package app.ezcall.receiver

import android.Manifest.permission
import android.app.NotificationManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Context
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.text.buildSpannedString
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import app.ezcall.android.PairKey
import app.ezcall.android.PairingArtwork
import app.ezcall.android.Support
import app.ezcall.android.ReceiverTestStep
import app.ezcall.android.AudioTestLog
import app.ezcall.android.AudioWaveformView
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

internal fun Context.receiverCallScreen(id: String, address: String? = null, name: String? = null) =
    Intent(this, IncomingCallActivity::class.java).setIdentifier(id)
        .putExtra("address", address).putExtra("name", name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

internal fun AppCompatActivity.presentRingingCall(beforeOpen: () -> Unit = {}) {
    val state = ReceiverState
    val call = state.inCall ?: return
    if (call.state != "ringing" || !state.paired || state.pendingCall || state.presentedCall == call.id ||
        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    state.presentedCall = call.id
    beforeOpen()
    startActivity(receiverCallScreen(call.id))
}

internal fun Context.receiverNumberDisplay(number: String) =
    BidiFormatter.getInstance(resources.configuration.locales[0]).unicodeWrap(number, TextDirectionHeuristicsCompat.LTR)

internal fun receiverConnectionAction(nearbyGranted: Boolean, bluetoothEnabled: Boolean, connected: Boolean, running: Boolean) = when {
    !nearbyGranted -> R.string.r_allow_nearby
    !bluetoothEnabled -> R.string.r_enable_bluetooth
    !connected && !running -> R.string.r_reconnect_now
    else -> 0
}

open class ReceiverActivity : AppCompatActivity() {
    private val state get() = ReceiverState
    private lateinit var support: Support
    private lateinit var pairKey: PairKey
    private lateinit var dashboard: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var settingsScreen: ScrollView
    private lateinit var settingsContent: LinearLayout
    private lateinit var alertsContent: LinearLayout
    private var alertsOpen = false
    private lateinit var settingsBack: OnBackPressedCallback
    private lateinit var settingsLinkTitle: TextView
    private lateinit var settingsLinkDetail: TextView
    private lateinit var settingsConnectionCard: MaterialCardView
    private lateinit var settingsRecovery: SettingRow
    private lateinit var pairedSettings: LinearLayout
    private lateinit var forgetSettings: View
    private lateinit var syncSetting: SettingRow
    private lateinit var testSetting: SettingRow
    private lateinit var notificationSetting: SettingRow
    private var lockScreenSetting: SettingRow? = null
    private lateinit var microphoneSetting: SettingRow
    private lateinit var audioTestSetting: SettingRow
    private lateinit var pairingScreen: ScrollView
    private lateinit var pairingFeedback: MaterialCardView
    private lateinit var pairingStatus: TextView
    private lateinit var pairingProgress: LinearProgressIndicator
    private lateinit var scanButton: MaterialButton
    private lateinit var photoButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var pairingRecovery: MaterialButton
    private lateinit var connection: MaterialCardView
    private lateinit var linkTitle: TextView
    private lateinit var linkDetail: TextView
    private lateinit var linkProgress: LinearProgressIndicator
    private lateinit var linkAction: MaterialButton
    private lateinit var connectionSettingsButton: ImageButton
    private lateinit var nav: BottomNavigationView
    private lateinit var pairingIntro: View
    private lateinit var pairingTitle: TextView
    private lateinit var callPanel: MaterialCardView
    private lateinit var sectionTitle: TextView
    private lateinit var createAction: ExtendedFloatingActionButton
    private lateinit var list: ListView
    private lateinit var emptyPanel: LinearLayout
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDetail: TextView
    private lateinit var featureStatus: TextView
    private lateinit var adapter: Rows
    private var secret: ByteArray? = null
    private var cameraFile: File? = null
    private var micAction: ((Boolean) -> Unit)? = null
    private var dialDialog: BottomSheetDialog? = null
    private var activeTab = Destination.CALLS
    private val scrollPositions = IntArray(Destination.entries.size)
    private val scrollOffsets = IntArray(Destination.entries.size)
    private var statusOverride: String? = null
    private var nearbyDenied = false
    private var notificationsDenied = false
    private var microphoneDenied = false
    private var awaitingPair = false
    private var openingQR = false
    private var listData: ReceiverData? = null
    private var listAttempts: List<SmsAttempt>? = null
    private var renderedTab: Destination? = null
    private var callPanelContents: List<Any?>? = null
    private val floatDashboardAction get() = resources.configuration.fontScale < 1.3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (this is IncomingCallActivity) {
            callPanel = card(0)
            setContentView(ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.receiver_call_surface))
                isFillViewport = true
                addView(callPanel)
                applyScreenInsets(this)
            })
            render()
            return
        }
        support = Support(this)
        pairKey = PairKey(this)
        activeTab = Destination.entries.getOrElse(savedInstanceState?.getInt("tab") ?: 0) { Destination.CALLS }
        cameraFile = savedInstanceState?.getString("camera")?.let(::File)
        awaitingPair = savedInstanceState?.getBoolean("awaitingPair") == true
        nearbyDenied = savedInstanceState?.getBoolean("nearbyDenied") == true
        notificationsDenied = savedInstanceState?.getBoolean("notificationsDenied") == true
        microphoneDenied = savedInstanceState?.getBoolean("microphoneDenied") == true
        if (ReceiverService.current == null) {
            state.paired = pairKey.exists()
            state.data = if (state.paired) ReceiverVault(this).load() else ReceiverData()
        }

        val root = column().apply { setBackgroundColor(color(android.R.attr.colorBackground)) }
        applyScreenInsets(root)
        toolbar = buildToolbar()
        root.addView(toolbar)

        val content = FrameLayout(this)
        pairingScreen = buildPairingScreen()
        dashboard = buildDashboard(savedInstanceState)
        alertsOpen = savedInstanceState?.getBoolean("alerts") == true
        settingsScreen = buildSettings()
        settingsBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (alertsOpen) {
                    alertsOpen = false
                    settingsScreen.scrollTo(0, 0)
                    render()
                } else showSettings(false)
            }
        }
        onBackPressedDispatcher.addCallback(this, settingsBack)
        settingsBack.isEnabled = savedInstanceState?.getBoolean("settings") == true
        content.addView(pairingScreen, FrameLayout.LayoutParams(-1, -1))
        content.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        content.addView(settingsScreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun applyScreenInsets(root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = dp(((resources.configuration.screenWidthDp - 600) / 2).coerceAtLeast(0))
            view.setPadding(bars.left + side, bars.top, bars.right + side, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun buildToolbar() = MaterialToolbar(this).apply {
        minimumHeight = dp(64)
        setContentInsetsRelative(dp(24), dp(16))
        setPaddingRelative(0, 0, dp(10), 0)
        val brand = row().apply {
            addView(FrameLayout(this@ReceiverActivity).apply {
                background = rounded(getColor(R.color.launcher_background), 15)
                addView(smallIcon(R.drawable.ezcall_icon_mark, Color.WHITE),
                    FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            addView(heading(getString(R.string.ez_brand), R.style.TextAppearance_ezcall_TitleLarge))
            addView(label(getString(R.string.r_receiver), R.style.TextAppearance_ezcall_LabelMedium,
                MaterialR.attr.colorOnPrimaryContainer).apply {
                background = rounded(color(MaterialR.attr.colorPrimaryContainer), 20)
                setPaddingRelative(dp(14), dp(4), dp(14), dp(4))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        addView(brand, Toolbar.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        menu.add(0, SETTINGS, 0, R.string.ez_settings).apply {
            icon = iconDrawable(R.drawable.ic_settings, MaterialR.attr.colorOnSurface)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener { showSettings(true); true }
        }
    }

    private fun showSettings(show: Boolean) {
        alertsOpen = false
        if (show) {
            settingsScreen.scrollTo(0, 0)
        }
        settingsBack.isEnabled = show
        render()
    }

    private fun buildPairingScreen() = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(column().apply {
            setPaddingRelative(dp(24), dp(12), dp(24), dp(20))

            pairingTitle = heading(getString(R.string.ez_pair_phones), R.style.TextAppearance_ezcall_HeadlineLarge)
            addView(pairingTitle)
            pairingIntro = column().apply {
                addView(label(getString(R.string.r_pair_intro), R.style.TextAppearance_ezcall_BodyMedium,
                    MaterialR.attr.colorOnSurfaceVariant), fullWidth(top = 6))
                val hero = card(28).apply {
                    setCardBackgroundColor(getColor(R.color.ezcall_pairing_hero))
                    addView(PairingArtwork(this@ReceiverActivity).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                addView(hero, LinearLayout.LayoutParams(-1, dp(136)).apply { topMargin = dp(12) })
                addView(settingsGroup {
                    addView(SettingRow(R.drawable.ic_qr, getString(R.string.r_setup_sender), click = null).apply {
                        updateDetail(getString(R.string.r_setup_sender_detail))
                    })
                }, fullWidth(top = 12))
            }
            addView(pairingIntro, fullWidth())

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

            scanButton = button(getString(R.string.r_scan_qr), 0, click = ::camera)
            photoButton = button(getString(R.string.r_choose_qr), 0, secondary = true, click = ::choosePhoto)
            cancelButton = button(getString(R.string.ez_cancel_pairing), R.drawable.ic_close, secondary = true) { ReceiverService.current?.forget() }
            pairingRecovery = button("", R.drawable.ic_bluetooth, secondary = true, click = ::recoverConnection)
            addView(scanButton, fullWidth(top = 12))
            addView(photoButton, fullWidth(top = 8))
            addView(pairingRecovery, fullWidth(top = 8))
            addView(cancelButton, fullWidth(top = 8))

        })
    }

    private fun buildDashboard(saved: Bundle?) = column().apply {
        val content = FrameLayout(this@ReceiverActivity)
        val header = column().apply { setPaddingRelative(dp(24), dp(8), dp(24), 0) }
        sectionTitle = heading("", R.style.TextAppearance_ezcall_HeadlineLarge)
        header.addView(sectionTitle, fullWidth(bottom = 12))
        callPanel = card(24)
        header.addView(callPanel, fullWidth(bottom = 12))

        connection = card(20)
        val linkContent = column()
        val linkRow = row().apply { setPaddingRelative(dp(12), 0, dp(8), 0) }
        val linkBadge = FrameLayout(this@ReceiverActivity).apply {
            addView(smallIcon(R.drawable.ic_bluetooth, color(MaterialR.attr.colorOnPrimaryContainer)),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        linkRow.addView(linkBadge, LinearLayout.LayoutParams(dp(28), dp(44)).apply { marginEnd = dp(8) })
        linkTitle = heading("", R.style.TextAppearance_ezcall_LabelLarge)
        linkDetail = label("", R.style.TextAppearance_ezcall_BodyMedium).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        linkRow.addView(column().apply { addView(linkTitle); addView(linkDetail, fullWidth(top = 2)) },
            LinearLayout.LayoutParams(0, -2, 1f))
        connectionSettingsButton = iconButton(R.drawable.receiver_forward, getString(R.string.r_settings_connection)) { showSettings(true) }
        linkRow.addView(connectionSettingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        linkContent.addView(linkRow, fullWidth())
        linkProgress = LinearProgressIndicator(this@ReceiverActivity).apply {
            isIndeterminate = true
            trackCornerRadius = dp(2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        linkContent.addView(linkProgress, fullWidth(bottom = 12).apply { marginStart = dp(16); marginEnd = dp(16) })
        linkAction = button("", R.drawable.ic_bluetooth, secondary = true, click = ::recoverConnection)
        linkContent.addView(linkAction, fullWidth(bottom = 12).apply { marginStart = dp(16); marginEnd = dp(16) })
        connection.addView(linkContent)
        header.addView(connection, fullWidth(bottom = 12))

        featureStatus = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(featureStatus, fullWidth(bottom = 8))
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
            backgroundTintList = ColorStateList.valueOf(color(MaterialR.attr.colorPrimaryContainer))
            setTextColor(color(MaterialR.attr.colorOnPrimaryContainer))
            iconTint = ColorStateList.valueOf(color(MaterialR.attr.colorOnPrimaryContainer))
            elevation = 0f
            maxLines = 2
            minimumHeight = dp(56)
            setOnClickListener { newAction() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
        }
        if (floatDashboardAction) {
            content.addView(createAction, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(20)
                bottomMargin = dp(16)
            })
        }
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!floatDashboardAction) {
            addView(createAction, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END
                marginEnd = dp(20)
                marginStart = dp(20)
                topMargin = dp(4)
                bottomMargin = dp(8)
            })
        }

        nav = BottomNavigationView(this@ReceiverActivity).apply {
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setLabelMaxLines(2)
            setItemActiveIndicatorColor(ColorStateList.valueOf(color(MaterialR.attr.colorPrimaryContainer)))
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
        emptyIcon = smallIcon(activeTab.icon, color(MaterialR.attr.colorOnSurfaceVariant)).apply {
            background = rounded(color(MaterialR.attr.colorSurfaceContainerHigh), 20)
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
        if (secret != null && ReceiverService.hasNearby(this)) {
            beginPairing()
        } else if (pairKey.exists() && ReceiverService.current == null && ReceiverService.hasNearby(this)) {
            startReceiver()
        }
        ReceiverService.current?.refreshLocale()
        render()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (this !is IncomingCallActivity) renderCall()
    }

    override fun onStop() {
        state.listeners.remove(::render)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        ReceiverService.current?.retryDeferredMicrophone()
        if (this is IncomingCallActivity) {
            render()
            return
        }
        if (activeTab == Destination.CALLS && !settingsBack.isEnabled &&
            state.paired && state.inCall == null && !state.pendingCall) {
            support.remind(R.string.ez_support_prompt, R.string.ez_support_action, android.R.string.cancel, ::openSupport)
        }
    }

    override fun onPause() {
        if (this !is IncomingCallActivity) support.dismissReminder()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this is IncomingCallActivity) return
        if (list.isLaidOut) storeScrollPosition()
        outState.putInt("tab", activeTab.ordinal)
        outState.putIntArray("scrollPositions", scrollPositions)
        outState.putIntArray("scrollOffsets", scrollOffsets)
        outState.putString("camera", cameraFile?.path)
        outState.putBoolean("settings", settingsBack.isEnabled)
        outState.putBoolean("alerts", alertsOpen)
        outState.putBoolean("awaitingPair", awaitingPair)
        outState.putBoolean("nearbyDenied", nearbyDenied)
        outState.putBoolean("notificationsDenied", notificationsDenied)
        outState.putBoolean("microphoneDenied", microphoneDenied)
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

    private fun updateListPadding() {
        val bottom = if (floatDashboardAction && createAction.isVisible) maxOf(createAction.height, dp(56)) + dp(36) else dp(16)
        if (list.paddingBottom != bottom) list.setPadding(0, 0, 0, bottom)
    }

    private fun render() {
        if (this is IncomingCallActivity) {
            val id = intent.identifier
            if (state.paired && id != null && (state.inCall?.id == id || ReceiverService.current?.isDialing(id) == true)) renderCall()
            else finishAndRemoveTask()
            return
        }
        val paired = state.paired || pairKey.exists()
        val settingsOpen = settingsBack.isEnabled
        if (state.inCall != null || state.pendingCall) support.dismissReminder()
        settingsScreen.visibility = visible(settingsOpen)
        pairingScreen.visibility = visible(!settingsOpen && !paired)
        dashboard.visibility = visible(!settingsOpen && paired)
        toolbar.menu.setGroupVisible(0, !settingsOpen)
        if (settingsOpen && toolbar.navigationIcon == null) {
            toolbar.setNavigationIcon(R.drawable.receiver_back)
            toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            toolbar.setNavigationOnClickListener { settingsBack.handleOnBackPressed() }
        } else if (!settingsOpen) {
            toolbar.navigationIcon = null
        }
        if (awaitingPair && paired && state.connected) {
            awaitingPair = false
            if (activeTab != Destination.CALLS) nav.selectedItemId = Destination.CALLS.ordinal + 1
            if (settingsOpen) showSettings(false)
        }
        if (settingsOpen) renderSettings(paired)
        if (!paired) {
            renderPairing()
            return
        }
        renderConnection()
        renderCall()

        val action = when (activeTab) {
            Destination.MESSAGES -> R.string.r_new_sms
            Destination.CALLS -> R.string.r_new_call
            Destination.CONTACTS -> 0
        }
        val reason = when {
            action == 0 || !state.connected -> 0
            activeTab == Destination.CALLS && state.inCall != null -> R.string.r_finish_other_call
            activeTab == Destination.CALLS && state.pendingCall -> R.string.r_dialing
            activeTab == Destination.CALLS && !state.canDial -> R.string.r_call_control_needed
            activeTab == Destination.MESSAGES && state.pendingSms -> R.string.r_sending
            activeTab == Destination.MESSAGES && !state.canSendSms -> R.string.r_sms_needs_permission
            else -> 0
        }
        createAction.visibility = visible(action != 0 && state.connected && (activeTab != Destination.CALLS || state.inCall == null))
        createAction.isEnabled = action != 0
        if (action != 0 && createAction.text != getString(action)) {
            createAction.setText(action)
            createAction.setIconResource(if (activeTab == Destination.MESSAGES) R.drawable.receiver_plus else activeTab.icon)
        }
        updateListPadding()
        featureStatus.isVisible = createAction.isVisible && reason != 0
        if (featureStatus.isVisible) featureStatus.updateText(getString(reason))
        emptyDetail.updateText(getString(if (state.syncing && activeTab != Destination.CALLS) R.string.r_sync_progress
            else activeTab.emptyDetail))

        if (listData === state.data && listAttempts === state.attempts && renderedTab == activeTab) return
        listData = state.data
        listAttempts = state.attempts
        renderedTab = activeTab
        renderRows()
    }

    private fun renderPairing() {
        val enrolling = awaitingPair && (ReceiverService.current != null || state.detail.isBlank())
        if (awaitingPair && !enrolling) awaitingPair = false
        val permissionNeeded = nearbyDenied && !ReceiverService.hasNearby(this)
        val feedback = statusOverride ?: when {
            openingQR -> getString(R.string.r_reading_code)
            enrolling -> receiverNotice(state.detail).ifBlank { getString(R.string.r_pairing) }
            else -> state.detail.takeIf { it.isNotBlank() }?.let(::receiverNotice)
        }
        val busy = openingQR || enrolling && statusOverride == null
        pairingTitle.setText(when {
            enrolling -> R.string.r_pairing
            permissionNeeded -> R.string.r_need_nearby
            else -> R.string.ez_pair_phones
        })
        pairingIntro.isVisible = !enrolling && feedback == null
        val neutral = busy || feedback == getString(R.string.r_pair_removed)
        pairingFeedback.visibility = visible(feedback != null)
        pairingFeedback.setCardBackgroundColor(color(if (neutral) MaterialR.attr.colorSurfaceContainer else MaterialR.attr.colorErrorContainer))
        pairingStatus.setTextColor(color(if (neutral) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnErrorContainer))
        pairingStatus.updateText(feedback.orEmpty())
        pairingProgress.visibility = visible(busy)
        scanButton.visibility = visible(!enrolling)
        photoButton.visibility = visible(!enrolling)
        scanButton.isEnabled = !openingQR
        photoButton.isEnabled = !openingQR
        cancelButton.visibility = visible(enrolling)
        cancelButton.isEnabled = ReceiverService.current != null
        val recovery = when {
            permissionNeeded -> R.string.r_allow_nearby
            enrolling && connectionAction() == R.string.r_enable_bluetooth -> R.string.r_enable_bluetooth
            else -> 0
        }
        pairingRecovery.visibility = visible(recovery != 0)
        if (recovery != 0) pairingRecovery.updateText(getString(recovery))
    }

    private fun renderConnection() {
        val recovery = connectionAction()
        val connected = state.connected && recovery == 0
        val title = when {
            recovery == R.string.r_allow_nearby -> getString(R.string.r_need_nearby)
            state.syncing && connected -> getString(R.string.r_sync_active)
            connected -> getString(R.string.r_sender_connected)
            else -> getString(R.string.r_sender_disconnected)
        }
        val detail = if (!connected || state.syncing) connectionDetail(recovery, connected) else ""
        connection.isVisible = activeTab == Destination.CALLS || !connected || state.syncing
        connection.setCardBackgroundColor(color(if (connected) MaterialR.attr.colorPrimaryContainer
            else MaterialR.attr.colorSurfaceContainer))
        linkTitle.setTextColor(color(if (connected) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface))
        linkDetail.setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
        linkTitle.updateText(title)
        linkDetail.updateText(detail)
        linkDetail.visibility = visible(detail.isNotEmpty())
        connectionSettingsButton.visibility = visible(connected)
        linkProgress.visibility = visible(state.syncing && connected || !connected && recovery == 0 && ReceiverService.current != null)
        linkAction.visibility = visible(recovery != 0)
        if (recovery != 0) linkAction.updateText(getString(recovery))
    }

    private fun connectionDetail(recovery: Int, connected: Boolean) = when {
        recovery == R.string.r_allow_nearby -> getString(if (nearbyDenied) R.string.r_nearby_denied else R.string.r_connect_sender)
        recovery == R.string.r_enable_bluetooth -> getString(R.string.r_bt_off)
        recovery == R.string.r_reconnect_now -> statusOverride ?: state.detail.takeIf { it.isNotBlank() }?.let(::receiverNotice)
            ?: getString(R.string.r_reconnect_detail)
        state.syncing && connected -> getString(R.string.r_link_while_syncing)
        connected && (state.detail.isBlank() || state.detail == "Connected over Bluetooth.") -> getString(R.string.r_active_bt)
        state.detail.isNotBlank() -> receiverNotice(state.detail)
        else -> getString(R.string.r_searching)
    }

    private fun renderCall() {
        val outgoing = this is IncomingCallActivity && state.inCall == null
        val call = state.inCall ?: if (outgoing) intent.identifier?.let {
            ReceivedCall(it, intent.getStringExtra("address").orEmpty(), 0, "dialing", false)
        } else null
        val name = call?.let {
            val dialName = if (this is IncomingCallActivity) intent.getStringExtra("name") else null
            dialName ?: state.data.name(it.address, getString(R.string.r_unknown))
        }
        val test = state.audioTest?.takeIf { call?.id == it.id }?.log
        val checklist = test?.let(::testChecklist)
        val callDetail = if (call != null && (state.pendingCall || !state.connected || call.state != "ringing")) {
            receiverNotice(state.detail)
        } else ""
        val locked = this is IncomingCallActivity && getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val callContents = listOf(call, name, state.pendingCall, state.canControlCurrentCall, callDetail,
            state.microphoneOffered, state.microphoneAvailable, state.microphoneLive, checklist, locked)
        if (this !is IncomingCallActivity) presentRingingCall { dialDialog?.dismiss() }
        if (callPanelContents == callContents) return
        callPanelContents = callContents
        callPanel.removeAllViews()
        callPanel.visibility = visible(call != null)
        if (call == null) return

        val ringing = call.state == "ringing"
        if (this is IncomingCallActivity) {
            renderCallScreen(call, name.orEmpty(), callDetail, test, locked)
            return
        }
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
                    addView(heading(if (name == call.address) receiverNumberDisplay(call.address) else name.orEmpty(),
                        R.style.TextAppearance_ezcall_TitleLarge, ink))
                    if (call.address.isNotBlank() && name != call.address) {
                        addView(label(receiverNumberDisplay(call.address), R.style.TextAppearance_ezcall_BodyMedium, ink))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.receiver_forward, getString(R.string.r_open_call_alert)) {
                    startActivity(receiverCallScreen(call.id))
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            if (callDetail.isNotBlank()) addView(label(callDetail, R.style.TextAppearance_ezcall_BodyMedium, ink).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, fullWidth(top = 8))
            if (state.canControlCurrentCall) addView(column().apply {
                if (ringing) addView(button(getString(R.string.r_answer), R.drawable.ic_call) {
                    microphone { ReceiverService.current?.answer(call.id, it) }
                }.apply { isEnabled = !state.pendingCall }, fullWidth())
                if (!ringing && state.microphoneOffered) {
                    val action = when {
                        state.microphoneLive -> R.string.r_mute
                        state.microphoneAvailable -> R.string.r_unmute
                        else -> R.string.r_enable_mic
                    }
                    addView(button(getString(action), R.drawable.receiver_mic, secondary = true) {
                        if (state.microphoneAvailable) ReceiverService.current?.mute()
                        else microphone { ReceiverService.current?.enableMicrophone(call.id, it) }
                    }.apply { isEnabled = !state.pendingCall }, fullWidth(bottom = 4))
                }
                addView(button(if (ringing) getString(R.string.r_decline) else getString(R.string.r_end_call), R.drawable.receiver_end, danger = true) {
                    ReceiverService.current?.end(call.id)
                }.apply { isEnabled = !state.pendingCall }, fullWidth(top = if (ringing) 4 else 0))
            }, fullWidth(top = 12))
            if (call.simulated && test != null) {
                addAudioTest(test, ringing, ink)
            }
        })
    }

    private fun renderCallScreen(call: ReceivedCall, name: String, detail: String, test: AudioTestLog?, locked: Boolean) {
        val ringing = call.state == "ringing"
        val dialing = call.state == "dialing"
        val ink = MaterialR.attr.colorOnPrimaryContainer
        callPanel.setCardBackgroundColor(getColor(R.color.receiver_call_surface))
        callPanel.addView(column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(24), dp(32), dp(24), dp(32))
            addView(label(getString(R.string.r_call_brand), R.style.TextAppearance_ezcall_LabelLarge, ink).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconDrawable(R.drawable.ic_bluetooth, ink), null, null, null)
                compoundDrawablePadding = dp(8)
            })
            val status = when {
                dialing -> R.string.r_calling
                ringing -> R.string.r_call_incoming
                else -> R.string.r_call_active
            }
            addView(label(getString(status), R.style.TextAppearance_ezcall_TitleMedium, ink),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
            val known = name != call.address && call.address.isNotBlank()
            val avatar = FrameLayout(this@ReceiverActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                for (size in if (dialing) emptyList() else listOf(224, 174)) {
                    addView(View(this@ReceiverActivity).apply {
                        background = rounded(Color.TRANSPARENT, 112).apply {
                            setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(color(ink), 36))
                        }
                    }, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
                }
                addView(label(if (known && ringing) initials(name).take(1) else "", R.style.TextAppearance_ezcall_DisplaySmall, ink).apply {
                    gravity = Gravity.CENTER
                    background = rounded(color(MaterialR.attr.colorPrimaryContainer), 56)
                    if (!known || !ringing) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(null, iconDrawable(R.drawable.ic_call, ink), null, null)
                        setPadding(0, dp(40), 0, 0)
                    }
                }, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
            }
            addView(avatar, LinearLayout.LayoutParams(dp(224), dp(224)).apply { topMargin = dp(16) })
            addView(heading(if (known) name else name.takeUnless { it == call.address } ?: receiverNumberDisplay(call.address),
                R.style.TextAppearance_ezcall_HeadlineLarge, ink).apply { gravity = Gravity.CENTER }, fullWidth())
            if (known) addView(label(receiverNumberDisplay(call.address), R.style.TextAppearance_ezcall_BodyLarge, ink),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            if (ringing && locked) {
                addView(label(getString(R.string.r_call_from_sender), R.style.TextAppearance_ezcall_BodyMedium, ink),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            }
            if (detail.isNotBlank()) addView(label(detail, R.style.TextAppearance_ezcall_BodyMedium, ink).apply {
                gravity = Gravity.CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, fullWidth(top = 12))
            if (call.simulated && test != null) addAudioTest(test, ringing, ink)
            addView(View(this@ReceiverActivity), LinearLayout.LayoutParams(1, dp(32), 1f))
            if (dialing) {
                addView(button(getString(R.string.r_end_call), R.drawable.receiver_end, danger = true) {
                    ReceiverService.current?.end(call.id)
                }.apply { isEnabled = state.detail != "Ending the call…" }, fullWidth())
            } else if (state.canControlCurrentCall) {
                val enabled = !state.pendingCall
                addView(row().apply {
                    if (ringing) {
                        addView(callCircle(R.string.r_decline, R.drawable.receiver_end, danger = true, enabled = enabled) {
                            ReceiverService.current?.end(call.id)
                        }, actionWidth())
                        addView(callCircle(R.string.r_answer, R.drawable.ic_call, danger = false, enabled = enabled) {
                            microphone { ReceiverService.current?.answer(call.id, it) }
                        }, actionWidth())
                    } else {
                        if (state.microphoneOffered) {
                            val micTitle = when {
                                state.microphoneLive -> R.string.r_mute
                                state.microphoneAvailable -> R.string.r_unmute
                                else -> R.string.r_enable_mic
                            }
                            addView(callCircle(micTitle, R.drawable.receiver_mic, danger = false, enabled = enabled) {
                                if (state.microphoneAvailable) ReceiverService.current?.mute()
                                else microphone { ReceiverService.current?.enableMicrophone(call.id, it) }
                            }, actionWidth())
                        }
                        addView(callCircle(R.string.r_end_call, R.drawable.receiver_end, danger = true, enabled = enabled) {
                            ReceiverService.current?.end(call.id)
                        }, actionWidth())
                    }
                }, fullWidth())
            }
        })
    }

    private fun LinearLayout.addAudioTest(test: AudioTestLog, ringing: Boolean, ink: Int) {
        if (!ringing) {
            addView(label(getString(R.string.ez_test_speak_after_tones), R.style.TextAppearance_ezcall_BodyMedium, ink),
                fullWidth(top = 12))
            addView(label(getString(R.string.ez_test_own_wave), R.style.TextAppearance_ezcall_LabelMedium, ink), fullWidth(top = 8))
            addView(AudioWaveformView(this@ReceiverActivity, test.localWaveform, ink, live = true), LinearLayout.LayoutParams(-1, dp(38)))
            addView(label(getString(R.string.ez_test_incoming_wave), R.style.TextAppearance_ezcall_LabelMedium, ink), fullWidth(top = 4))
            addView(AudioWaveformView(this@ReceiverActivity, test.remoteWaveform, ink, live = true), LinearLayout.LayoutParams(-1, dp(38)))
            addView(label(if (testPassed(test)) getString(R.string.ez_test_auto_passed) else testChecklist(test),
                R.style.TextAppearance_ezcall_BodyMedium, ink).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, fullWidth(top = 4))
        }
        addView(button(getString(R.string.ez_test_log), R.drawable.receiver_check, secondary = true, click = ::showTestLog),
            fullWidth(top = 8))
    }

    private fun callCircle(title: Int, icon: Int, danger: Boolean, enabled: Boolean, action: () -> Unit) = column().apply {
        gravity = Gravity.CENTER
        val fill = if (danger) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary
        val ink = if (danger) MaterialR.attr.colorOnError else MaterialR.attr.colorOnPrimary
        addView(iconButton(icon, getString(title), action).apply {
            setImageDrawable(iconDrawable(icon, ink))
            background = RippleDrawable(ColorStateList.valueOf(color(ink)), rounded(color(fill), 32), null)
            isEnabled = enabled
            alpha = if (enabled) 1f else .5f
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(label(getString(title), R.style.TextAppearance_ezcall_LabelLarge, MaterialR.attr.colorOnPrimaryContainer).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, fullWidth(top = 8))
    }
    private fun showTestLog() {
        state.audioTest?.log?.show(this, R.string.ez_test_log, R.string.ez_test_copy)
    }

    private fun testPassed(test: AudioTestLog) = ReceiverTestStep.CUE_PLAYED in test &&
        ReceiverTestStep.MIC_SIGNAL in test && ReceiverTestStep.ECHO_PLAYED in test

    private fun testChecklist(test: AudioTestLog) = listOf(
        if (ReceiverTestStep.CUE_PLAYED in test) R.string.ez_test_tone_played else R.string.ez_test_wait_tone,
        if (ReceiverTestStep.MIC_SIGNAL in test) R.string.ez_test_mic_detected else R.string.ez_test_wait_mic,
        if (ReceiverTestStep.ECHO_PLAYED in test) R.string.ez_test_echo_played else R.string.ez_test_wait_echo
    ).joinToString("\n") { getString(it) }

    private fun renderRows() {
        sectionTitle.text = getString(activeTab.title)
        val rows = when (activeTab) {
            Destination.MESSAGES -> state.visibleMessages().distinctBy { conversationKey(it.address, state.data.country) }.map { sms ->
                val title = state.data.name(sms.address, getString(R.string.r_unknown))
                val preview = sms.body.trim().replace('\n', ' ')
                Row(if (title == sms.address) receiverNumberDisplay(title) else title,
                    if (sms.direction == "out") getString(R.string.r_you_message, preview) else preview,
                    date(sms.date), R.drawable.ic_sms) { compose(sms.address, title.takeUnless { it == sms.address }) }
            }
            Destination.CONTACTS -> state.data.contacts.map { contact ->
                val title = contact.name.ifBlank { receiverNumberDisplay(contact.numbers.firstOrNull().orEmpty()) }
                Row(title, contact.numbers.joinToString(" · ", transform = ::receiverNumberDisplay), "", R.drawable.ic_contacts) {
                    contactActions(contact)
                }
            }
            Destination.CALLS -> state.data.calls.map { call ->
                val title = state.data.name(call.address, getString(R.string.r_unknown))
                val detail = call.address.takeUnless { it == title || it.isBlank() }?.let(::receiverNumberDisplay).orEmpty()
                val kind = when (call.kind) {
                    CallKind.OUTGOING -> getString(R.string.r_history_outgoing)
                    CallKind.INCOMING -> getString(R.string.r_history_incoming)
                    CallKind.MISSED -> getString(R.string.r_history_missed)
                    null -> ""
                }
                Row(if (title == call.address) receiverNumberDisplay(title) else title,
                    listOf(kind.ifBlank { detail }, date(call.date)).filter(String::isNotBlank).joinToString(" · "), "", R.drawable.ic_call,
                    day = call.date.takeIf { it > 0 }?.let(::day)) {
                    if (call.address.isNotBlank()) numberActions(call.address, title.takeUnless { it == call.address })
                }
            }
        }
        emptyTitle.text = getString(activeTab.emptyTitle)
        emptyIcon.setImageResource(activeTab.icon)
        emptyPanel.visibility = visible(rows.isEmpty())
        adapter.update(rows)
    }


    private fun buildSettings() = ScrollView(this).apply {
        ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.ez_settings))
        settingsContent = column().apply {
            setPaddingRelative(dp(24), dp(8), dp(24), dp(24))
            addView(heading(getString(R.string.ez_settings), R.style.TextAppearance_ezcall_HeadlineLarge), fullWidth(bottom = 12))
            pairedSettings = column().apply {
                settingsConnectionCard = card(16).apply {
                    setCardBackgroundColor(color(MaterialR.attr.colorPrimaryContainer))
                    addView(column().apply {
                    addView(row().apply {
                        minimumHeight = dp(44)
                        setPaddingRelative(dp(12), dp(4), dp(12), dp(4))
                        if (!settingsLargeText) {
                            addView(smallIcon(R.drawable.ic_bluetooth, color(MaterialR.attr.colorOnPrimaryContainer)),
                                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                        }
                        settingsLinkTitle = label("", R.style.TextAppearance_ezcall_LabelLarge, MaterialR.attr.colorOnPrimaryContainer)
                        settingsLinkDetail = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnPrimaryContainer)
                        addView(column().apply {
                            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                            isScreenReaderFocusable = true
                            addView(settingsLinkTitle)
                            addView(settingsLinkDetail, fullWidth(top = 2))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    })
                    settingsRecovery = SettingRow(R.drawable.ic_bluetooth, "", click = ::recoverConnection)
                    addView(settingsRecovery)
                    })
                }
                addView(settingsConnectionCard, fullWidth(bottom = 14))
                addView(settingsHeading(R.string.r_settings_connection), fullWidth())
                addView(settingsGroup {
                    syncSetting = SettingRow(R.drawable.receiver_sync, getString(R.string.r_sync), opensPage = true) {
                        ReceiverService.current?.sync()
                    }.apply { updateDetail(getString(R.string.r_sync_detail)) }
                    addView(syncSetting)
                    testSetting = SettingRow(R.drawable.receiver_check, getString(R.string.r_test), opensPage = true) {
                        ReceiverService.current?.test()
                    }.apply { updateDetail(getString(R.string.r_check_link)) }
                    addView(testSetting)
                }, fullWidth(top = 6))

                addView(settingsHeading(R.string.r_calling_settings), fullWidth(top = 18))
                addView(settingsGroup {
                    addView(SettingRow(R.drawable.ic_notifications, getString(R.string.r_calls_and_alerts),
                        opensPage = true, click = ::showAlerts).apply { updateDetail(getString(R.string.r_calls_and_alerts_detail)) })
                    audioTestSetting = SettingRow(R.drawable.ic_call, getString(R.string.r_settings_audio_test),
                        opensPage = true, click = ::showAudioTestSettings)
                    addView(audioTestSetting)
                }, fullWidth(top = 6, bottom = 18))
            }
            addView(pairedSettings)
            addView(settingsHeading(R.string.r_app_settings), fullWidth())
            addView(settingsGroup {
                addView(SettingRow(R.drawable.ic_language, getString(R.string.ez_language), opensPage = true) {
                    showEzcallLanguage(getString(R.string.ez_language))
                }.apply { updateDetail(languageName()) })
                addView(SettingRow(R.drawable.ic_support, getString(R.string.ez_support_title), opensPage = true,
                    click = ::openSupport).apply { updateDetail(getString(R.string.ez_support_optional)) })
            }, fullWidth(top = 6))
            forgetSettings = MaterialButton(androidx.appcompat.view.ContextThemeWrapper(
                this@ReceiverActivity, R.style.ThemeOverlay_ezcall_TextButton)).apply {
                setText(R.string.r_forget_sender)
                minimumHeight = dp(48)
                setOnClickListener { confirmForget() }
            }
            addView(forgetSettings, fullWidth(top = 8))
        }
        alertsContent = buildAlerts()
        addView(column().apply {
            addView(settingsContent, fullWidth())
            addView(alertsContent, fullWidth())
        })
    }

    private fun showAlerts() {
        alertsOpen = true
        settingsScreen.scrollTo(0, 0)
        render()
    }

    private fun buildAlerts() = column().apply {
        setPaddingRelative(dp(24), dp(8), dp(24), dp(24))
        addView(heading(getString(R.string.r_calls_and_alerts), R.style.TextAppearance_ezcall_HeadlineLarge),
            fullWidth(bottom = 16))
        addView(settingsHeading(R.string.r_settings_alerts), fullWidth(bottom = 6))
        addView(settingsGroup {
                    notificationSetting = SettingRow(R.drawable.ic_notifications, getString(R.string.r_manage_notifications),
                        opensPage = true, click = ::openNotifications)
                    addView(notificationSetting)
                    if (Build.VERSION.SDK_INT >= 34) {
                        lockScreenSetting = SettingRow(R.drawable.receiver_lock, getString(R.string.r_enable_call_screen), opensPage = true) {
                            val uri = Uri.fromParts("package", packageName, null)
                            openSystemSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, uri))
                        }.also(::addView)
                    }
        }, fullWidth())
        addView(settingsHeading(R.string.r_calling_settings), fullWidth(top = 18, bottom = 6))
        addView(settingsGroup {
                    addView(SettingRow(R.drawable.ic_call, getString(R.string.r_phone_account), opensPage = true, click = ::openPhoneAccounts)
                        .apply { updateDetail(getString(R.string.r_phone_account_detail)) })
                    microphoneSetting = SettingRow(R.drawable.receiver_mic, getString(R.string.r_settings_microphone), opensPage = true) {
                        if (microphoneAllowed() || microphoneDenied && !shouldShowRequestPermissionRationale(permission.RECORD_AUDIO)) {
                            openAppSettings()
                        } else requestPermissions(arrayOf(permission.RECORD_AUDIO), MIC)
                    }
                    addView(microphoneSetting)
        }, fullWidth())
    }

    private fun settingsHeading(title: Int) = heading(getString(title), R.style.TextAppearance_ezcall_TitleSmall,
        androidx.appcompat.R.attr.colorPrimary)

    private fun openSupport() = support.open(R.layout.support_wallets, ReceiverSupportActivity::class.java)

    private val settingsLargeText get() = resources.configuration.fontScale >= 1.5f

    private fun settingsGroup(content: LinearLayout.() -> Unit) = card(20).apply {
        setCardBackgroundColor(color(MaterialR.attr.colorSurfaceContainerLow))
        clipToOutline = true
        addView(column().apply(content))
    }

    private inner class SettingRow(icon: Int, text: String, opensPage: Boolean = false,
                                  click: (() -> Unit)?) : LinearLayout(this@ReceiverActivity) {
        private val title = label(text, R.style.TextAppearance_ezcall_BodyLarge)
        private val detail = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant)

        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
            if (click != null) {
                background = ripple(12)
                isFocusable = true
                setOnClickListener { click() }
            }
            isScreenReaderFocusable = true
            if (!settingsLargeText) {
                addView(badge(icon, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer),
                    LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            }
            addView(column().apply {
                addView(title)
                addView(detail, fullWidth(top = 2))
                detail.visibility = View.GONE
            }, LayoutParams(0, -2, 1f))
            if (opensPage) addView(smallIcon(R.drawable.receiver_forward, color(MaterialR.attr.colorOnSurfaceVariant)),
                LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        }

        override fun getAccessibilityClassName(): CharSequence =
            if (isClickable) android.widget.Button::class.java.name else super.getAccessibilityClassName()

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else .55f
        }

        fun updateTitle(value: String) = title.updateText(value)

        fun updateDetail(value: String) {
            detail.updateText(value)
            detail.isVisible = value.isNotBlank()
        }
    }

    private fun renderSettings(paired: Boolean) {
        if (!paired) alertsOpen = false
        settingsContent.isVisible = !alertsOpen
        alertsContent.isVisible = alertsOpen
        ViewCompat.setAccessibilityPaneTitle(settingsScreen, getString(if (alertsOpen) R.string.r_calls_and_alerts else R.string.ez_settings))
        pairedSettings.isVisible = paired
        forgetSettings.isVisible = paired
        if (!paired) return
        val recovery = connectionAction()
        val connected = state.connected && recovery == 0
        settingsConnectionCard.setCardBackgroundColor(color(if (connected) MaterialR.attr.colorPrimaryContainer
            else MaterialR.attr.colorSurfaceContainer))
        val title = when {
            recovery == R.string.r_allow_nearby -> R.string.r_need_nearby
            state.syncing && connected -> R.string.r_sync_active
            connected -> R.string.r_sender_connected
            recovery != 0 -> R.string.r_sender_disconnected
            else -> R.string.r_searching
        }
        val detail = when {
            recovery == R.string.r_enable_bluetooth -> ""
            state.syncing && connected -> receiverNotice(state.detail)
            else -> connectionDetail(recovery, connected)
        }.takeUnless {
            it == getString(title) || it == getString(R.string.r_connect_sender) ||
                it == getString(R.string.r_active_bt) || it == getString(R.string.r_sync_progress)
        }.orEmpty()
        settingsLinkTitle.updateText(getString(title))
        settingsLinkDetail.updateText(detail)
        settingsLinkDetail.isVisible = detail.isNotEmpty()
        settingsRecovery.isVisible = recovery != 0
        if (recovery != 0) settingsRecovery.updateTitle(getString(recovery))
        val idle = connected && !state.syncing && state.detail != "Testing the connection…"
        testSetting.isEnabled = idle
        syncSetting.isEnabled = idle && state.inCall?.state != "active"
        if (alertsOpen) renderAlerts()
        audioTestSetting.updateDetail(state.audioTest?.log?.let { getString(audioTestStatus(it)) }
            ?: getString(R.string.r_check_call_audio))
    }

    private fun renderAlerts() {
        val alerts = getSystemService(NotificationManager::class.java)
        notificationSetting.updateDetail(permissionStatus(alerts.areNotificationsEnabled()))
        if (Build.VERSION.SDK_INT >= 34) lockScreenSetting?.updateDetail(permissionStatus(alerts.canUseFullScreenIntent()))
        microphoneSetting.updateDetail(permissionStatus(microphoneAllowed()))
    }

    private fun permissionStatus(allowed: Boolean) = getString(if (allowed) R.string.r_permission_on else R.string.r_permission_off)

    private fun audioTestStatus(test: AudioTestLog) = when {
        testPassed(test) -> R.string.ez_test_auto_passed
        state.inCall?.simulated == true -> R.string.r_audio_test_running
        else -> R.string.ez_test_auto_incomplete
    }

    private fun showAudioTestSettings() {
        val test = state.audioTest?.log
        val message = test?.let { getString(audioTestStatus(it)) + "\n\n" + testChecklist(it) }
            ?: getString(R.string.r_settings_audio_test_detail)
        MaterialAlertDialogBuilder(this).setTitle(R.string.r_settings_audio_test).setMessage(message)
            .setPositiveButton(R.string.ez_close, null)
            .apply { if (test != null) setNeutralButton(R.string.ez_test_log) { _, _ -> showTestLog() } }
            .show()
    }

    private fun openNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!notificationsDenied || shouldShowRequestPermissionRationale(permission.POST_NOTIFICATIONS))) {
            requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), NOTIFICATIONS)
        } else {
            openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openSystemSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(intent) }.onFailure {
            MaterialAlertDialogBuilder(this).setMessage(R.string.r_settings_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun connectionAction(): Int {
        val nearby = ReceiverService.hasNearby(this)
        val bluetooth = nearby && getSystemService(BluetoothManager::class.java).adapter?.isEnabled != false
        return receiverConnectionAction(nearby, bluetooth, state.connected, ReceiverService.current != null)
    }

    private fun recoverConnection() {
        when (connectionAction()) {
            R.string.r_allow_nearby -> if (nearbyDenied && !shouldShowRequestPermissionRationale(permission.BLUETOOTH_CONNECT)) {
                openAppSettings()
            } else requestNearby()
            R.string.r_enable_bluetooth -> openSystemSettings(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            R.string.r_reconnect_now -> startReceiver()
        }
        render()
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.r_forget_sender_title)
            .setIcon(R.drawable.ic_link)
            .setMessage(R.string.r_remove_details)
            .setNegativeButton(R.string.ez_cancel, null).setPositiveButton(R.string.ez_forget) { _, _ ->
                val running = ReceiverService.current
                if (running != null) running.forget()
                else runCatching { pairKey.forget(); ReceiverVault(this).clear() }.onSuccess {
                    RelayConnectionService.unregister(this)
                    state.attempts = emptyList()
                    state.paired = false
                    state.data = ReceiverData()
                    state.detail = getString(R.string.r_pair_removed)
                }.onFailure {
                    MaterialAlertDialogBuilder(this).setMessage(R.string.r_remove_failed)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                if (!state.paired && !pairKey.exists()) {
                    statusOverride = null
                    showSettings(false)
                } else render()
            }.show().getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setTextColor(color(androidx.appcompat.R.attr.colorError))
    }

    private fun openPhoneAccounts() {
        val opened = RelayConnectionService.register(this) && runCatching {
            startActivity(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
        }.isSuccess
        if (!opened) MaterialAlertDialogBuilder(this).setMessage(R.string.r_phone_account_help)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun newAction() {
        when (activeTab) {
            Destination.CALLS -> dial()
            Destination.MESSAGES -> compose()
            Destination.CONTACTS -> Unit
        }
    }

    private fun contactActions(contact: ReceivedContact) {
        if (contact.numbers.size == 1) numberActions(contact.numbers.first(), contact.name)
        else if (contact.numbers.isNotEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(contact.name)
                .setItems(contact.numbers.map(::receiverNumberDisplay).toTypedArray()) { _, index ->
                    numberActions(contact.numbers[index], contact.name)
                }.show()
        }
    }

    private fun numberActions(number: String, name: String?) {
        val contactName = name?.takeIf(String::isNotBlank)
        val dialog = BottomSheetDialog(this)
        val content = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(24), 0, dp(24), dp(20))
            addView(BottomSheetDragHandleView(this@ReceiverActivity), fullWidth())
            addView(badge(R.drawable.ic_contacts, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer),
                LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(heading(contactName ?: receiverNumberDisplay(number), R.style.TextAppearance_ezcall_HeadlineSmall)
                .apply { gravity = Gravity.CENTER }, fullWidth(top = 12))
            if (contactName != null) addView(label(receiverNumberDisplay(number), R.style.TextAppearance_ezcall_BodyMedium,
                MaterialR.attr.colorOnSurfaceVariant).apply { gravity = Gravity.CENTER }, fullWidth(top = 4))
            if (!state.connected) addView(label(getString(R.string.r_sender_disconnected),
                R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant)
                .apply { gravity = Gravity.CENTER }, fullWidth(top = 8))
            addView(row().apply {
                addView(button(getString(R.string.r_call), R.drawable.ic_call, secondary = true) {
                    dialog.dismiss()
                    dial(number, contactName)
                }, actionWidth(true))
                addView(button(getString(R.string.r_messages), R.drawable.ic_sms, secondary = true) {
                    dialog.dismiss()
                    compose(number, contactName)
                }.apply { isEnabled = smsRecipient(number) != null }, actionWidth())
            }, fullWidth(top = 12))
        }
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun dial(initial: String = "", contact: String? = null) {
        if (dialDialog?.isShowing == true) return
        val dialog = BottomSheetDialog(this)
        dialDialog = dialog
        val pad = column().apply { setPaddingRelative(dp(24), 0, dp(24), dp(16)) }
        pad.addView(BottomSheetDragHandleView(this), fullWidth())
        val title = heading("", R.style.TextAppearance_ezcall_TitleMedium).apply {
            gravity = Gravity.CENTER
        }
        pad.addView(title, fullWidth())
        val field = com.google.android.material.textfield.TextInputLayout(this, null, MaterialR.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.r_phone_number)
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            isErrorEnabled = false
        }
        val input = com.google.android.material.textfield.TextInputEditText(field.context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
            showSoftInputOnFocus = false
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            textSize = 24f
            setText(initial)
            setSelection(length())
            filters = arrayOf(android.text.InputFilter.LengthFilter(128))
        }
        field.addView(input)
        fun edit(key: String) {
            val start = minOf(input.selectionStart, input.selectionEnd).takeIf { it >= 0 } ?: input.length()
            val end = maxOf(input.selectionStart, input.selectionEnd).takeIf { it >= 0 } ?: input.length()
            val from = if (key.isEmpty() && start == end) (start - 1).coerceAtLeast(0) else start
            input.text?.replace(from, end, key)
        }
        val erase = iconButton(R.drawable.ic_backspace, getString(R.string.r_delete_digit)) { edit("") }
        pad.addView(row().apply {
            addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            addView(erase, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, fullWidth(top = 12, bottom = 12))

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
                    if (digit == '0') {
                        contentDescription = getString(R.string.r_dial_zero)
                        setOnLongClickListener { edit("+"); true }
                    }
                }
                keyRow.addView(key, actionWidth(spacing = col < 2))
            }
            pad.addView(keyRow, fullWidth())
        }
        var request: String? = null
        var submitting = false
        var failure: String? = null
        val dialStatus = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        lateinit var updateActions: () -> Unit
        val call = button(getString(R.string.r_call), R.drawable.ic_call) {
            val number = receiverDialNumber(input.text.toString()) ?: return@button
            failure = null
            submitting = true
            updateActions()
            microphone { allowed ->
                if (dialog.isShowing) {
                    submitting = false
                    request = ReceiverService.current?.dial(number, allowed)
                    if (request == null) failure = getString(R.string.r_dial_failed)
                    else request?.let { startActivity(receiverCallScreen(it, number, contact?.takeIf { input.text.toString() == initial })) }
                    updateActions()
                }
            }
        }
        updateActions = update@{
            if (request != null && state.inCall?.id == request) {
                dialog.dismiss()
                return@update
            }
            if (request != null && !state.pendingCall) {
                request = null
                failure = receiverNotice(state.detail).ifBlank { getString(R.string.r_dial_failed) }
            }
            val typed = input.text?.toString().orEmpty()
            val valid = receiverDialNumber(typed) != null
            title.updateText(contact?.takeIf { typed == initial } ?: getString(R.string.r_keypad))
            field.error = getString(R.string.r_incomplete_number).takeIf { typed.isNotBlank() && !valid }
            if (field.error == null) field.isErrorEnabled = false
            erase.isEnabled = typed.isNotEmpty()
            val reason = when {
                !state.connected -> R.string.r_sender_disconnected
                request != null || submitting -> R.string.r_dialing
                state.inCall != null || state.pendingCall -> R.string.r_finish_other_call
                !state.canDial -> R.string.r_call_control_needed
                else -> 0
            }
            val status = if (reason != 0) getString(reason) else failure.orEmpty()
            dialStatus.isVisible = status.isNotBlank()
            dialStatus.setTextColor(color(if (reason == 0 && failure != null) androidx.appcompat.R.attr.colorError
                else MaterialR.attr.colorOnSurfaceVariant))
            dialStatus.updateText(status)
            call.isEnabled = reason == 0 && valid
        }
        input.doAfterTextChanged { failure = null; updateActions() }
        updateActions()
        pad.addView(dialStatus, fullWidth(top = 4))
        pad.addView(call, fullWidth(top = 8))
        dialog.setContentView(ScrollView(this).apply { addView(pad) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnShowListener { state.listeners.add(updateActions) }
        dialog.setOnDismissListener {
            state.listeners.remove(updateActions)
            if (dialDialog === dialog) dialDialog = null
        }
        dialog.show()
    }

    private fun compose(initial: String? = null, name: String? = null) {
        startActivity(Intent(this, ConversationActivity::class.java).putExtra("address", initial).putExtra("name", name))
    }

    private fun microphone(action: (Boolean) -> Unit) {
        val granted = microphoneAllowed()
        val needsMic = state.microphoneOffered || state.audioMode == "two_way" || state.inCall?.simulated == true
        if (!needsMic || granted) action(needsMic && granted)
        else {
            micAction = action
            requestPermissions(arrayOf(permission.RECORD_AUDIO), MIC)
        }
    }

    private fun microphoneAllowed() = checkSelfPermission(permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
            statusOverride = getString(R.string.r_camera_unavailable)
            render()
        }
    }

    private fun choosePhoto() {
        statusOverride = null
        val pickerOpened = Build.VERSION.SDK_INT >= 33 && runCatching {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PHOTO)
        }.isSuccess
        if (!pickerOpened && !runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PHOTO)
        }.isSuccess) {
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
                    when {
                        ReceiverService.hasNearby(this) -> beginPairing()
                        nearbyDenied && !shouldShowRequestPermissionRationale(permission.BLUETOOTH_CONNECT) -> openAppSettings()
                        else -> requestNearby()
                    }
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
        val pending = secret ?: return
        secret = null
        statusOverride = null
        state.detail = ""
        awaitingPair = true
        runCatching { ReceiverService.start(this, pending) }.onFailure {
            pending?.fill(0)
            awaitingPair = false
            statusOverride = getString(R.string.r_start_failed)
        }
    }

    private fun startReceiver() {
        statusOverride = null
        runCatching { ReceiverService.start(this) }.onFailure {
            statusOverride = getString(R.string.r_start_failed)
        }
    }

    override fun onDestroy() {
        dialDialog?.dismiss()
        secret?.fill(0)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            NEARBY -> {
                nearbyDenied = !ReceiverService.hasNearby(this)
                if (nearbyDenied) {
                    statusOverride = getString(R.string.r_nearby_denied)
                } else if (secret != null) {
                    beginPairing()
                } else if (pairKey.exists() && ReceiverService.current == null) {
                    startReceiver()
                } else {
                    statusOverride = null
                }
            }
            NOTIFICATIONS -> notificationsDenied = results.firstOrNull() != PackageManager.PERMISSION_GRANTED
            MIC -> {
                val action = micAction
                micAction = null
                microphoneDenied = results.firstOrNull() != PackageManager.PERMISSION_GRANTED
                action?.invoke(!microphoneDenied)
            }
        }
        render()
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
            isAllCaps = false
            minimumHeight = dp(54)
            maxLines = 3
            insetTop = dp(2)
            insetBottom = dp(2)
            if (image != 0) setIconResource(image)
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
    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }

    private fun date(value: Long): String = when {
        value <= 0 -> ""
        DateUtils.isToday(value) -> android.text.format.DateFormat.getTimeFormat(this).format(Date(value))
        else -> DateUtils.formatDateTime(this, value, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    private fun day(value: Long): String = DateUtils.getRelativeTimeSpanString(value, System.currentTimeMillis(),
        DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH).toString()

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
        val day: String? = null, val open: () -> Unit)

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
            (existing as? EntryView ?: EntryView()).apply {
                val item = getItem(position)
                val first = position == 0 || item.day != getItem(position - 1).day
                val last = position == count - 1 || item.day != getItem(position + 1).day
                bind(item, first, last)
            }
    }

    private inner class EntryView : LinearLayout(this@ReceiverActivity) {
        private val accent = MaterialR.attr.colorOnPrimaryContainer
        private val symbol = smallIcon(R.drawable.ic_call, color(accent))
        private val dateTitle = heading("", R.style.TextAppearance_ezcall_LabelLarge, androidx.appcompat.R.attr.colorPrimary)
        private val title = label("", R.style.TextAppearance_ezcall_TitleMedium).singleLine()
        private val detail = label("", R.style.TextAppearance_ezcall_BodyMedium,
            MaterialR.attr.colorOnSurfaceVariant).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val time = label("", R.style.TextAppearance_ezcall_LabelMedium, MaterialR.attr.colorOnSurfaceVariant)
        private val body = row()

        init {
            orientation = VERTICAL
            layoutParams = AbsListView.LayoutParams(-1, -2)
            isScreenReaderFocusable = true
            setPaddingRelative(dp(24), 0, dp(24), 0)
            addView(dateTitle, fullWidth(top = 8, bottom = 10))
            addView(body.apply {
                minimumHeight = dp(68)
                setPaddingRelative(dp(12), dp(10), dp(12), dp(10))
                addView(FrameLayout(this@ReceiverActivity).apply {
                    background = rounded(color(MaterialR.attr.colorPrimaryContainer), 20)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(symbol, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                }, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(column().apply { addView(title); addView(detail, fullWidth(top = 2)) },
                    LayoutParams(0, -2, 1f))
                addView(time, LayoutParams(-2, -2).apply { marginStart = dp(12) })
            }, fullWidth(bottom = 1))
        }

        fun bind(entry: Row, first: Boolean, last: Boolean) {
            symbol.setImageResource(entry.image)
            dateTitle.isVisible = first && entry.day != null
            dateTitle.text = entry.day.orEmpty()
            val top = if (first) dp(20).toFloat() else 0f
            val bottom = if (last) dp(20).toFloat() else 0f
            body.background = GradientDrawable().apply {
                setColor(color(MaterialR.attr.colorSurfaceContainerLow))
                cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            }
            title.text = entry.title
            detail.text = entry.subtitle
            detail.visibility = visible(entry.subtitle.isNotEmpty())
            time.text = entry.time
            time.visibility = visible(entry.time.isNotEmpty())
        }
    }

    private fun TextView.singleLine() = apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    companion object {
        private const val CAMERA = 101
        private const val PHOTO = 102
        private const val NEARBY = 103
        private const val NOTIFICATIONS = 104
        private const val MIC = 105
        private const val SETTINGS = 106
    }
}
