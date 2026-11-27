package app.ezcall.receiver

import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal class ConversationActivity : AppCompatActivity() {
    private val state get() = ReceiverState
    private var address: String? = null
    private var contactName: String? = null
    private var requestedMessage: String? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recipient: AutoCompleteTextView
    private lateinit var recipientField: TextInputLayout
    private lateinit var composer: TextInputEditText
    private lateinit var messageField: TextInputLayout
    private lateinit var send: MaterialButton
    private lateinit var availability: TextView
    private lateinit var scroll: ScrollView
    private lateinit var bubbles: LinearLayout
    private lateinit var empty: LinearLayout
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
        contactName = savedInstanceState?.getString("contactName") ?: intent.getStringExtra("name")
        requestedMessage = if (savedInstanceState == null) intent.getStringExtra("messageID")
            else savedInstanceState.getString("messageID")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        val root = column().apply { setBackgroundColor(tone(M.attr.colorSurface)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val side = dp(((resources.configuration.screenWidthDp - 600) / 2).coerceAtLeast(0))
            view.setPadding(bars.left + side, bars.top, bars.right + side, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.receiver_back)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        recipientField = TextInputLayout(this, null, M.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.r_recipient)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        recipient = MaterialAutoCompleteTextView(recipientField.context).apply {
            threshold = 1
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            imeOptions = EditorInfo.IME_ACTION_NEXT
            minHeight = dp(48)
            background = null
            setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
            setText(savedInstanceState?.getString("recipient"))
            setOnItemClickListener { adapterView, _, position, _ ->
                val contact = adapterView.getItemAtPosition(position) as ContactNumber
                address = contact.number
                contactName = contact.name.takeIf(String::isNotBlank)
                setText(contact.number, false)
                render()
                composer.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
            }
            setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_NEXT) false
                else {
                    dismissDropDown()
                    composer.requestFocus()
                    true
                }
            }
        }
        recipientField.addView(recipient)
        root.addView(recipientField, margins(-1, -2).apply { topMargin = dp(8) })
        bubbles = column().apply {
            gravity = Gravity.BOTTOM
            setPaddingRelative(dp(16), dp(12), dp(16), dp(16))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        empty = column().apply {
            gravity = Gravity.CENTER
            setPaddingRelative(dp(24), dp(12), dp(24), dp(12))
            addView(TextView(this@ConversationActivity).apply {
                id = android.R.id.title
                setTextAppearance(R.style.TextAppearance_ezcall_TitleMedium)
                setTextColor(tone(M.attr.colorOnSurface))
                gravity = Gravity.CENTER
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(TextView(this@ConversationActivity).apply {
                id = android.R.id.summary
                setTextAppearance(R.style.TextAppearance_ezcall_BodyMedium)
                setTextColor(tone(M.attr.colorOnSurfaceVariant))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column().apply {
                gravity = Gravity.BOTTOM
                addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(bubbles, LinearLayout.LayoutParams(-1, -2))
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        availability = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextAppearance(R.style.TextAppearance_ezcall_BodyMedium)
            setTextColor(tone(M.attr.colorOnSurfaceVariant))
            background = bubble(M.attr.colorSurfaceContainerLow)
            setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(availability, margins(-1, -2))
        val stacked = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360
        val bar = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(12))
        }
        messageField = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
        }
        composer = TextInputEditText(messageField.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            minHeight = dp(48)
            background = bubble(M.attr.colorSurfaceContainer)
            hint = getString(R.string.r_text_message)
            setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
            setText(savedInstanceState?.getString("draft").orEmpty())
            setSelection(length())
        }
        messageField.addView(composer)
        bar.addView(messageField, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        send = MaterialButton(this).apply {
            contentDescription = getString(R.string.r_send)
            tooltipText = contentDescription
            setIconResource(R.drawable.receiver_send)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            setPadding(0, 0, 0, 0)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(24)
            iconTint = ColorStateList.valueOf(tone(M.attr.colorOnPrimary))
            minHeight = dp(48)
            isAllCaps = false
            setOnClickListener { submit() }
        }
        bar.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            if (stacked) topMargin = dp(4) else marginStart = dp(8)
        })
        root.addView(bar)
        setContentView(root)
        composer.doAfterTextChanged { messageField.error = null; updateSend() }
        recipient.doAfterTextChanged { recipientField.error = null; updateSend() }
        val savedScroll = savedInstanceState?.getInt("historyScroll")?.takeIf { requestedMessage == null }
        render()
        if (savedScroll != null) scroll.post { scroll.scrollTo(0, savedScroll) }
    }

    override fun onStart() {
        super.onStart()
        if (::send.isInitialized) { state.listeners.add(::render); render() }
    }

    override fun onStop() { state.listeners.remove(::render); super.onStop() }

    override fun onPostResume() {
        super.onPostResume()
        presentRingingCall()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::composer.isInitialized) return
        outState.putString("address", address)
        outState.putString("contactName", contactName)
        outState.putString("recipient", recipient.text.toString())
        outState.putString("draft", composer.text?.toString().orEmpty())
        outState.putString("messageID", requestedMessage)
        outState.putInt("historyScroll", scroll.scrollY)
    }

    private inner class ContactNumber(val name: String, val number: String) {
        override fun toString() = if (name.isBlank()) number else "$name  ${receiverNumberDisplay(number)}"
    }

    private fun submit() {
        val number = smsRecipient(address ?: recipient.text.toString())
        val body = composer.text?.toString().orEmpty()
        when {
            number == null -> recipientField.error = getString(R.string.r_enter_number)
            !Wire.validSmsBody(body) -> messageField.error = getString(R.string.r_validation_max)
            ReceiverService.current?.sendSms(number, body) == true -> {
                address = number
                composer.text?.clear()
                render()
                scroll.post { scroll.scrollTo(0, bubbles.height) }
            }
            else -> messageField.error = getString(R.string.r_sms_unavailable)
        }
    }

    private fun updateSend() {
        send.isEnabled = state.connected && state.canSendSms && !state.pendingSms && !composer.text.isNullOrBlank() &&
            (address ?: recipient.text.toString()).isNotBlank() && (address == null || smsRecipient(address.orEmpty()) != null)
    }

    private fun render() {
        if (!state.paired) { finish(); return }
        presentRingingCall()
        val target = address
        recipientField.visibility = if (target == null) View.VISIBLE else View.GONE
        val name = target?.let { contactName ?: state.data.name(it, getString(R.string.r_unknown)) }
        toolbar.title = when {
            name == null -> getString(R.string.r_new_sms)
            name == target -> receiverNumberDisplay(name)
            else -> name
        }
        toolbar.subtitle = target?.let {
            if (it == name || it.isBlank()) getString(R.string.r_sms_via_sender)
            else "${receiverNumberDisplay(it)} · ${getString(R.string.r_sms_via_sender)}"
        }
        if (target == null && lastContacts !== state.data.contacts) {
            lastContacts = state.data.contacts
            val numbers = state.data.contacts.flatMap { contact -> contact.numbers.map { ContactNumber(contact.name, it) } }
            recipient.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, numbers))
        }
        val reason = when {
            !state.connected -> R.string.r_sender_disconnected
            !state.canSendSms -> R.string.r_sms_needs_permission
            target != null && smsRecipient(target) == null -> R.string.r_read_only_sender
            state.pendingSms -> R.string.r_sending
            else -> 0
        }
        availability.visibility = if (reason == 0) View.GONE else View.VISIBLE
        if (reason != 0 && availability.text != getString(reason)) availability.setText(reason)
        updateSend()
        val key = target?.let { conversationKey(it, state.data.country) }
        val rows = state.visibleMessages().filter { key != null && conversationKey(it.address, state.data.country) == key }
            .asReversed().map { sms -> sms to state.attempts.find { it.sms.id == sms.id || it.syncedID == sms.id }?.detail }
        empty.findViewById<TextView>(android.R.id.title).setText(
            if (target == null) R.string.r_compose_empty else R.string.r_conversation_empty)
        empty.findViewById<TextView>(android.R.id.summary).setText(
            if (target == null) R.string.r_compose_empty_detail else R.string.r_conversation_empty_detail)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (lastRows == rows) return
        val atBottom = lastRows == null || !scroll.canScrollVertically(1)
        lastRows = rows
        bubbles.removeAllViews()
        var requestedRow: View? = null
        var previous = 0L
        for ((sms, detail) in rows) {
            if (sms.date > 0 && (previous <= 0 || sms.date - previous >= 30 * DateUtils.MINUTE_IN_MILLIS)) {
                bubbles.addView(TextView(this).apply {
                    setTextAppearance(R.style.TextAppearance_ezcall_LabelMedium)
                    setTextColor(tone(M.attr.colorOnSurfaceVariant))
                    gravity = Gravity.CENTER
                    text = DateUtils.formatDateTime(this@ConversationActivity, sms.date,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                    setPadding(0, dp(8), 0, dp(8))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            previous = sms.date
            val outgoing = sms.direction == "out"
            val side = if (outgoing) Gravity.END else Gravity.START
            val box = column().apply {
                gravity = side
                setPaddingRelative(if (outgoing) dp(36) else 0, dp(6), if (outgoing) 0 else dp(36), dp(6))
            }
            box.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_ezcall_BodyLarge)
                setTextColor(tone(if (outgoing) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurface))
                background = bubble(if (outgoing) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                text = sms.body
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-2, -2))
            if (detail != null) box.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_ezcall_LabelSmall)
                val normal = detail == "SMS sent." || detail == "Sending SMS…"
                setTextColor(tone(if (normal) M.attr.colorOnSurfaceVariant else androidx.appcompat.R.attr.colorError))
                text = receiverNotice(detail)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = side; topMargin = dp(2) })
            bubbles.addView(box)
            if (sms.id == requestedMessage) requestedRow = box
        }
        if (requestedRow != null) {
            requestedMessage = null
            scroll.post { scroll.scrollTo(0, bubbles.top + requestedRow.top) }
        } else if (atBottom) scroll.post { scroll.scrollTo(0, bubbles.height) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun tone(attr: Int) = MaterialColors.getColor(this, attr, 0)
    private fun bubble(attr: Int) = GradientDrawable().apply { setColor(tone(attr)); cornerRadius = dp(22).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun margins(width: Int, height: Int) = LinearLayout.LayoutParams(width, height).apply {
        marginStart = dp(16); marginEnd = dp(16)
    }
}
