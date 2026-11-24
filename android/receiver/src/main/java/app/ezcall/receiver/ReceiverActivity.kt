package app.ezcall.receiver

import android.Manifest.permission
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.text.buildSpannedString
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import app.ezcall.android.PairKey
import app.ezcall.android.PairingArtwork
import app.ezcall.android.languageName
import app.ezcall.android.showEzcallLanguage
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.util.Date
import kotlin.concurrent.thread

internal class IncomingCallActivity : ReceiverActivity()

open class ReceiverActivity : AppCompatActivity() {
    private val state get() = ReceiverState
    private lateinit var pairKey: PairKey
    private lateinit var dashboard: LinearLayout
    private lateinit var pairingScreen: ScrollView
    private lateinit var pairingFeedback: MaterialCardView
    private lateinit var pairingStatus: TextView
    private lateinit var pairingProgress: LinearProgressIndicator
    private lateinit var scanButton: MaterialButton
    private lateinit var photoButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var connection: MaterialCardView
    private lateinit var linkBadge: FrameLayout
    private lateinit var linkTitle: TextView
    private lateinit var linkDetail: TextView
    private lateinit var syncButton: ImageButton
    private lateinit var fullScreenAccess: MaterialButton
    private lateinit var callPanel: MaterialCardView
    private lateinit var sectionTitle: TextView
    private lateinit var createAction: ExtendedFloatingActionButton
    private lateinit var list: ListView
    private lateinit var emptyPanel: LinearLayout
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDetail: TextView
    private lateinit var adapter: Rows
    private var secret: ByteArray? = null
    private var cameraFile: File? = null
    private var micAction: ((Boolean) -> Unit)? = null
    private var activeTab = Destination.CALLS
    private val scrollPositions = IntArray(Destination.entries.size)
    private val scrollOffsets = IntArray(Destination.entries.size)
    private var statusOverride: String? = null
    private var openingQR = false
    private var listData: ReceiverData? = null
    private var listAttempts: List<SmsAttempt>? = null
    private var renderedTab: Destination? = null
    private var callPanelContents: List<Any?>? = null
    private var surfacedCall: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (this is IncomingCallActivity) {
            callPanel = card(24)
            val screen = column().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(40), dp(24), dp(40))
                addView(heading(getString(R.string.r_call_from_sender), R.style.TextAppearance_ezcall_TitleLarge),
                    fullWidth(bottom = 16))
                addView(callPanel, fullWidth())
            }
            setContentView(ScrollView(this).apply { isFillViewport = true; addView(screen) })
            render()
            return
        }
        pairKey = PairKey(this)
        activeTab = Destination.entries.getOrElse(savedInstanceState?.getInt("tab") ?: 0) { Destination.CALLS }
        cameraFile = savedInstanceState?.getString("camera")?.let(::File)
        if (ReceiverService.current == null) {
            state.paired = pairKey.exists()
            state.data = if (state.paired) ReceiverVault(this).load() else ReceiverData()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        val root = column().apply { setBackgroundColor(color(android.R.attr.colorBackground)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = dp(((resources.configuration.screenWidthDp - 600) / 2).coerceAtLeast(0))
            view.setPadding(bars.left + side, bars.top, bars.right + side, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        root.addView(buildToolbar())

        val content = FrameLayout(this)
        pairingScreen = buildPairingScreen()
        dashboard = buildDashboard(savedInstanceState)
        content.addView(pairingScreen, FrameLayout.LayoutParams(-1, -1))
        content.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
        if (savedInstanceState == null && checkSelfPermission(permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission.RECORD_AUDIO), MIC)
        }
    }

    private fun buildToolbar() = MaterialToolbar(this).apply {
        minimumHeight = dp(64)
        setContentInsetsRelative(dp(24), dp(16))
        setPaddingRelative(0, 0, dp(10), 0)
        addView(row().apply {
            addView(FrameLayout(this@ReceiverActivity).apply {
                background = rounded(getColor(R.color.launcher_background), 13)
                addView(smallIcon(R.drawable.ezcall_icon_mark, Color.WHITE),
                    FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(column().apply {
                addView(heading(getString(R.string.ez_brand), R.style.TextAppearance_ezcall_TitleLarge))
                addView(label(getString(R.string.r_receiver), R.style.TextAppearance_ezcall_LabelMedium,
                    MaterialR.attr.colorOnSurfaceVariant))
            })
        }, Toolbar.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        menu.add(getString(R.string.ez_settings)).apply {
            icon = iconDrawable(R.drawable.ic_settings, MaterialR.attr.colorOnSurface)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { options(); true }
        }
    }

    private fun buildPairingScreen() = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(column().apply {
            setPaddingRelative(dp(24), dp(12), dp(24), dp(20))

            val hero = card(32).apply {
                setCardBackgroundColor(getColor(R.color.ezcall_pairing_hero))
                addView(PairingArtwork(this@ReceiverActivity), FrameLayout.LayoutParams(-1, -1))
            }
            addView(hero, LinearLayout.LayoutParams(-1, dp(232)))

            addView(heading(getString(R.string.ez_pair_phones), R.style.TextAppearance_ezcall_HeadlineLarge),
                fullWidth(top = 24))
            addView(label(getString(R.string.r_pair_instructions),
                R.style.TextAppearance_ezcall_BodyLarge, MaterialR.attr.colorOnSurfaceVariant),
                fullWidth(top = 8))

            pairingFeedback = card(16)
            val notice = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            pairingStatus = label("", R.style.TextAppearance_ezcall_BodyMedium).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            pairingProgress = LinearProgressIndicator(this@ReceiverActivity).apply {
                isIndeterminate = true
                trackCornerRadius = dp(2)
            }
            notice.addView(pairingStatus)
            notice.addView(pairingProgress, fullWidth(top = 8))
            pairingFeedback.addView(notice)
            addView(pairingFeedback, fullWidth(top = 16))

            addView(Space(this@ReceiverActivity), LinearLayout.LayoutParams(-1, dp(24), 1f))
            scanButton = button(getString(R.string.r_scan_qr), R.drawable.ic_qr, click = ::camera)
            photoButton = button(getString(R.string.r_choose_qr), R.drawable.receiver_photo, secondary = true, click = ::choosePhoto)
            cancelButton = button(getString(R.string.ez_cancel_pairing), R.drawable.ic_close, secondary = true) { ReceiverService.current?.forget() }
            addView(scanButton, LinearLayout.LayoutParams(-1, -2))
            addView(photoButton, fullWidth(top = 8))
            addView(cancelButton, fullWidth(top = 8))

            addView(row().apply {
                gravity = Gravity.CENTER
                addView(smallIcon(R.drawable.receiver_lock, color(MaterialR.attr.colorOnSurfaceVariant)),
                    LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
                addView(label(getString(R.string.r_encrypted), R.style.TextAppearance_ezcall_LabelMedium,
                    MaterialR.attr.colorOnSurfaceVariant))
            }, fullWidth(top = 16))
        })
    }

    private fun buildDashboard(saved: Bundle?) = column().apply {
        val content = FrameLayout(this@ReceiverActivity)
        val header = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        callPanel = card(24)
        header.addView(callPanel, fullWidth(bottom = 12))

        connection = card(22).apply {
            setCardBackgroundColor(color(MaterialR.attr.colorSurface))
            setStrokeColor(ColorStateList.valueOf(color(MaterialR.attr.colorOutlineVariant)))
            strokeWidth = dp(1)
        }
        val linkRow = row().apply { setPaddingRelative(dp(12), dp(12), dp(8), dp(12)) }
        linkBadge = FrameLayout(this@ReceiverActivity).apply {
            addView(smallIcon(R.drawable.ic_bluetooth, color(MaterialR.attr.colorOnPrimaryContainer)),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        linkRow.addView(linkBadge, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        linkTitle = heading("", R.style.TextAppearance_ezcall_TitleMedium)
        linkDetail = label("", R.style.TextAppearance_ezcall_BodyMedium).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        linkRow.addView(column().apply { addView(linkTitle); addView(linkDetail, fullWidth(top = 2)) },
            LinearLayout.LayoutParams(0, -2, 1f))
        syncButton = iconButton(R.drawable.receiver_sync, getString(R.string.r_sync)) { ReceiverService.current?.sync() }
        linkRow.addView(syncButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        connection.addView(linkRow)
        header.addView(connection)
        fullScreenAccess = button(getString(R.string.r_enable_call_screen), R.drawable.ic_call, secondary = true) {
            if (Build.VERSION.SDK_INT >= 34) {
                val uri = Uri.fromParts("package", packageName, null)
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, uri)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
            }
        }
        header.addView(fullScreenAccess, fullWidth(top = 8))

        sectionTitle = heading("", R.style.TextAppearance_ezcall_HeadlineSmall)
        header.addView(sectionTitle, fullWidth(top = 24, bottom = 8))
        emptyPanel = buildEmptyPanel()
        adapter = Rows()
        list = ListView(this@ReceiverActivity).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            divider = null
            addHeaderView(header, null, false)
            addFooterView(emptyPanel, null, false)
            adapter = this@ReceiverActivity.adapter
            setOnItemClickListener { parent, _, position, _ -> (parent.getItemAtPosition(position) as? Row)?.open?.invoke() }
        }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))

        createAction = ExtendedFloatingActionButton(this@ReceiverActivity).apply {
            setOnClickListener { newAction() }
        }
        content.addView(createAction, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20)
            bottomMargin = dp(16)
        })
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = BottomNavigationView(this@ReceiverActivity).apply {
            setBackgroundColor(color(MaterialR.attr.colorSurface))
            elevation = dp(3).toFloat()
            Destination.entries.forEach { tab -> menu.add(0, tab.ordinal + 1, 0, tab.title).setIcon(tab.icon) }
            selectedItemId = activeTab.ordinal + 1
            setOnItemSelectedListener { target ->
                storeScrollPosition()
                activeTab = Destination.entries[target.itemId - 1]
                render()
                list.adapter = this@ReceiverActivity.adapter
                restoreScrollPosition()
                true
            }
            setOnItemReselectedListener { list.smoothScrollToPosition(0) }
        }
        addView(nav, LinearLayout.LayoutParams(-1, -2))
        saved?.getIntArray("scrollPositions")?.copyInto(scrollPositions)
        saved?.getIntArray("scrollOffsets")?.copyInto(scrollOffsets)
        if (saved != null) list.post { restoreScrollPosition() }
    }

    private fun buildEmptyPanel() = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(28), dp(32), dp(28), dp(20))
        emptyIcon = smallIcon(activeTab.icon, color(MaterialR.attr.colorOnPrimaryContainer)).apply {
            background = rounded(color(MaterialR.attr.colorPrimaryContainer), 21)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        addView(emptyIcon, LinearLayout.LayoutParams(dp(60), dp(60)))
        emptyTitle = heading("", R.style.TextAppearance_ezcall_TitleMedium).apply { gravity = Gravity.CENTER }
        addView(emptyTitle, fullWidth(top = 16))
        emptyDetail = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant)
            .apply { gravity = Gravity.CENTER }
        addView(emptyDetail, fullWidth(top = 4))
    }

    override fun onStart() {
        super.onStart()
        state.listeners.add(::render)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (this is IncomingCallActivity) return
        if (pairKey.exists() && ReceiverService.current == null && ReceiverService.hasNearby(this)) {
            runCatching { ReceiverService.start(this) }
        }
        ReceiverService.current?.refreshLocale()
        render()
    }

    override fun onStop() {
        state.listeners.remove(::render)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this is IncomingCallActivity) return
        if (dashboard.isShown) storeScrollPosition()
        outState.putInt("tab", activeTab.ordinal)
        outState.putIntArray("scrollPositions", scrollPositions)
        outState.putIntArray("scrollOffsets", scrollOffsets)
        outState.putString("camera", cameraFile?.path)
    }

    private fun storeScrollPosition() {
        scrollPositions[activeTab.ordinal] = list.firstVisiblePosition
        scrollOffsets[activeTab.ordinal] = list.getChildAt(0)?.top ?: 0
    }

    private fun restoreScrollPosition() {
        val position = scrollPositions[activeTab.ordinal].coerceAtMost(list.count - 1)
        val offset = scrollOffsets[activeTab.ordinal]
        if (position > 0 || offset < 0) list.setSelectionFromTop(position, offset)
    }

    private fun render() {
        if (this is IncomingCallActivity) {
            if (state.paired && state.inCall != null && state.inCall?.id == intent.identifier) renderCall()
            else finishAndRemoveTask()
            return
        }
        val paired = state.paired || pairKey.exists()
        pairingScreen.visibility = visible(!paired)
        dashboard.visibility = visible(paired)
        if (!paired) {
            renderPairing()
            return
        }
        renderConnection()
        fullScreenAccess.visibility = visible(Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
        renderCall()

        val canDial = state.canDial && state.inCall == null && !state.pendingCall
        val canText = state.canSendSms && !state.pendingSms
        val action = when {
            activeTab == Destination.MESSAGES && canText -> Destination.MESSAGES
            activeTab == Destination.CALLS && canDial -> Destination.CALLS
            canDial && !canText -> Destination.CALLS
            canText && !canDial -> Destination.MESSAGES
            else -> null
        }
        val newVisible = state.connected && (if (activeTab == Destination.MESSAGES) canText else canDial || canText)
        createAction.visibility = visible(newVisible)
        createAction.text = when (action) {
            Destination.CALLS -> getString(R.string.r_new_call)
            Destination.MESSAGES -> getString(R.string.r_new_sms)
            else -> getString(R.string.r_new)
        }
        createAction.icon = iconDrawable(action?.icon ?: R.drawable.receiver_plus, MaterialR.attr.colorOnPrimaryContainer)
        list.setPadding(0, 0, 0, dp(if (newVisible) 88 else 20))

        if (listData === state.data && listAttempts === state.attempts && renderedTab == activeTab) return
        listData = state.data
        listAttempts = state.attempts
        renderedTab = activeTab
        renderRows()
    }

    private fun renderPairing() {
        val enrolling = ReceiverService.current != null
        val feedback = statusOverride ?: when {
            openingQR -> getString(R.string.r_reading_code)
            enrolling -> receiverNotice(state.detail).ifBlank { getString(R.string.r_pairing) }
            else -> state.detail.takeIf { it.contains("timed out") || it.startsWith("Could not") }?.let(::receiverNotice)
        }
        val busy = openingQR || enrolling
        pairingFeedback.visibility = visible(feedback != null)
        pairingFeedback.setCardBackgroundColor(color(if (busy) MaterialR.attr.colorSurfaceContainer else MaterialR.attr.colorErrorContainer))
        pairingStatus.setTextColor(color(if (busy) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnErrorContainer))
        if (pairingStatus.text.toString() != feedback.orEmpty()) pairingStatus.text = feedback.orEmpty()
        pairingProgress.visibility = visible(busy)
        scanButton.visibility = visible(!enrolling)
        photoButton.visibility = visible(!enrolling)
        scanButton.isEnabled = !openingQR
        photoButton.isEnabled = !openingQR
        cancelButton.visibility = visible(enrolling)
    }

    private fun renderConnection() {
        val permissionMissing = !ReceiverService.hasNearby(this)
        val connected = state.connected
        val title = when {
            permissionMissing -> getString(R.string.r_need_nearby)
            state.syncing -> getString(R.string.r_sync_active)
            connected -> getString(R.string.r_sender_connected)
            else -> getString(R.string.r_sender_disconnected)
        }
        val detail = when {
            permissionMissing -> getString(R.string.r_allow_nearby_in_settings)
            state.syncing -> ""
            connected && (state.detail.isBlank() || state.detail == "Connected over Bluetooth.") -> getString(R.string.r_active_bt)
            state.detail.isNotBlank() -> receiverNotice(state.detail)
            else -> getString(R.string.r_searching)
        }
        linkBadge.background = rounded(color(if (connected) MaterialR.attr.colorPrimaryContainer
            else MaterialR.attr.colorSurfaceContainerHigh), 14)
        linkDetail.setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
        if (linkTitle.text.toString() != title) linkTitle.text = title
        if (linkDetail.text.toString() != detail) linkDetail.text = detail
        linkDetail.visibility = visible(detail.isNotEmpty())
        syncButton.visibility = visible(connected)
        syncButton.isEnabled = !state.syncing && state.inCall?.state != "active"
        syncButton.alpha = if (syncButton.isEnabled) 1f else 0.38f
    }

    private fun renderCall() {
        val call = state.inCall
        val name = call?.let { state.data.name(it.address, getString(R.string.r_unknown)) }
        val callContents = listOf(call, name, state.pendingCall, state.canControlCurrentCall, state.microphoneLive, state.microphoneAvailable)
        if (callPanelContents == callContents) return
        callPanelContents = callContents
        callPanel.removeAllViews()
        callPanel.visibility = visible(call != null)
        if (call == null) {
            surfacedCall = null
            return
        }

        val ringing = call.state == "ringing"
        callPanel.setCardBackgroundColor(color(MaterialR.attr.colorPrimaryContainer))
        val ink = MaterialR.attr.colorOnPrimaryContainer
        callPanel.addView(column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(12))
            addView(row().apply {
                addView(badge(R.drawable.ic_call, MaterialR.attr.colorSurface, ink),
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
                addView(column().apply {
                    addView(label(if (ringing) getString(R.string.r_call_incoming) else getString(R.string.r_call_active),
                        R.style.TextAppearance_ezcall_LabelMedium, ink))
                    addView(heading(name.orEmpty(), R.style.TextAppearance_ezcall_TitleLarge, ink))
                    if (call.address.isNotBlank() && name != call.address) {
                        addView(label(call.address, R.style.TextAppearance_ezcall_BodyMedium, ink))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            if (state.canControlCurrentCall) addView(row().apply {
                if (ringing) addView(button(getString(R.string.r_answer), R.drawable.ic_call) {
                    microphone { ReceiverService.current?.answer(call.id, it) }
                }.apply { isEnabled = !state.pendingCall }, actionWidth(spacing = true))
                if (!ringing && state.microphoneAvailable) {
                    addView(button(if (state.microphoneLive) getString(R.string.r_mute) else getString(R.string.r_unmute), R.drawable.receiver_mic, secondary = true) {
                        ReceiverService.current?.mute()
                    }, actionWidth(spacing = true))
                }
                addView(button(if (ringing) getString(R.string.r_decline) else getString(R.string.r_end_call), R.drawable.receiver_end, danger = true) {
                    ReceiverService.current?.end(call.id)
                }.apply { isEnabled = !state.pendingCall }, actionWidth())
            }, fullWidth(top = 12))
        })
        if (ringing && surfacedCall != call.id) {
            surfacedCall = call.id
            if (this !is IncomingCallActivity) list.setSelection(0)
        }
    }

    private fun renderRows() {
        sectionTitle.text = getString(activeTab.title)
        val rows = when (activeTab) {
            Destination.MESSAGES -> state.visibleMessages().distinctBy { conversationKey(it.address, state.data.country) }.map { sms ->
                val title = state.data.name(sms.address, getString(R.string.r_unknown))
                val preview = sms.body.trim().replace('\n', ' ')
                Row(title, if (sms.direction == "out") getString(R.string.r_you_message, preview) else preview,
                    date(sms.date), R.drawable.ic_sms, if (title == sms.address) "" else initials(title)) { compose(sms.address) }
            }
            Destination.CONTACTS -> state.data.contacts.map { contact ->
                val title = contact.name.ifBlank { contact.numbers.firstOrNull().orEmpty() }
                Row(title, contact.numbers.joinToString(" · "), "", R.drawable.ic_contacts,
                    if (contact.name.isNotBlank()) initials(contact.name) else "") { contactActions(contact) }
            }
            Destination.CALLS -> state.data.calls.map { call ->
                val title = state.data.name(call.address, getString(R.string.r_unknown))
                val detail = call.address.takeUnless { it == title }.orEmpty()
                Row(title, detail, date(call.date), R.drawable.ic_call) {
                    if (call.address.isNotBlank()) numberActions(call.address)
                }
            }
        }
        emptyTitle.text = getString(activeTab.emptyTitle)
        emptyDetail.text = getString(activeTab.emptyDetail)
        emptyIcon.setImageResource(activeTab.icon)
        emptyPanel.visibility = visible(rows.isEmpty())
        adapter.update(rows)
    }


    private fun options() {
        val entries = mutableListOf(SheetAction(R.drawable.ic_language, getString(R.string.ez_language), languageName()) {
            showEzcallLanguage(getString(R.string.ez_language))
        })
        if (!state.paired && !pairKey.exists()) {
            sheet(getString(R.string.ez_settings), entries)
            return
        }
        entries += SheetAction(R.drawable.ic_call, getString(R.string.r_phone_account), getString(R.string.r_phone_account_detail)) {
            if (checkSelfPermission(permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) openPhoneAccounts()
            else {
                micAction = { openPhoneAccounts() }
                requestPermissions(arrayOf(permission.RECORD_AUDIO), MIC)
            }
        }
        if (state.connected) entries += SheetAction(R.drawable.receiver_check, getString(R.string.r_test),
            getString(R.string.r_check_link)) { ReceiverService.current?.test() }
        if (state.connected && !state.syncing && state.inCall?.state != "active") {
            entries += SheetAction(R.drawable.receiver_sync, getString(R.string.r_sync),
                getString(R.string.r_sync_detail)) { ReceiverService.current?.sync() }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            entries += SheetAction(R.drawable.ic_notifications, getString(R.string.r_allow_notifications),
                getString(R.string.r_notifications_detail)) {
                requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), NOTIFICATIONS)
            }
        }
        if (!ReceiverService.hasNearby(this)) {
            entries += SheetAction(R.drawable.ic_bluetooth, getString(R.string.r_allow_nearby), getString(R.string.r_connect_sender)) {
                requestNearby()
            }
        }
        entries += SheetAction(R.drawable.ic_link, getString(R.string.r_forget_sender), getString(R.string.r_remove_data), danger = true) {
            MaterialAlertDialogBuilder(this).setTitle(getString(R.string.r_forget_sender_title))
                .setMessage(getString(R.string.r_remove_details))
                .setNegativeButton(getString(R.string.ez_cancel), null).setPositiveButton(getString(R.string.ez_forget)) { _, _ ->
                    val running = ReceiverService.current
                    if (running != null) {
                        running.forget()
                    } else {
                        runCatching { pairKey.forget(); ReceiverVault(this).clear() }.onSuccess {
                            RelayConnectionService.unregister(this)
                            state.attempts = emptyList()
                            state.paired = false
                            state.data = ReceiverData()
                            state.detail = getString(R.string.r_pair_removed)
                        }.onFailure { statusOverride = getString(R.string.r_remove_failed) }
                    }
                    render()
                }.show()
        }
        sheet(getString(R.string.ez_settings), entries)
    }

    private fun openPhoneAccounts() {
        val opened = RelayConnectionService.register(this) && runCatching {
            startActivity(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
        }.isSuccess
        if (!opened) MaterialAlertDialogBuilder(this).setMessage(R.string.r_phone_account_help)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun newAction() {
        val canDial = state.canDial && state.inCall == null && !state.pendingCall
        val canText = state.canSendSms && !state.pendingSms
        when {
            activeTab == Destination.CALLS && canDial -> dial()
            activeTab == Destination.MESSAGES && canText -> compose()
            canDial && canText -> sheet(getString(R.string.r_new), listOf(
                SheetAction(R.drawable.ic_call, getString(R.string.r_new_call), getString(R.string.r_dial_info)) { dial() },
                SheetAction(R.drawable.ic_sms, getString(R.string.r_new_sms), getString(R.string.r_send_info)) { compose() }
            ))
            canDial -> dial()
            canText -> compose()
        }
    }

    private data class SheetAction(val icon: Int, val title: String, val detail: String,
        val danger: Boolean = false, val open: () -> Unit)

    private fun sheet(title: String, entries: List<SheetAction>) {
        val dialog = BottomSheetDialog(this)
        val content = column()
        content.addView(BottomSheetDragHandleView(this), fullWidth())
        content.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column().apply {
                setPadding(dp(12), 0, dp(12), dp(16))
                addView(heading(title, R.style.TextAppearance_ezcall_HeadlineSmall)
                    .apply { setPadding(dp(12), 0, dp(12), dp(12)) })
                for (entry in entries) {
                    val semanticInk = if (entry.danger) androidx.appcompat.R.attr.colorError else MaterialR.attr.colorOnSurface
                    addView(row().apply {
                        minimumHeight = dp(64)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = ripple(16)
                        isFocusable = true
                        isScreenReaderFocusable = true
                        setOnClickListener {
                            dialog.dismiss()
                            entry.open()
                        }
                        addView(smallIcon(entry.icon, color(semanticInk)), LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            marginEnd = dp(16)
                        })
                        addView(column().apply {
                            addView(label(entry.title, R.style.TextAppearance_ezcall_TitleSmall, semanticInk))
                            addView(label(entry.detail, R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    })
                }
            })
        }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(content)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun contactActions(contact: ReceivedContact) {
        if (contact.numbers.size == 1) numberActions(contact.numbers.first())
        else if (contact.numbers.isNotEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(contact.name)
                .setItems(contact.numbers.toTypedArray()) { _, index -> numberActions(contact.numbers[index]) }.show()
        }
    }

    private fun numberActions(number: String) {
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (state.canDial && state.inCall == null) entries += getString(R.string.r_call) to { dial(number) }
        if (state.canSendSms) entries += getString(R.string.r_send_sms) to { compose(number) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(number).setNegativeButton(getString(R.string.ez_close), null)
        if (entries.isNotEmpty()) dialog.setItems(entries.map { it.first }.toTypedArray()) { _, index -> entries[index].second() }
        dialog.show()
    }

    private fun dial(initial: String = "") {
        if (state.inCall != null || state.pendingCall) return
        val dialog = BottomSheetDialog(this)
        val pad = column().apply { setPadding(dp(24), 0, dp(24), dp(16)) }
        pad.addView(BottomSheetDragHandleView(this), fullWidth())
        pad.addView(heading(getString(R.string.r_new_call), R.style.TextAppearance_ezcall_HeadlineSmall))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
            showSoftInputOnFocus = false
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            textSize = 28f
            background = null
            hint = getString(R.string.r_phone_number)
            setText(initial)
            setSelection(length())
        }
        fun edit(key: String) {
            val start = minOf(input.selectionStart, input.selectionEnd).coerceAtLeast(0)
            val end = maxOf(input.selectionStart, input.selectionEnd).coerceAtLeast(0)
            val from = if (key.isEmpty() && start == end) (start - 1).coerceAtLeast(0) else start
            input.text.replace(from, end, key)
        }
        val erase = iconButton(R.drawable.ic_backspace, getString(R.string.r_delete_digit)) { edit("") }
        pad.addView(row().apply {
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(erase, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, fullWidth(top = 8, bottom = 8))

        val legends = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", "")
        "123456789*0#".chunked(3).forEachIndexed { index, keys ->
            val keyRow = row().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
            keys.forEachIndexed { col, digit ->
                val legend = legends[index * 3 + col]
                val key = MaterialButton(this, null, MaterialR.attr.materialButtonTonalStyle).apply {
                    isAllCaps = false
                    textSize = 25f
                    minimumHeight = dp(60)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    insetTop = dp(2)
                    insetBottom = dp(2)
                    text = buildSpannedString {
                        append(digit)
                        if (legend.isNotEmpty()) {
                            append('\n')
                            append(legend, RelativeSizeSpan(0.44f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    setOnClickListener { edit(digit.toString()) }
                    if (digit == '0') setOnLongClickListener { edit("+"); true }
                }
                keyRow.addView(key, actionWidth(spacing = col < 2))
            }
            pad.addView(keyRow, fullWidth())
        }
        val call = button(getString(R.string.r_call), R.drawable.ic_call) {
            val number = normalizedNumber(input.text.toString())
            if (number.any(Char::isDigit)) {
                dialog.dismiss()
                microphone { ReceiverService.current?.dial(number, it) }
            }
        }
        fun updateActions() {
            erase.isEnabled = input.length() > 0
            call.isEnabled = input.text.any(Char::isDigit)
        }
        input.doAfterTextChanged { updateActions() }
        updateActions()
        pad.addView(call, fullWidth(top = 8))
        dialog.setContentView(ScrollView(this).apply { addView(pad) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun compose(initial: String? = null) {
        startActivity(Intent(this, ConversationActivity::class.java).putExtra("address", initial))
    }

    private fun microphone(action: (Boolean) -> Unit) {
        val granted = checkSelfPermission(permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val needsMic = state.audioMode == "two_way" || state.inCall?.simulated == true
        if (!needsMic || granted) action(needsMic && granted)
        else {
            micAction = action
            requestPermissions(arrayOf(permission.RECORD_AUDIO), MIC)
        }
    }

    private fun camera() {
        statusOverride = null
        val captured = runCatching {
            val directory = cacheDir.resolve("qr").apply { mkdirs() }
            val photo = File.createTempFile("pairing-", ".jpg", directory)
            cameraFile = photo
            val uri = FileProvider.getUriForFile(this, "app.ezcall.receiver.qr", photo)
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri(getString(R.string.r_pairing_photo), uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, CAMERA)
        }.isSuccess
        if (!captured) {
            cameraFile?.delete()
            cameraFile = null
            choosePhoto()
        }
    }

    private fun choosePhoto() {
        statusOverride = null
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PHOTO)
        }.onFailure {
            statusOverride = getString(R.string.r_no_photo_picker)
            render()
        }
    }

    @Deprecated("Activity results for external camera and image picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAMERA && requestCode != PHOTO) return
        val photo = if (requestCode == CAMERA) cameraFile.also { cameraFile = null } else null
        val uri = if (requestCode == PHOTO) data?.data else photo?.let(Uri::fromFile)
        if (resultCode != RESULT_OK || uri == null) {
            photo?.delete()
            return
        }
        openingQR = true
        render()
        thread(name = "ezcall-qr") {
            val credential = runCatching { decode(uri) }.getOrNull()
            photo?.delete()
            runOnUiThread {
                if (isDestroyed) {
                    credential?.fill(0)
                    return@runOnUiThread
                }
                openingQR = false
                if (credential == null) {
                    statusOverride = getString(R.string.r_no_code)
                } else {
                    secret?.fill(0)
                    secret = credential
                    if (ReceiverService.hasNearby(this)) beginPairing() else requestNearby()
                }
                render()
            }
        }
    }

    private fun decode(uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1_200))
        }
        val image = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        try {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val source = RGBLuminanceSource(image.width, image.height, pixels)
            val code = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
            return PairKey.parseQr(code.text)
        } finally {
            image.recycle()
        }
    }

    private fun requestNearby() {
        requestPermissions(arrayOf(permission.BLUETOOTH_SCAN, permission.BLUETOOTH_CONNECT), NEARBY)
    }

    private fun beginPairing() {
        val pending = secret
        secret = null
        statusOverride = null
        runCatching { ReceiverService.start(this, pending) }.onFailure {
            pending?.fill(0)
            statusOverride = getString(R.string.r_start_failed)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            NEARBY -> if (ReceiverService.hasNearby(this)) beginPairing() else {
                secret?.fill(0)
                secret = null
                statusOverride = getString(R.string.r_nearby_denied)
                render()
            }
            MIC -> {
                val action = micAction
                micAction = null
                action?.invoke(results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            }
        }
    }


    private fun label(value: String, appearance: Int, tone: Int = MaterialR.attr.colorOnSurface) = TextView(this).apply {
        text = value
        setTextAppearance(appearance)
        setTextColor(color(tone))
    }

    private fun heading(value: String, appearance: Int, tone: Int = MaterialR.attr.colorOnSurface) =
        label(value, appearance, tone).apply { ViewCompat.setAccessibilityHeading(this, true) }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    private fun fullWidth(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun actionWidth(spacing: Boolean = false) = LinearLayout.LayoutParams(0, -2, 1f).apply {
        if (spacing) marginEnd = dp(8)
    }

    private fun card(corner: Int) = MaterialCardView(this).apply {
        radius = dp(corner).toFloat()
        cardElevation = 0f
        strokeWidth = 0
    }

    private fun rounded(fill: Int, corner: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(corner).toFloat()
    }

    private fun ripple(corner: Int) = RippleDrawable(
        ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color(MaterialR.attr.colorOnSurface), 31)),
        null, rounded(Color.WHITE, corner)
    )

    private fun badge(image: Int, surface: Int, ink: Int) = FrameLayout(this).apply {
        background = rounded(color(surface), 24)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(smallIcon(image, color(ink)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }

    private fun smallIcon(image: Int, tint: Int) = ImageView(this).apply {
        setImageResource(image)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun iconDrawable(image: Int, tone: Int) = requireNotNull(AppCompatResources.getDrawable(this, image)).mutate()
        .apply { setTint(color(tone)) }

    private fun iconButton(image: Int, text: String, click: () -> Unit) = ImageButton(this).apply {
        contentDescription = text
        tooltipText = text
        setImageDrawable(iconDrawable(image, MaterialR.attr.colorOnPrimaryContainer))
        background = ripple(24)
        setOnClickListener { click() }
    }

    private fun button(value: String, image: Int, secondary: Boolean = false, danger: Boolean = false, click: () -> Unit) =
        MaterialButton(this, null, if (secondary) MaterialR.attr.materialButtonOutlinedStyle else MaterialR.attr.materialButtonStyle).apply {
            text = value
            minimumHeight = dp(54)
            insetTop = dp(2)
            insetBottom = dp(2)
            setIconResource(image)
            iconSize = dp(20)
            iconPadding = dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            if (danger) {
                val disabled = intArrayOf(-android.R.attr.state_enabled)
                val states = arrayOf(disabled, intArrayOf())
                backgroundTintList = ColorStateList(states,
                    intArrayOf(color(MaterialR.attr.colorSurfaceContainerHigh), color(androidx.appcompat.R.attr.colorError)))
                val foreground = ColorStateList(states,
                    intArrayOf(color(MaterialR.attr.colorOnSurfaceVariant), color(MaterialR.attr.colorOnError)))
                setTextColor(foreground)
                iconTint = foreground
            }
            setOnClickListener { click() }
        }

    private fun color(attribute: Int) = MaterialColors.getColor(this, attribute, 0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun date(value: Long): String = when {
        value <= 0 -> ""
        DateUtils.isToday(value) -> android.text.format.DateFormat.getTimeFormat(this).format(Date(value))
        else -> DateUtils.formatDateTime(this, value, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    private fun initials(name: String) = name.trim().split(Regex("\\s+"), limit = 2)
        .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")

    private enum class Destination(val title: Int, val icon: Int, val emptyTitle: Int, val emptyDetail: Int) {
        CALLS(R.string.r_calls, R.drawable.ic_call, R.string.r_no_calls,
            R.string.r_no_calls_detail),
        MESSAGES(R.string.r_messages, R.drawable.ic_sms, R.string.r_no_messages,
            R.string.r_no_messages_detail),
        CONTACTS(R.string.ez_contacts, R.drawable.ic_contacts, R.string.r_no_contacts,
            R.string.r_no_contacts_detail)
    }

    private data class Row(val title: String, val subtitle: String, val time: String, val image: Int,
        val initials: String = "", val open: () -> Unit)

    private inner class Rows : BaseAdapter() {
        private var rows = emptyList<Row>()
        fun update(value: List<Row>) {
            rows = value
            notifyDataSetChanged()
        }
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, existing: View?, parent: ViewGroup): View =
            (existing as? EntryView ?: EntryView()).apply { bind(getItem(position), position < count - 1) }
    }

    private inner class EntryView : LinearLayout(this@ReceiverActivity) {
        private val accent = MaterialR.attr.colorOnPrimaryContainer
        private val symbol = smallIcon(R.drawable.ic_call, color(accent))
        private val initials = label("", R.style.TextAppearance_ezcall_LabelLarge, accent).apply {
            gravity = Gravity.CENTER
        }
        private val title = label("", R.style.TextAppearance_ezcall_TitleMedium).singleLine()
        private val detail = label("", R.style.TextAppearance_ezcall_BodyMedium,
            MaterialR.attr.colorOnSurfaceVariant).singleLine()
        private val time = label("", R.style.TextAppearance_ezcall_LabelMedium, MaterialR.attr.colorOnSurfaceVariant)
        private val separator = View(this@ReceiverActivity).apply {
            setBackgroundColor(color(MaterialR.attr.colorOutlineVariant))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = VERTICAL
            layoutParams = AbsListView.LayoutParams(-1, -2)
            isScreenReaderFocusable = true
            addView(row().apply {
                minimumHeight = dp(72)
                setPadding(dp(24), dp(10), dp(24), dp(10))
                addView(FrameLayout(this@ReceiverActivity).apply {
                    background = rounded(color(MaterialR.attr.colorPrimaryContainer), 20)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(symbol, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    addView(initials, FrameLayout.LayoutParams(-1, -1))
                }, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(column().apply { addView(title); addView(detail, fullWidth(top = 2)) },
                    LayoutParams(0, -2, 1f))
                addView(time, LayoutParams(-2, -2).apply { marginStart = dp(12) })
            })
            addView(separator, LayoutParams(-1, dp(1)).apply {
                marginStart = dp(76)
                marginEnd = dp(24)
            })
        }

        fun bind(entry: Row, divided: Boolean) {
            symbol.setImageResource(entry.image)
            symbol.visibility = visible(entry.initials.isEmpty())
            initials.text = entry.initials
            initials.visibility = visible(entry.initials.isNotEmpty())
            title.text = entry.title
            detail.text = entry.subtitle
            detail.visibility = visible(entry.subtitle.isNotEmpty())
            time.text = entry.time
            time.visibility = visible(entry.time.isNotEmpty())
            separator.visibility = if (divided) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun TextView.singleLine() = apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    companion object {
        private const val CAMERA = 101
        private const val PHOTO = 102
        private const val NEARBY = 103
        private const val NOTIFICATIONS = 104
        private const val MIC = 105
    }
}
