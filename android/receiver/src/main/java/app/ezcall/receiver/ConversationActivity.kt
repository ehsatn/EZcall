package app.ezcall.receiver

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import app.ezcall.android.PairKey
import app.ezcall.android.Wire
import com.google.android.material.R as M
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

internal class ConversationActivity : AppCompatActivity() {
    private val state get() = ReceiverState
    private var address: String? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recipient: AutoCompleteTextView
    private lateinit var composer: EditText
    private lateinit var send: MaterialButton
    private lateinit var availability: TextView
    private lateinit var scroll: ScrollView
    private lateinit var bubbles: LinearLayout
    private var lastRows: List<Pair<ReceivedSms, String?>>? = null
    private var lastContacts: List<ReceivedContact>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ReceiverService.current == null) {
            state.paired = PairKey(this).exists()
            if (state.paired) {
                state.data = ReceiverVault(this).load()
                if (ReceiverService.hasNearby(this)) ReceiverService.start(this)
            }
        }
        if (!state.paired) { finish(); return }
        address = savedInstanceState?.getString("address") ?: intent.getStringExtra("address")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = column()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.receiver_back)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        recipient = AutoCompleteTextView(this).apply {
            hint = getString(R.string.r_recipient)
            threshold = 1
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(savedInstanceState?.getString("recipient"))
            setOnItemClickListener { adapterView, _, position, _ ->
                address = (adapterView.getItemAtPosition(position) as ContactNumber).number
                composer.requestFocus()
                render()
            }
        }
        root.addView(recipient, margins(-1, -2))
        bubbles = column().apply { gravity = Gravity.BOTTOM; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(bubbles) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        availability = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(availability, margins(-1, -2))
        val bar = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        composer = EditText(this).apply {
            hint = getString(R.string.r_message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            minHeight = dp(48)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = bubble(M.attr.colorSurfaceContainerHigh)
            setText(savedInstanceState?.getString("draft"))
            setSelection(length())
        }
        bar.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        send = MaterialButton(this).apply {
            text = getString(R.string.r_send)
            minHeight = dp(48)
            setOnClickListener { submit() }
        }
        bar.addView(send, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(bar)
        setContentView(root)
        composer.doAfterTextChanged { updateSend() }
        recipient.doAfterTextChanged { updateSend() }
        render()
    }

    override fun onStart() {
        super.onStart()
        if (::send.isInitialized) { state.listeners.add(::render); render() }
    }

    override fun onStop() { state.listeners.remove(::render); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::composer.isInitialized) return
        outState.putString("address", address)
        outState.putString("recipient", recipient.text.toString())
        outState.putString("draft", composer.text.toString())
    }

    private data class ContactNumber(val name: String, val number: String) {
        override fun toString() = "$name  $number"
    }

    private fun submit() {
        val number = smsRecipient(address ?: recipient.text.toString())
        val body = composer.text.toString()
        when {
            number == null -> recipient.error = getString(R.string.r_enter_number)
            !Wire.validSmsBody(body) -> composer.error = getString(R.string.r_validation_max)
            ReceiverService.current?.sendSms(number, body) == true -> {
                address = number
                composer.text.clear()
                render()
                scroll.post { scroll.scrollTo(0, bubbles.height) }
            }
            else -> composer.error = getString(R.string.r_sms_unavailable)
        }
    }

    private fun updateSend() {
        send.isEnabled = state.connected && state.canSendSms && !state.pendingSms && composer.text.isNotBlank() &&
            (address ?: recipient.text.toString()).isNotBlank() && (address == null || smsRecipient(address.orEmpty()) != null)
    }

    private fun render() {
        if (!state.paired) { finish(); return }
        val target = address
        recipient.visibility = if (target == null) View.VISIBLE else View.GONE
        toolbar.title = if (target == null) getString(R.string.r_new_sms) else state.data.name(target, getString(R.string.r_unknown))
        toolbar.subtitle = target?.takeIf { it.isNotBlank() && it != toolbar.title }
        if (target == null && lastContacts !== state.data.contacts) {
            lastContacts = state.data.contacts
            val numbers = state.data.contacts.flatMap { contact -> contact.numbers.map { ContactNumber(contact.name, it) } }
            recipient.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, numbers))
        }
        val reason = when {
            !state.connected -> R.string.r_sender_disconnected
            !state.canSendSms -> R.string.r_sms_needs_permission
            target != null && smsRecipient(target) == null -> R.string.r_read_only_sender
            else -> 0
        }
        availability.visibility = if (reason == 0) View.GONE else View.VISIBLE
        if (reason != 0) availability.setText(reason)
        updateSend()
        val key = target?.let { conversationKey(it, state.data.country) }
        val rows = state.visibleMessages().filter { key != null && conversationKey(it.address, state.data.country) == key }
            .asReversed().map { sms -> sms to state.attempts.find { it.sms.id == sms.id || it.syncedID == sms.id }?.detail }
        if (lastRows == rows) return
        val atBottom = lastRows == null || !scroll.canScrollVertically(1)
        lastRows = rows
        bubbles.removeAllViews()
        for ((sms, detail) in rows) {
            val outgoing = sms.direction == "out"
            val side = if (outgoing) Gravity.END else Gravity.START
            val box = column().apply {
                gravity = side
                setPaddingRelative(if (outgoing) dp(42) else 0, dp(4), if (outgoing) 0 else dp(42), dp(4))
            }
            box.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_ezcall_BodyLarge)
                setTextColor(tone(if (outgoing) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurface))
                background = bubble(if (outgoing) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                text = sms.body
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-2, -2))
            box.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_ezcall_LabelSmall)
                val date = DateUtils.formatDateTime(this@ConversationActivity, sms.date,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                text = if (detail == null) date else "$date · ${receiverNotice(detail)}"
                contentDescription = getString(if (outgoing) R.string.r_outgoing_sms else R.string.r_incoming_sms, text)
            })
            bubbles.addView(box)
        }
        if (atBottom) scroll.post { scroll.scrollTo(0, bubbles.height) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun tone(attr: Int) = MaterialColors.getColor(this, attr, 0)
    private fun bubble(attr: Int) = GradientDrawable().apply { setColor(tone(attr)); cornerRadius = dp(22).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun margins(width: Int, height: Int) = LinearLayout.LayoutParams(width, height).apply {
        marginStart = dp(16); marginEnd = dp(16)
    }
}
