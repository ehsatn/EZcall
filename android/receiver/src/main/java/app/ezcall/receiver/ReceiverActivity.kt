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
import android.graphics.Typeface
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
import app.ezcall.android.SenderNetwork
import app.ezcall.android.Support
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.text.NumberFormat
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
    private lateinit var dashboardScreen: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var toolbarSettings: ImageButton
    private lateinit var settingsScreen: ScrollView
    private lateinit var settingsContent: LinearLayout
    private lateinit var alertsContent: LinearLayout
    private lateinit var audioTestInstructions: LinearLayout
    private var alertsOpen = false
    private var audioTestOpen = false
    private lateinit var settingsBack: OnBackPressedCallback
    private lateinit var settingsLinkTitle: TextView
    private lateinit var settingsLinkDetail: TextView
    private lateinit var settingsConnectionCard: MaterialCardView
    private lateinit var settingsRecovery: SettingRow
    private lateinit var pairedSettings: LinearLayout
    private lateinit var forgetSettings: View
    private lateinit var syncSetting: SettingRow
    private lateinit var testSetting: SettingRow
    private lateinit var senderBattery: SettingRow
    private lateinit var networkNote: TextView
    private val networkSwitches = mutableMapOf<SenderNetwork, MaterialSwitch>()
    private val networkUnavailable = mutableMapOf<SenderNetwork, SettingRow>()
    private lateinit var notificationSetting: SettingRow
    private var lockScreenSetting: SettingRow? = null
    private lateinit var microphoneSetting: SettingRow
    private lateinit var audioTestSetting: SettingRow
    private lateinit var pairingScreen: ScrollView
    private lateinit var pairingStatus: TextView
    private lateinit var pairingProgress: LinearProgressIndicator
    private lateinit var scanButton: MaterialButton
    private lateinit var photoButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var pairingRecovery: MaterialButton
    private lateinit var connection: MaterialCardView
    private lateinit var linkRow: LinearLayout
    private lateinit var linkBadge: FrameLayout
    private lateinit var linkTitle: TextView
    private lateinit var linkDetail: TextView
    private lateinit var linkAction: MaterialButton
    private lateinit var connectionSettingsButton: ImageButton
    private lateinit var nav: BottomNavigationView
    private lateinit var pairingArt: ImageView
    private lateinit var pairingStateArt: ImageView
    private lateinit var brandWordmark: TextView
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
    private var displayedAudioTest: List<Any?>? = null
    private lateinit var callScreen: LinearLayout
    private val floatDashboardAction get() = resources.configuration.fontScale < 1.3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (this is IncomingCallActivity) {
            callPanel = card(0)
            toolbar = buildToolbar().apply {
                menu.clear()
                setNavigationIcon(R.drawable.receiver_back)
                setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
                setNavigationOnClickListener { finishAndRemoveTask() }
                visibility = View.GONE
            }
            callScreen = column().apply {
                setBackgroundColor(getColor(R.color.receiver_call_surface))
                addView(toolbar)
                addView(ScrollView(this@ReceiverActivity).apply {
                    isFillViewport = true
                    addView(callPanel)
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                applyScreenInsets(this)
            }
            setContentView(callScreen)
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

        dashboardScreen = column()
        val root = dashboardScreen
        applyScreenInsets(root)
        toolbar = buildToolbar()
        root.addView(toolbar)

        val content = FrameLayout(this)
        pairingScreen = buildPairingScreen()
        dashboard = buildDashboard(savedInstanceState)
        alertsOpen = savedInstanceState?.getBoolean("alerts") == true
        audioTestOpen = savedInstanceState?.getBoolean("audioTest") ?: intent.getBooleanExtra("audioTest", false)
        settingsScreen = buildSettings()
        settingsBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (alertsOpen || audioTestOpen) {
                    alertsOpen = false
                    audioTestOpen = false
                    settingsScreen.scrollTo(0, 0)
                    render()
                } else showSettings(false)
            }
        }
        onBackPressedDispatcher.addCallback(this, settingsBack)
        settingsBack.isEnabled = savedInstanceState?.getBoolean("settings") ?: intent.getBooleanExtra("audioTestReturn", false)
        content.addView(pairingScreen, FrameLayout.LayoutParams(-1, -1))
        content.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        content.addView(settingsScreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (this !is IncomingCallActivity && intent.getBooleanExtra("audioTestReturn", false)) {
            if (intent.getBooleanExtra("audioTest", false)) showAudioTestSettings() else showSettings(true)
        }
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
        contentInsetStartWithNavigation = dp(60)
        setPaddingRelative(0, 0, dp(20), 0)
        val brand = row().apply {
            addView(ImageView(this@ReceiverActivity).apply {
                setImageResource(R.drawable.receiver_header_brand)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            brandWordmark = heading(getString(R.string.ez_brand), R.style.TextAppearance_ezcall_TitleLarge).apply {
                textSize = 24f
                typeface = Typeface.create(typeface, 500, false)
            }
            addView(brandWordmark)
            addView(label(getString(R.string.r_receiver), R.style.TextAppearance_ezcall_LabelMedium,
                MaterialR.attr.colorOnPrimaryContainer).apply {
                background = rounded(color(MaterialR.attr.colorPrimaryContainer), 20)
                setPaddingRelative(dp(14), dp(4), dp(14), dp(4))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        addView(brand, Toolbar.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        if (this@ReceiverActivity !is IncomingCallActivity) {
            toolbarSettings = iconButton(R.drawable.ic_settings, getString(R.string.ez_settings)) { showSettings(true) }.apply {
                layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
            }
            menu.add(0, SETTINGS, 0, R.string.ez_settings).apply {
                actionView = toolbarSettings
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
    }

    private fun showSettings(show: Boolean) {
        alertsOpen = false
        audioTestOpen = false
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(24), dp(36), dp(24), dp(24))

            pairingStateArt = ImageView(this@ReceiverActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(pairingStateArt, LinearLayout.LayoutParams(-1, dp(208)).apply { bottomMargin = dp(24) })
            pairingTitle = heading(getString(R.string.ez_pair_phones), R.style.TextAppearance_ezcall_DisplaySmall).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            addView(pairingTitle, fullWidth())
            pairingStatus = label("", R.style.TextAppearance_ezcall_BodyLarge).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0f
                setTextColor(getColor(R.color.receiver_pairing_body))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(pairingStatus, fullWidth(top = 10))
            pairingArt = ImageView(this@ReceiverActivity).apply {
                setImageResource(R.drawable.receiver_pairing_home)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val artSize = (resources.configuration.screenWidthDp - 48).coerceIn(160, 344)
            addView(pairingArt, LinearLayout.LayoutParams(-1, dp(artSize)).apply { topMargin = dp(22) })

            pairingProgress = LinearProgressIndicator(this@ReceiverActivity).apply {
                isIndeterminate = true
                trackCornerRadius = dp(2)
                setIndicatorColor(Color.WHITE)
                trackColor = 0x52FFFFFF
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(pairingProgress, LinearLayout.LayoutParams(dp(160), dp(4)).apply { topMargin = dp(24) })

            scanButton = pairingButton(getString(R.string.r_scan_qr), click = ::camera)
            photoButton = pairingButton(getString(R.string.r_choose_qr), secondary = true, click = ::choosePhoto)
            cancelButton = pairingButton(getString(R.string.ez_cancel_pairing), secondary = true) { ReceiverService.current?.forget() }
            pairingRecovery = pairingButton("", click = ::recoverConnection)
            addView(scanButton, fullWidth(top = 20))
            addView(pairingRecovery, fullWidth(top = 20))
            addView(photoButton, fullWidth(top = 8))
            addView(cancelButton, fullWidth(top = 20))
        })
    }

    private fun pairingButton(title: String, secondary: Boolean = false, click: () -> Unit) =
        button(title, 0, secondary, click = click).apply {
            minimumHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(if (secondary) 0x29FFFFFF else Color.WHITE)
            setTextColor(if (secondary) Color.WHITE else getColor(R.color.ezcall_pairing_hero))
            if (secondary) strokeColor = ColorStateList.valueOf(0xB3FFFFFF.toInt())
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
        linkRow = row()
        linkBadge = FrameLayout(this@ReceiverActivity).apply {
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
        linkRow.addView(connectionSettingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        linkContent.addView(linkRow, fullWidth())
        linkAction = button("", 0, click = ::recoverConnection)
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
            stateListAnimator = null
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
            itemTextColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(androidx.appcompat.R.attr.colorPrimary), color(MaterialR.attr.colorOnSurfaceVariant))
            )
            itemIconTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnSurface))
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
        setPadding(dp(24), dp(64), dp(24), dp(20))
        emptyIcon = smallIcon(activeTab.icon, color(MaterialR.attr.colorOnPrimaryContainer)).apply {
            background = rounded(color(MaterialR.attr.colorPrimaryContainer), 32)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        addView(emptyIcon, LinearLayout.LayoutParams(dp(64), dp(64)))
        emptyTitle = heading("", R.style.TextAppearance_ezcall_TitleLarge).apply { gravity = Gravity.CENTER }
        addView(emptyTitle, fullWidth(top = 12))
        emptyDetail = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant)
            .apply { gravity = Gravity.CENTER }
        addView(emptyDetail, fullWidth(top = 10))
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
        outState.putBoolean("audioTest", audioTestOpen)
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
            val call = state.inCall?.takeIf { it.id == id }
            val test = state.audioTest?.takeIf { it.id == id }
            if (state.paired && test != null && call?.state != "ringing") {
                renderAudioTestCall(test, call?.state == "active")
            } else if (state.paired && id != null && (call != null || ReceiverService.current?.isDialing(id) == true)) {
                renderCall()
            } else finishAndRemoveTask()
            return
        }
        val paired = state.paired || pairKey.exists()
        val settingsOpen = settingsBack.isEnabled
        if (state.inCall != null || state.pendingCall) support.dismissReminder()
        settingsScreen.visibility = visible(settingsOpen)
        pairingScreen.visibility = visible(!settingsOpen && !paired)
        dashboard.visibility = visible(!settingsOpen && paired)
        val pairScreenVisible = !settingsOpen && !paired
        dashboardScreen.setBackgroundColor(if (pairScreenVisible) getColor(R.color.ezcall_pairing_hero)
            else color(android.R.attr.colorBackground))
        brandWordmark.setTextColor(if (pairScreenVisible) Color.WHITE else color(MaterialR.attr.colorOnSurface))
        toolbarSettings.apply {
            val surface = if (pairScreenVisible) getColor(R.color.receiver_pairing_settings)
                else color(MaterialR.attr.colorSurfaceContainerLow)
            val ink = if (pairScreenVisible) getColor(R.color.receiver_pairing_settings_ink) else color(MaterialR.attr.colorOnSurface)
            setColorFilter(ink)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(surface, 24), null)
        }
        WindowCompat.getInsetsController(window, dashboardScreen).apply {
            val lightBars = !pairScreenVisible && resources.getBoolean(R.bool.ezcall_light_system_bars)
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
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
            createAction.setIconResource(activeTab.icon)
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
        val permissionNeeded = secret != null && nearbyDenied && !ReceiverService.hasNearby(this)
        val invalid = statusOverride == getString(R.string.r_no_code)
        val feedback = statusOverride ?: state.detail.takeIf(String::isNotBlank)?.let(::receiverNotice)
        val timedOut = !enrolling && feedback == getString(R.string.r_pair_timeout)
        val busy = openingQR || enrolling && statusOverride == null
        val hasPairingState = busy || permissionNeeded || invalid || timedOut
        pairingStateArt.isVisible = hasPairingState
        pairingArt.isVisible = !hasPairingState
        val drawable = when {
            permissionNeeded -> R.drawable.receiver_pairing_permission
            invalid -> R.drawable.receiver_pairing_invalid
            timedOut -> R.drawable.receiver_pairing_timeout
            else -> R.drawable.receiver_pairing_connecting
        }
        pairingStateArt.setImageResource(drawable)
        pairingTitle.setTextAppearance(if (hasPairingState) R.style.TextAppearance_ezcall_HeadlineLarge
            else R.style.TextAppearance_ezcall_DisplaySmall)
        pairingTitle.typeface = Typeface.create(pairingTitle.typeface, 500, false)
        pairingTitle.letterSpacing = 0f
        pairingTitle.setTextColor(Color.WHITE)
        pairingTitle.setText(when {
            enrolling -> R.string.r_pair_connecting
            permissionNeeded -> R.string.r_need_nearby
            invalid -> R.string.r_pair_invalid_code
            timedOut -> R.string.r_pair_timed_out
            else -> R.string.ez_pair_phones
        })
        pairingStatus.updateText(when {
            openingQR -> getString(R.string.r_reading_code)
            enrolling && connectionAction() == R.string.r_enable_bluetooth && feedback != null -> feedback
            enrolling -> getString(R.string.r_pair_keep_nearby)
            invalid -> getString(R.string.r_pair_scan_sender)
            else -> feedback ?: getString(R.string.r_pair_open_code)
        })
        pairingProgress.isVisible = busy
        scanButton.isVisible = !enrolling && !permissionNeeded
        photoButton.isVisible = !enrolling
        scanButton.isEnabled = !openingQR
        photoButton.isEnabled = !openingQR
        scanButton.setText(if (invalid || timedOut) R.string.r_pair_scan_again else R.string.r_scan_qr)
        photoButton.setText(when {
            permissionNeeded -> R.string.ez_cancel
            timedOut -> R.string.r_pair_back
            else -> R.string.r_choose_qr
        })
        photoButton.setOnClickListener {
            if (permissionNeeded || timedOut) {
                secret?.fill(0)
                secret = null
                statusOverride = null
                state.detail = ""
                render()
            } else choosePhoto()
        }
        cancelButton.isVisible = enrolling
        cancelButton.isEnabled = ReceiverService.current != null
        val recovery = when {
            permissionNeeded -> R.string.r_allow_nearby
            enrolling && connectionAction() == R.string.r_enable_bluetooth -> R.string.r_enable_bluetooth
            else -> 0
        }
        pairingRecovery.isVisible = recovery != 0
        if (recovery != 0) pairingRecovery.setText(recovery)
    }

    private fun renderConnection() {
        val recovery = connectionAction()
        val connected = state.connected && recovery == 0
        val compact = recovery == 0
        val title = when {
            recovery == R.string.r_allow_nearby -> R.string.r_need_nearby
            !compact -> R.string.r_connection_lost
            !connected -> R.string.r_connection_reconnecting
            state.syncing -> R.string.r_connection_syncing
            else -> R.string.r_sender_connected
        }
        val detail = when {
            !compact -> connectionDetail(recovery, connected)
            !connected -> getString(R.string.r_connection_stay_near)
            state.syncing -> getString(R.string.r_connection_syncing_detail)
            else -> ""
        }
        connection.isVisible = activeTab == Destination.CALLS || !connected || state.syncing
        connection.radius = dp(if (compact) 16 else 24).toFloat()
        connection.setCardBackgroundColor(color(if (compact) MaterialR.attr.colorPrimaryContainer
            else MaterialR.attr.colorSurfaceContainerHigh))
        linkRow.minimumHeight = dp(if (!compact) 0 else if (detail.isEmpty()) 44 else 59)
        val verticalInset = if (!compact) 16 else if (detail.isNotEmpty()) 4 else 0
        linkRow.setPaddingRelative(dp(if (compact) 12 else 16), dp(verticalInset), dp(8), dp(if (compact) verticalInset else 0))
        linkBadge.isVisible = compact
        linkTitle.setTextAppearance(if (compact) R.style.TextAppearance_ezcall_LabelLarge else R.style.TextAppearance_ezcall_TitleLarge)
        linkTitle.setTextColor(color(if (compact) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface))
        linkDetail.setTextAppearance(if (compact) R.style.TextAppearance_ezcall_BodySmall else R.style.TextAppearance_ezcall_BodyMedium)
        linkDetail.setTextColor(color(if (compact) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant))
        linkTitle.updateText(getString(title))
        linkDetail.updateText(detail)
        linkDetail.visibility = visible(detail.isNotEmpty())
        connectionSettingsButton.isVisible = connected && !state.syncing
        linkAction.isVisible = !compact
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
        val checklist = test?.audioChecks()
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
            renderCallScreen(call, name.orEmpty(), callDetail, locked)
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
        })
    }

    private fun renderCallScreen(call: ReceivedCall, name: String, detail: String, locked: Boolean) {
        val ringing = call.state == "ringing"
        val dialing = call.state == "dialing"
        val ink = MaterialR.attr.colorOnPrimaryContainer
        toolbar.visibility = View.GONE
        callScreen.setBackgroundColor(getColor(R.color.receiver_call_surface))
        callPanel.setCardBackgroundColor(getColor(R.color.receiver_call_surface))
        callPanel.addView(column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(24), dp(56), dp(24), dp(80))
            addView(label(getString(R.string.r_call_brand), R.style.TextAppearance_ezcall_LabelLarge, ink).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconDrawable(R.drawable.ic_bluetooth, ink), null, null, null)
                compoundDrawablePadding = dp(8)
            }, LinearLayout.LayoutParams(-2, -2))
            val status = when {
                call.simulated -> R.string.r_settings_audio_test
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
            addView(avatar, LinearLayout.LayoutParams(dp(224), dp(224)).apply {
                topMargin = dp(16)
                bottomMargin = -dp(16)
            })
            addView(heading(if (call.simulated) getString(R.string.r_audio_test_sender) else if (known) name
                else name.takeUnless { it == call.address } ?: receiverNumberDisplay(call.address),
                R.style.TextAppearance_ezcall_DisplaySmall, ink).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create(typeface, 500, false)
            }, fullWidth())
            if (known && !call.simulated) addView(label(receiverNumberDisplay(call.address), R.style.TextAppearance_ezcall_BodyLarge, ink),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            if (ringing && locked && !call.simulated) {
                addView(label(getString(R.string.r_call_from_sender), R.style.TextAppearance_ezcall_BodyMedium, ink),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            }
            if (detail.isNotBlank()) addView(label(detail, R.style.TextAppearance_ezcall_BodyMedium, ink).apply {
                gravity = Gravity.CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, fullWidth(top = 12))
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

    private fun renderAudioTestCall(test: ReceiverAudioTest, active: Boolean) {
        val checks = test.log.audioChecks()
        val passed = checks.all { it }
        val voiceCheck = checks.first()
        val callStatus = if (active && (!state.connected || state.pendingCall)) receiverNotice(state.detail) else ""
        val display = listOf(test.id, active, checks, state.connected, state.pendingCall, callStatus)
        if (displayedAudioTest == display) return
        displayedAudioTest = display
        callPanelContents = null
        toolbar.visibility = View.VISIBLE
        callScreen.setBackgroundColor(color(MaterialR.attr.colorSurface))
        callPanel.setCardBackgroundColor(color(MaterialR.attr.colorSurface))
        callPanel.removeAllViews()
        callPanel.visibility = View.VISIBLE
        callPanel.addView(column().apply {
            setPaddingRelative(dp(24), dp(16), dp(24), dp(24))
            when {
                passed -> addView(audioTestHero(R.drawable.receiver_check, R.string.r_audio_test_complete,
                    R.string.r_audio_test_passed, R.string.r_audio_test_passed_detail), fullWidth())
                !active -> addView(audioTestHero(R.drawable.receiver_mic, R.string.ez_test_auto_incomplete,
                    R.string.r_audio_test_incomplete, R.string.r_audio_test_incomplete_detail), fullWidth())
                else -> addView(audioTestHero(if (voiceCheck) R.drawable.receiver_mic else R.drawable.ic_call,
                    R.string.r_audio_test_active, if (voiceCheck) R.string.r_audio_test_check_voice else R.string.r_audio_test_check_audio,
                    if (voiceCheck) R.string.r_audio_test_voice_detail else R.string.r_audio_test_instructions), fullWidth())
            }
            if (active && !passed) {
                if (callStatus.isNotBlank()) addView(label(callStatus, R.style.TextAppearance_ezcall_BodyMedium,
                    MaterialR.attr.colorOnSurfaceVariant).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }, fullWidth(top = 12))
                addView(audioTestCard {
                    addView(heading(getString(R.string.r_audio_test_levels), R.style.TextAppearance_ezcall_TitleMedium))
                    val wave = if (voiceCheck) test.log.localWaveform else test.log.remoteWaveform
                    addView(label(getString(if (voiceCheck) R.string.ez_test_own_wave else R.string.ez_test_incoming_wave),
                        R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant), fullWidth(top = 6))
                    addView(AudioWaveformView(this@ReceiverActivity, wave, color(androidx.appcompat.R.attr.colorPrimary), live = true)
                        .apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                        LinearLayout.LayoutParams(-1, dp(38)))
                }, fullWidth(top = 16))
            }
            addView(audioTestCard {
                if (active || passed) {
                    addView(heading(getString(R.string.r_audio_test_checks), R.style.TextAppearance_ezcall_TitleMedium))
                    val titles = listOf(R.string.r_audio_test_tone, R.string.r_audio_test_mic, R.string.r_audio_test_echo)
                    val waiting = listOf(R.string.ez_test_wait_tone, R.string.ez_test_wait_mic, R.string.ez_test_wait_echo)
                    titles.forEachIndexed { index, title ->
                        addAudioTestCheck(title, when {
                            passed -> R.string.r_audio_test_check_confirmed
                            checks[index] -> R.string.r_audio_test_detected
                            else -> waiting[index]
                        })
                    }
                } else {
                    addAudioTestCheck(R.string.r_audio_test_two_way, R.string.r_audio_test_unconfirmed)
                    addView(heading(getString(R.string.r_audio_test_before_retry), R.style.TextAppearance_ezcall_TitleMedium), fullWidth(top = 16))
                    addView(label(getString(R.string.r_audio_test_retry_detail), R.style.TextAppearance_ezcall_BodyMedium), fullWidth(top = 6))
                }
            }, fullWidth(top = 16))
            if (active && !passed) {
                addView(button(getString(R.string.r_audio_test_end), 0, danger = true) {
                    ReceiverService.current?.end(test.id)
                }.apply { isEnabled = state.canControlCurrentCall && !state.pendingCall }, fullWidth(top = 12))
            } else {
                addView(button(getString(if (passed) R.string.r_audio_test_done else R.string.r_audio_test_retry), 0) {
                    leaveAudioTest(instructions = !passed)
                }, fullWidth(top = 12))
                addView(button(getString(if (passed) R.string.r_audio_test_again else R.string.r_audio_test_back), 0, secondary = true) {
                    leaveAudioTest(instructions = passed)
                }, fullWidth(top = 12))
            }
        })
        ViewCompat.setAccessibilityPaneTitle(callPanel, getString(when {
            passed -> R.string.r_audio_test_passed
            active -> R.string.r_audio_test_active
            else -> R.string.r_audio_test_incomplete
        }))
    }

    private fun leaveAudioTest(instructions: Boolean) {
        startActivity(Intent(this, ReceiverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("audioTest", instructions).putExtra("audioTestReturn", true))
        finishAndRemoveTask()
    }

    private fun audioTestCard(accent: Boolean = false, items: LinearLayout.() -> Unit) = card(24).apply {
        setCardBackgroundColor(color(if (accent) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerLow))
        addView(column().apply {
            setPaddingRelative(dp(20), dp(20), dp(20), dp(20))
            items()
        })
    }

    private fun audioTestHero(icon: Int, caption: Int, title: Int, detail: Int) = audioTestCard(accent = true) {
        val ink = MaterialR.attr.colorOnPrimaryContainer
        addView(badge(icon, MaterialR.attr.colorSurface, ink), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(getString(caption), R.style.TextAppearance_ezcall_LabelMedium, ink), fullWidth(top = 12))
        addView(heading(getString(title), R.style.TextAppearance_ezcall_HeadlineMedium, ink).apply {
            typeface = Typeface.create(typeface, 500, false)
        }, fullWidth(top = 8))
        addView(label(getString(detail), R.style.TextAppearance_ezcall_BodyMedium, ink).apply { textSize = 15f }, fullWidth(top = 8))
    }

    private fun LinearLayout.addAudioTestCheck(title: Int, detail: Int) {
        addView(column().apply {
            setPaddingRelative(dp(16), dp(8), dp(16), dp(6))
            addView(heading(getString(title), R.style.TextAppearance_ezcall_TitleSmall))
            addView(label(getString(detail), R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant))
        }, fullWidth(top = 12))
    }

    private fun callCircle(title: Int, icon: Int, danger: Boolean, enabled: Boolean, action: () -> Unit) = column().apply {
        gravity = Gravity.CENTER
        val mic = icon == R.drawable.receiver_mic
        val fill = when {
            danger -> getColor(R.color.receiver_call_end)
            mic -> color(MaterialR.attr.colorPrimaryContainer)
            else -> color(androidx.appcompat.R.attr.colorPrimary)
        }
        val ink = when {
            danger -> Color.WHITE
            mic -> color(MaterialR.attr.colorOnPrimaryContainer)
            else -> color(MaterialR.attr.colorOnPrimary)
        }
        addView(iconButton(icon, getString(title), action).apply {
            setImageDrawable(iconDrawable(icon, MaterialR.attr.colorOnSurface).apply { setTint(ink) })
            background = RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 51)),
                rounded(fill, 32), null)
            isEnabled = enabled
            alpha = if (enabled) 1f else .5f
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(label(getString(title), R.style.TextAppearance_ezcall_LabelLarge, MaterialR.attr.colorOnPrimaryContainer).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, fullWidth(top = 8))
    }
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

                addView(settingsHeading(R.string.r_sender_phone), fullWidth(top = 18))
                addView(settingsGroup {
                    senderBattery = SettingRow(R.drawable.ic_battery, getString(R.string.r_sender_battery), click = null)
                    addView(senderBattery)
                    for ((setting, title) in listOf(
                        SenderNetwork.HOTSPOT to R.string.r_sender_hotspot,
                        SenderNetwork.MOBILE_DATA to R.string.r_sender_mobile_data
                    )) {
                        val control = MaterialSwitch(this@ReceiverActivity).apply {
                            setText(title)
                            setTextAppearance(R.style.TextAppearance_ezcall_BodyLarge)
                            minimumHeight = dp(56)
                            setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
                            switchPadding = dp(12)
                            setOnClickListener {
                                val requested = isChecked
                                isChecked = state.senderNetwork[setting] == true
                                ReceiverService.current?.setSenderNetwork(setting, requested)
                            }
                        }
                        networkSwitches[setting] = control
                        addView(control, fullWidth())
                        val unavailable = SettingRow(R.drawable.ic_status, getString(title), click = null)
                        unavailable.updateDetail(getString(R.string.r_sender_unavailable))
                        networkUnavailable[setting] = unavailable
                        addView(unavailable)
                    }
                }, fullWidth(top = 6))
                networkNote = label("", R.style.TextAppearance_ezcall_BodyMedium, MaterialR.attr.colorOnSurfaceVariant).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                addView(networkNote, fullWidth(top = 8))

                addView(settingsHeading(R.string.r_calling_settings), fullWidth(top = 18))
                addView(settingsGroup {
                    addView(SettingRow(R.drawable.ic_notifications, getString(R.string.r_calls_and_alerts),
                        opensPage = true, click = ::showAlerts).apply { updateDetail(getString(R.string.r_calls_and_alerts_detail)) })
                    audioTestSetting = SettingRow(R.drawable.receiver_mic, getString(R.string.r_settings_audio_test),
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
        audioTestInstructions = buildAudioTestInstructions()
        addView(column().apply {
            addView(settingsContent, fullWidth())
            addView(alertsContent, fullWidth())
            addView(audioTestInstructions, fullWidth())
        })
    }

    private fun showAlerts() {
        alertsOpen = true
        audioTestOpen = false
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
        if (!paired) {
            alertsOpen = false
            audioTestOpen = false
        }
        settingsContent.isVisible = !alertsOpen && !audioTestOpen
        alertsContent.isVisible = alertsOpen
        audioTestInstructions.isVisible = audioTestOpen
        ViewCompat.setAccessibilityPaneTitle(settingsScreen, getString(when {
            alertsOpen -> R.string.r_calls_and_alerts
            audioTestOpen -> R.string.r_settings_audio_test
            else -> R.string.ez_settings
        }))
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
        senderBattery.updateDetail(state.senderBattery?.let { NumberFormat.getPercentInstance().format(it / 100.0) }
            ?: getString(R.string.r_sender_unavailable))
        networkSwitches.forEach { (setting, control) ->
            val value = state.senderNetwork[setting]
            control.isVisible = value != null
            control.isChecked = value == true
            control.isEnabled = connected && !state.networkPending
            networkUnavailable.getValue(setting).isVisible = value == null
        }
        val networkMessage = when {
            state.networkPending -> R.string.r_sender_network_pending
            state.networkFailed -> R.string.r_sender_network_failed
            state.senderNetworkAllowed == false -> R.string.r_sender_network_allow
            state.senderNetworkAllowed == true && state.senderNetwork.isEmpty() -> R.string.r_sender_network_setup
            else -> 0
        }
        networkNote.isVisible = networkMessage != 0
        if (networkMessage != 0) networkNote.updateText(getString(networkMessage))
        if (alertsOpen) renderAlerts()
        audioTestSetting.updateDetail(state.audioTest?.let { getString(audioTestStatus(it)) }
            ?: getString(R.string.r_check_call_audio))
    }

    private fun renderAlerts() {
        val alerts = getSystemService(NotificationManager::class.java)
        notificationSetting.updateDetail(permissionStatus(alerts.areNotificationsEnabled()))
        if (Build.VERSION.SDK_INT >= 34) lockScreenSetting?.updateDetail(permissionStatus(alerts.canUseFullScreenIntent()))
        microphoneSetting.updateDetail(permissionStatus(microphoneAllowed()))
    }

    private fun permissionStatus(allowed: Boolean) = getString(if (allowed) R.string.r_permission_on else R.string.r_permission_off)

    private fun audioTestStatus(test: ReceiverAudioTest) = when {
        test.log.audioChecks().all { it } -> R.string.r_audio_test_passed
        state.inCall?.id == test.id -> R.string.r_audio_test_running
        else -> R.string.ez_test_auto_incomplete
    }

    private fun showAudioTestSettings() {
        alertsOpen = false
        audioTestOpen = true
        settingsBack.isEnabled = true
        settingsScreen.scrollTo(0, 0)
        render()
    }

    private fun buildAudioTestInstructions() = column().apply {
        setPaddingRelative(dp(24), dp(16), dp(24), dp(24))
        addView(audioTestHero(R.drawable.receiver_mic, R.string.r_audio_test_receiver,
            R.string.r_settings_audio_test, R.string.r_audio_test_intro), fullWidth())
        addView(audioTestCard {
            addView(heading(getString(R.string.r_audio_test_how), R.style.TextAppearance_ezcall_TitleMedium))
            addView(label(getString(R.string.r_audio_test_step_sender), R.style.TextAppearance_ezcall_BodyMedium), fullWidth(top = 10))
            addView(label(getString(R.string.r_audio_test_step_receiver), R.style.TextAppearance_ezcall_BodyMedium), fullWidth(top = 10))
            addView(label(getString(R.string.r_audio_test_automatic), R.style.TextAppearance_ezcall_BodyMedium,
                MaterialR.attr.colorOnSurfaceVariant), fullWidth(top = 10))
        }, fullWidth(top = 16))
        addView(label(getString(R.string.r_audio_test_waiting), R.style.TextAppearance_ezcall_BodyMedium,
            MaterialR.attr.colorOnSurfaceVariant), fullWidth(top = 16))
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
                    intArrayOf(color(MaterialR.attr.colorSurfaceContainerHigh), getColor(R.color.receiver_call_end)))
                val foreground = ColorStateList(states,
                    intArrayOf(color(MaterialR.attr.colorOnSurfaceVariant), Color.WHITE))
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
