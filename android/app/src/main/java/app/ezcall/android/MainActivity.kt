package app.ezcall.android

import android.Manifest.permission
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.WindowCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import rikka.shizuku.Shizuku

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    private lateinit var support: Support
    private lateinit var screen: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var brand: LinearLayout
    private lateinit var guideProgress: MaterialTextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var guideAction: LinearLayout
    private lateinit var navigation: BottomNavigationView
    private lateinit var navigationDivider: View
    private lateinit var back: OnBackPressedCallback
    private var page = Page.CONNECTION
    private var detail: Detail? = null
    private var guideFromSettings = false
    private var audioSetupParent: Detail? = null
    private val offersRoot by lazy(CallAudio.Setup::offersRoot)
    private var offeredRoot = false
    private var startingRoot = false
    private var rootGranted = false
    private var reportParent: Detail? = null
    private val audioSetupFromGuide get() = audioSetupParent == Detail.GUIDE
    private var connectionPanel: LinearLayout? = null
    private var sharingPanel: LinearLayout? = null
    private var audioPanel: LinearLayout? = null
    private var audioIntro: View? = null
    private var audioMethodsLink: LinearLayout? = null
    private var testPanel: LinearLayout? = null
    private var reportPanel: LinearLayout? = null
    private var settingsNotifications: SettingsRow? = null
    private var settingsBattery: SettingsRow? = null
    private var shownAudio: AudioDisplay? = null
    private var shownTest: TestDisplay? = null
    private var shownReport: TestCallReport? = null
    private var testRequestFailed = false
    private var showTestRun = false
    private var connectionIssue: Int? = null
    private var relayStarting = false
    private var relayBeforeStart: RelayState? = null
    private val startTimeout = Runnable {
        if (relayStarting) {
            relayStarting = false
            relayBeforeStart = null
            connectionIssue = R.string.s_relay_open_app
            renderLink(RelayState.current)
        }
    }
    private var audioBusy = false
    private var audioReporting = false
    private var cancelUsbSetup: (() -> Unit)? = null
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_AUDIO && !isDestroyed) {
            if (result == PackageManager.PERMISSION_GRANTED) setupShizukuAudio()
            else shizukuNotice(R.string.s_audio_shizuku_denied)
        }
    }
    private var setupAudioReady: CallAudio.Status? = null
    private var setupAudioError: Int? = null
    private var requestingFeature: Feature? = null
    private var lastState: RelayState? = null
    private var pairingAgain = false
    private var pairingHint: String? = null
    private var qrImage: Bitmap? = null
    private var qrValue: String? = null
    internal enum class Step(val title: Int, val detail: Int, val help: Int) {
        SHARING(R.string.s_choose_sharing, R.string.s_ui_guide_sharing, R.string.s_ui_guide_help_sharing),
        LINK(R.string.ez_pair_phones, R.string.s_ui_guide_pairing, R.string.s_ui_guide_help_pairing),
        AUDIO(R.string.s_setup_audio, R.string.s_ui_guide_optional_audio, R.string.s_ui_guide_help_audio);

        companion object {
            fun afterSharing(paired: Boolean) = if (paired) AUDIO else LINK

            fun restore(saved: String?, legacy: String?, paired: Boolean): Step? {
                if (saved == "done") return null
                entries.firstOrNull { it.name == saved }?.let { return it }
                return when (legacy) {
                    "WIRELESS", "AUDIO" -> AUDIO
                    "SHARING" -> SHARING
                    "LINK" -> if (paired) AUDIO else LINK
                    else -> if (paired) AUDIO else null
                }
            }
        }
    }
    private var step: Step? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermission)
        support = Support(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        requestingFeature = savedInstanceState?.getString("feature")?.let(Feature::valueOf)
        setupAudioReady = savedInstanceState?.getString("setupAudioReady")?.let(CallAudio.Status::valueOf)
        setupAudioError = savedInstanceState?.getInt("setupAudioError")?.takeIf { it != 0 }
        connectionIssue = savedInstanceState?.getInt("connectionIssue")?.takeIf { it != 0 }
        pairingAgain = savedInstanceState?.getBoolean("pairingAgain") == true
        pairingHint = savedInstanceState?.getString("pairingHint")
        showTestRun = savedInstanceState?.getBoolean("showTestRun") == true
        page = Page.entries.getOrElse(savedInstanceState?.getInt("page") ?: 0) { Page.CONNECTION }
        detail = savedInstanceState?.getString("detail")?.let(Detail::valueOf)
            ?: Detail.SETTINGS.takeIf { savedInstanceState?.getBoolean("settingsOpen") == true }
        guideFromSettings = savedInstanceState?.getBoolean("guideFromSettings") == true
        with(getPreferences(MODE_PRIVATE)) {
            val saved = getString("senderGuideStep", null)
            val legacy = getString("setupStep", null)
            val paired = PairKey(this@MainActivity).exists()
            step = Step.restore(saved, legacy, paired)
            if (savedInstanceState == null) {
                detail = when {
                    step != null && saved == null -> Detail.GUIDE
                    saved == null && legacy == null && !paired -> Detail.WELCOME
                    else -> null
                }
            }
            if (step == Step.LINK && paired) saveStep(Step.AUDIO)
        }
        audioSetupParent = savedInstanceState?.getString("audioSetupParent")?.let(Detail::valueOf)
        reportParent = savedInstanceState?.getString("reportParent")?.let(Detail::valueOf)
        if (detail == Detail.GUIDE && step == null) detail = null

        screen = stack()
        val column = stack()
        screen.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            val width = resources.displayMetrics.widthPixels - safe.left - safe.right
            val columnWidth = width.coerceAtMost(dp(600))
            val params = column.layoutParams
            if (params.width != columnWidth) {
                params.width = columnWidth
                column.layoutParams = params
            }
            WindowInsets.CONSUMED
        }
        toolbar = MaterialToolbar(this).apply {
            minimumHeight = dp(64)
            setContentInsetsRelative(dp(24), dp(24))
            contentInsetStartWithNavigation = dp(60)
            setTitleTextAppearance(this@MainActivity, R.style.TextAppearance_ezcall_TitleLarge)
            setNavigationOnClickListener { navigateBack() }
            menu.add(getString(R.string.ez_settings)).apply {
                actionView = headerButton(R.drawable.ic_settings, getString(R.string.ez_settings)) {
                    if (detail == Detail.SETTINGS) navigateBack() else showDetail(Detail.SETTINGS)
                }.apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(dp(48), dp(48))
                }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        brand = brandLockup(welcome = false)
        toolbar.addView(brand, Toolbar.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        guideProgress = text("", R.style.TextAppearance_ezcall_LabelMedium, color(MaterialR.attr.colorOnSurfaceVariant))
        toolbar.addView(guideProgress, Toolbar.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        content = stack()
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, space())
        }
        guideAction = stack().apply {
            setPaddingRelative(dp(24), dp(4), dp(24), dp(16))
        }
        navigation = BottomNavigationView(this).apply {
            for (destination in Page.entries) {
                menu.add(0, destination.ordinal + 1, 0, destination.title).setIcon(destination.icon)
            }
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setLabelMaxLines(2)
            setLabelFontScalingEnabled(true)
            isItemHorizontalTranslationEnabled = false
            setBackgroundColor(color(MaterialR.attr.colorSurface))
            setItemActiveIndicatorColor(ColorStateList.valueOf(color(MaterialR.attr.colorPrimaryContainer)))
            setItemIconSize(dp(24))
            itemIconTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnSurface))
            itemTextColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(androidx.appcompat.R.attr.colorPrimary), color(MaterialR.attr.colorOnSurfaceVariant))
            )
            selectedItemId = page.ordinal + 1
            setOnItemSelectedListener {
                page = Page.entries[it.itemId - 1]
                renderPage()
                true
            }
            setOnItemReselectedListener { scroll.smoothScrollTo(0, 0) }
        }
        column.addView(toolbar, space())
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(guideAction, space())
        navigationDivider = View(this).apply { setBackgroundColor(color(MaterialR.attr.colorOutlineVariant)) }
        column.addView(navigationDivider, space().apply { height = dp(1) })
        column.addView(navigation, space())
        screen.addView(column, space().apply { height = -1; gravity = Gravity.CENTER_HORIZONTAL })
        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = navigateBack()
        }
        onBackPressedDispatcher.addCallback(this, back)
        setContentView(screen)
        renderPage()
    }

    private fun navigateBack() {
        when {
            detail == Detail.WELCOME -> skipSetup()
            detail == Detail.AUDIO_SETUP -> {
                val parent = audioSetupParent
                audioSetupParent = null
                showDetail(parent)
            }
            detail == Detail.REPORT -> showDetail(reportParent)
            showTestRun && (detail == Detail.SETTINGS_AUDIO || detail == null && page == Page.AUDIO) -> closeTestRun()
            detail == Detail.OVERLAY -> showDetail(Detail.AUDIO_SETUP)
            detail in setOf(Detail.SETTINGS_AUDIO, Detail.NOTIFICATIONS, Detail.BATTERY) -> showDetail(Detail.SETTINGS)
            detail == Detail.GUIDE && step == Step.LINK -> cancelGuidePairing()
            detail == Detail.GUIDE && step == Step.AUDIO -> moveTo(Step.SHARING)
            detail == Detail.GUIDE -> closeGuide()
            detail != null -> showDetail(null)
            else -> navigation.selectedItemId = Page.CONNECTION.ordinal + 1
        }
    }

    private fun showDetail(next: Detail?) {
        detail = next
        renderPage()
    }

    private fun openAudioSetup() {
        audioSetupParent = detail
        showDetail(Detail.AUDIO_SETUP)
        if (offersRoot && !offeredRoot && !CallAudio.Setup.enabled(this)) {
            offeredRoot = true
            setupAudio(method = CallAudio.Setup.Method.ROOT)
        }
    }

    private fun openReport() {
        reportParent = detail
        showDetail(Detail.REPORT)
    }

    private fun renderWelcome() {
        content.setPaddingRelative(dp(24), dp(28), dp(24), 0)
        content.addView(brandLockup(welcome = true), space())
        content.addView(sectionTitle(getString(R.string.s_ui_welcome_title), R.style.TextAppearance_ezcall_SenderWelcome)
            .apply { setTextColor(Color.WHITE) }, space(top = 24))
        content.addView(text(getString(R.string.s_ui_welcome_detail), R.style.TextAppearance_ezcall_BodyLarge,
            getColor(R.color.sender_welcome_description)), space(top = 12))
        content.addView(artwork(R.drawable.sender_welcome_art).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, space().apply {
            height = dp(320)
            marginStart = -dp(24)
            marginEnd = -dp(24)
        })
        guideAction.visibility = View.VISIBLE
        guideAction.addView(actionButton(getString(R.string.s_ui_start_guide), ::openGuide).apply {
            minHeight = dp(56)
            minimumHeight = dp(56)
            cornerRadius = dp(28)
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(getColor(R.color.sender_welcome_button_ink))
        }, space())
        guideAction.addView(textButton(getString(R.string.s_ui_skip_setup), ::skipSetup).apply {
            setTextColor(Color.WHITE)
        }, space(top = 8))
    }

    private fun screenHeading(title: Int, description: Int? = null) {
        content.addView(sectionTitle(getString(title), R.style.TextAppearance_ezcall_SenderHeadline), space())
        description?.let { content.addView(body(getString(it)), space(top = 6)) }
    }

    private fun renderAudioPage() {
        if (showTestRun && RelayState.testCall != null) {
            screenHeading(R.string.s_simulate)
            testPanel = stack().also { content.addView(it, space(top = 12)) }
            return
        }
        screenHeading(R.string.s_call_audio)
        audioIntro = body(getString(R.string.s_ui_audio_intro)).also { content.addView(it, space(top = 6)) }
        audioPanel = stack().also { content.addView(it, space(top = 12)) }
        testPanel = stack().also { content.addView(it, space(top = 12)) }
        audioMethodsLink = stack().also { content.addView(it, space(top = 12)) }
    }

    private fun renderPage() {
        connectionPanel = null
        sharingPanel = null
        audioPanel = null
        audioIntro = null
        audioMethodsLink = null
        testPanel = null
        reportPanel = null
        settingsNotifications = null
        settingsBattery = null
        shownAudio = null
        shownTest = null
        shownReport = null
        content.removeAllViews()
        guideAction.removeAllViews()
        guideAction.visibility = View.GONE
        navigation.visibility = if (detail == null) View.VISIBLE else View.GONE
        navigationDivider.visibility = navigation.visibility
        val welcome = detail == Detail.WELCOME
        val guide = detail == Detail.GUIDE
        screen.setBackgroundColor(if (welcome) getColor(R.color.sender_welcome_background) else color(MaterialR.attr.colorSurface))
        WindowCompat.getInsetsController(window, screen).apply {
            val lightBars = !welcome && resources.getBoolean(R.bool.ezcall_light_system_bars)
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        toolbar.visibility = if (welcome) View.GONE else View.VISIBLE
        toolbar.setPaddingRelative(0, 0, if (guide) 0 else dp(20), 0)
        content.clipToPadding = !welcome
        brand.visibility = if (guide) View.GONE else View.VISIBLE
        guideProgress.visibility = if (guide) View.VISIBLE else View.GONE
        toolbar.title = if (guide) getString(R.string.s_guide) else null
        toolbar.setTitleMarginStart(0)
        toolbar.setTitleMarginEnd(if (guide) dp(50) else 0)
        toolbar.navigationIcon = detail?.let { tinted(R.drawable.ic_sender_back) }
        toolbar.setNavigationContentDescription(when {
            detail == Detail.GUIDE && step != Step.SHARING -> R.string.s_guide_back
            detail == Detail.GUIDE -> R.string.s_close_guide
            else -> R.string.s_ui_back
        })
        toolbar.menu.setGroupVisible(0, detail == null || detail == Detail.SETTINGS)
        content.setPaddingRelative(dp(24), dp(if (guide) 0 else 12), dp(24), dp(24))
        content.accessibilityPaneTitle = getString(detail?.title ?: if (page == Page.SHARING) R.string.s_sharing else page.title)
        back.isEnabled = detail != null || page != Page.CONNECTION

        when (detail) {
            Detail.WELCOME -> renderWelcome()
            Detail.GUIDE -> renderGuide()
            Detail.SETTINGS -> renderSettings()
            Detail.SETTINGS_AUDIO -> renderAudioPage()
            Detail.NOTIFICATIONS -> renderPermissionPage(R.string.s_relay_notifications, R.string.s_notifications_in_status) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            Detail.BATTERY -> renderBatteryPage()
            Detail.OVERLAY -> renderPermissionPage(R.string.s_call_overlay, R.string.s_call_overlay_info) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            Detail.REPORT -> {
                screenHeading(R.string.s_test_report)
                reportPanel = stack().also { content.addView(it, space(top = 12)) }
            }
            Detail.AUDIO_SETUP -> {
                screenHeading(R.string.s_ui_audio_setup_methods, R.string.s_ui_audio_methods_intro)
                audioPanel = stack().also { content.addView(it, space(top = 12)) }
            }
            null -> when (page) {
                Page.CONNECTION -> connectionPanel = content
                Page.SHARING -> {
                    screenHeading(R.string.s_sharing, R.string.s_ui_sharing_intro)
                    sharingPanel = stack().also { content.addView(it, space(top = 12)) }
                }
                Page.AUDIO -> renderAudioPage()
            }
        }
        scroll.scrollTo(0, 0)
        renderSharing()
        renderLink(RelayState.current)
        renderAudio()
        renderTest()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        requestingFeature?.let { outState.putString("feature", it.name) }
        setupAudioReady?.let { outState.putString("setupAudioReady", it.name) }
        setupAudioError?.let { outState.putInt("setupAudioError", it) }
        connectionIssue?.let { outState.putInt("connectionIssue", it) }
        outState.putBoolean("pairingAgain", pairingAgain)
        pairingHint?.let { outState.putString("pairingHint", it) }
        outState.putBoolean("showTestRun", showTestRun)
        outState.putInt("page", page.ordinal)
        detail?.let { outState.putString("detail", it.name) }
        outState.putBoolean("guideFromSettings", guideFromSettings)
        audioSetupParent?.let { outState.putString("audioSetupParent", it.name) }
        reportParent?.let { outState.putString("reportParent", it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        RelayState.listener = ::render
        val state = RelayState.current
        render(state)
        if (!state.running && state.detail == null && PairKey(this).exists() &&
            RelayService.hasBluetoothPermissions(this)) startRelay()
    }

    override fun onResume() {
        super.onResume()
        renderSharing()
        RelayState.refreshSharing?.invoke()
        lastState = null
        render(RelayState.current)
    }

    override fun onStop() {
        if (RelayState.listener == ::render) RelayState.listener = null
        super.onStop()
    }

    override fun onPause() {
        support.dismissReminder()
        super.onPause()
    }

    override fun onDestroy() {
        cancelUsbSetup?.invoke()
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        toolbar.removeCallbacks(startTimeout)
        super.onDestroy()
    }

    private fun render(state: RelayState) {
        if (relayStarting && state != relayBeforeStart) {
            relayStarting = false
            relayBeforeStart = null
            toolbar.removeCallbacks(startTimeout)
        }
        val changed = lastState != state
        lastState = state
        if (pairingAgain && !state.running) {
            pairingAgain = false
            pairingHint = state.detail
            if (!PairKey(this).exists()) ensureBluetooth()
        }
        if (!state.connected) testRequestFailed = false
        if (state.connected) {
            pairingHint = null
            connectionIssue = null
        }
        if (state.connected && detail == Detail.GUIDE && step == Step.LINK) {
            moveTo(Step.AUDIO)
            return
        }
        if (liveAudio() != null) setupAudioError = null
        renderSettingsStatus()
        renderAudio()
        renderTest()
        if (changed) renderLink(state)
    }

    private fun renderLink(state: RelayState) {
        val panel = connectionPanel ?: return
        val inGuide = detail == Detail.GUIDE
        val paired = PairKey(this).exists()
        val qr = state.qr
        if (qr == null) {
            qrValue = null
            qrImage = null
        }
        panel.removeAllViews()
        if (inGuide && qr != null) {
            renderGuideQr(panel, qr)
            return
        }
        val welcome = !inGuide && !paired && !state.running && !relayStarting && state.detail == null && connectionIssue == null
        if (!inGuide) screenHeading(R.string.s_connection, R.string.s_ui_connection_intro.takeIf { welcome })
        val expired = state.detail == "Pairing code expired. Start pairing again."
        val issue = connectionIssue?.let(::getString) ?: state.detail?.let(::senderNotice)
        val title = when {
            relayStarting || state.detail == "Starting Bluetooth" -> getString(R.string.s_relay_start_bt)
            qr != null -> getString(R.string.s_scan_to_pair)
            state.connected -> getString(R.string.s_receiver_connected)
            expired -> getString(R.string.s_ui_qr_expired)
            state.running -> getString(R.string.s_waiting_receiver)
            paired -> getString(R.string.s_receiver_disconnected)
            welcome -> getString(R.string.ez_pair_phones)
            else -> getString(R.string.s_no_receiver)
        }
        val description = when {
            qr != null -> getString(R.string.s_scan_instruction)
            state.connected -> getString(if (state.tested) R.string.ez_test_passed else R.string.s_relay_active)
            relayStarting -> getString(R.string.s_nearby)
            issue != null && issue != title -> issue
            state.running -> getString(R.string.s_nearby)
            paired -> getString(R.string.s_reconnect_detail)
            welcome -> getString(R.string.s_nearby)
            else -> getString(R.string.s_start_link)
        }
        val foreground = if (state.connected) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface
        val card = stack().apply {
            setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            if (!inGuide || state.running || relayStarting || issue != null) {
                if (relayStarting) {
                    addView(spinner(), LinearLayout.LayoutParams(-2, -2))
                } else {
                    addView(iconTile(if (qr != null) R.drawable.ic_qr else R.drawable.ic_bluetooth),
                        LinearLayout.LayoutParams(dp(44), dp(44)))
                }
                addView(text(title, R.style.TextAppearance_ezcall_TitleLarge, color(foreground)).apply {
                    isAccessibilityHeading = true
                }, space(top = 14))
            }
            addView(text(description, R.style.TextAppearance_ezcall_BodyMedium,
                color(if (state.connected) foreground else if (issue != null && !state.running && qr == null)
                    androidx.appcompat.R.attr.colorError else MaterialR.attr.colorOnSurfaceVariant)),
                space(top = 8))
            if (welcome) {
                addView(artwork(R.drawable.sender_connection_art).apply {
                    background = rounded(getColor(R.color.ezcall_pairing_hero), 22)
                    scaleType = ImageView.ScaleType.CENTER
                    clipToOutline = true
                }, space(top = 12).apply { height = dp(136) })
            }
            if (qr != null) {
                pairingHint?.let { addView(secondaryText(senderNotice(it)), space(top = 12)) }
                addView(qrView(qr), centered(top = 16))
                addView(textButton(getString(R.string.ez_cancel_pairing)) { RelayState.cancelPairing?.invoke() }, space(top = 8))
            }
            if (!inGuide && qr == null && !state.running && !relayStarting) {
                val action = when {
                    welcome && step != null -> R.string.s_ui_resume_guide
                    welcome -> R.string.s_ui_start_guide
                    else -> linkAction(paired, expired)
                }
                addView(actionButton(getString(action)) {
                    if (welcome) openGuide() else ensureBluetooth()
                }, space(top = 14))
            }
        }
        if (!inGuide || paired || state.running || relayStarting || issue != null) {
            panel.addView(surfaceCard(card, accent = state.connected).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, space(top = if (inGuide) 0 else 12))
        }
        if (inGuide) {
            if (state.running && !state.connected) {
                guideAction.removeAllViews()
                guideAction.visibility = View.GONE
            } else {
                val action = when {
                    state.connected -> R.string.s_guide_audio_next
                    relayStarting -> R.string.s_relay_start_bt
                    else -> linkAction(paired, expired)
                }
                setGuideAction(action, canContinue = !relayStarting) {
                    if (state.connected) moveTo(Step.AUDIO) else ensureBluetooth()
                }
            }
        } else if (!welcome) {
            val guideLabel = if (step != null) R.string.s_ui_resume_guide else R.string.s_guide
            panel.addView(surfaceCard(settingsRow(R.drawable.ic_devices, getString(guideLabel),
                getString(R.string.s_reopen_guide), action = ::openGuide).view), space(top = 16))
            if (paired) panel.addView(textButton(getString(R.string.s_forget_receiver), ::forgetLink), space(top = 8))
        }
    }

    private fun renderGuideQr(panel: LinearLayout, qr: String) {
        pairingHint?.let { panel.addView(body(senderNotice(it)), space()) }
        panel.addView(qrView(qr), centered(top = 24))
        panel.addView(row().apply {
            gravity = Gravity.CENTER
            addView(View(this@MainActivity).apply {
                background = rounded(color(androidx.appcompat.R.attr.colorPrimary), 4)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
            addView(text(getString(R.string.s_ui_guide_qr_expiry), R.style.TextAppearance_ezcall_LabelMedium,
                color(MaterialR.attr.colorOnSurfaceVariant)))
        }, space(top = 12))
        panel.addView(guideNotice(R.drawable.ic_bluetooth, R.string.s_ui_pairing_nearby, R.string.s_ui_pairing_bluetooth),
            space(top = 32))
        guideAction.removeAllViews()
        guideAction.visibility = View.VISIBLE
        guideAction.addView(textButton(getString(R.string.ez_cancel_pairing), ::cancelGuidePairing), space())
    }

    private fun cancelGuidePairing() {
        moveTo(Step.SHARING)
        RelayState.cancelPairing?.invoke()
    }

    private fun linkAction(paired: Boolean, expired: Boolean) = when {
        paired -> R.string.s_reconnect
        expired -> R.string.s_ui_new_qr
        else -> R.string.s_show_qr
    }

    private fun forgetLink() {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.s_forget_receiver_title))
            .setMessage(getString(R.string.s_forget_receiver_detail))
            .setNegativeButton(getString(R.string.ez_cancel), null)
            .setPositiveButton(getString(R.string.ez_forget)) { _, _ ->
                saveStep(null)
                val forget = RelayState.forget
                if (forget != null) {
                    pairingAgain = true
                    forget()
                } else if (runCatching { PairKey(this).forget() }.isSuccess) {
                    pairingHint = "If the receiving phone still shows the old link, forget this phone there before scanning."
                    lastState = null
                    render(RelayState.current)
                    ensureBluetooth()
                } else alert(getString(R.string.s_erase_failed))
            }.show()
    }

    private fun renderSharing() {
        val panel = sharingPanel ?: return
        panel.removeAllViews()
        val callsEnabled = Feature.CALLS.active(this)
        val sections = listOf(
            R.string.s_ui_calls_group to listOf(Feature.CALLS, Feature.CALLER, Feature.CONTROL),
            R.string.s_ui_messages_group to listOf(Feature.SMS, Feature.SEND_SMS),
            R.string.s_ui_contacts_group to listOf(Feature.CONTACTS)
        )
        for ((heading, features) in sections) {
            panel.addView(groupLabel(heading), space(top = if (panel.childCount == 0) 0 else 12))
            val group = stack()
            for (feature in features) {
                if (group.childCount > 0) group.addView(divider(), space())
                val enabled = callsEnabled || (feature != Feature.CALLER && feature != Feature.CONTROL)
                group.addView(sharingRow(feature, enabled), space())
            }
            panel.addView(surfaceCard(group), space(top = 6))
        }
    }

    private fun sharingRow(feature: Feature, enabled: Boolean): View {
        val (icon, description) = when (feature) {
            Feature.CALLS -> R.drawable.ic_call to R.string.s_incoming_call_alert
            Feature.CALLER -> R.drawable.ic_caller to R.string.s_incoming_number
            Feature.CONTROL -> R.drawable.ic_answer to R.string.s_control_description
            Feature.SMS -> R.drawable.ic_sms to R.string.s_sms_description
            Feature.SEND_SMS -> R.drawable.ic_sender_send_sms to R.string.s_send_description
            Feature.CONTACTS -> R.drawable.ic_contacts to R.string.s_contacts_description
        }
        val title = getString(feature.title)
        val active = enabled && feature.active(this)
        val summary = when {
            !enabled -> getString(R.string.s_ui_enable_alerts)
            feature.optedIn(this) && !active -> getString(R.string.s_ui_permission_needed)
            else -> getString(description)
        }
        val toggle = MaterialSwitch(this).apply {
            contentDescription = getString(R.string.s_feature_summary, title, summary)
            isEnabled = enabled
            isChecked = active
            minimumWidth = dp(52)
            minimumHeight = dp(48)
            setOnClickListener {
                if (isChecked) {
                    isChecked = false
                    enable(feature)
                } else {
                    feature.allow(this@MainActivity, false)
                    refreshSharing()
                }
            }
        }
        return row().apply {
            minimumHeight = dp(59)
            setPaddingRelative(dp(12), dp(5), dp(12), dp(5))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (enabled) {
                setBackgroundResource(selectableBackground())
                setOnClickListener { toggle.performClick() }
                isFocusable = false
            }
            if (!largeText) {
                addView(iconTile(icon, size = 34), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
            }
            addView(stack().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(title, R.style.TextAppearance_ezcall_TitleMedium))
                addView(text(if (!enabled) getString(description) else summary, R.style.TextAppearance_ezcall_BodySmall,
                    color(MaterialR.attr.colorOnSurfaceVariant)))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(toggle, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun openGuide() {
        guideFromSettings = detail == Detail.SETTINGS
        val next = if (step == Step.LINK && PairKey(this).exists()) Step.AUDIO else step ?: Step.SHARING
        saveStep(next)
        showDetail(Detail.GUIDE)
        if (next == Step.LINK && !RelayState.current.running) ensureBluetooth()
    }

    private fun skipSetup() {
        saveStep(null)
        showDetail(null)
    }

    private fun closeGuide() {
        val backTo = Detail.SETTINGS.takeIf { guideFromSettings }
        guideFromSettings = false
        showDetail(backTo)
    }

    private fun saveStep(next: Step?) {
        step = next
        getPreferences(MODE_PRIVATE).edit().putString("senderGuideStep", step?.name ?: "done").apply()
    }

    private fun moveTo(next: Step) {
        saveStep(next)
        renderPage()
    }

    private fun renderGuide() {
        val current = checkNotNull(step)
        guideProgress.text = getString(R.string.s_ui_guide_progress, current.ordinal + 1, Step.entries.size)
        content.addView(row().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            for (segment in Step.entries) {
                addView(View(this@MainActivity).apply {
                    val tint = if (segment.ordinal <= current.ordinal) androidx.appcompat.R.attr.colorPrimary
                        else MaterialR.attr.colorOutlineVariant
                    background = rounded(color(tint), 2)
                }, LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                    if (segment != Step.SHARING) marginStart = dp(4)
                })
            }
        }, space())
        if (current == Step.AUDIO && PairKey(this).exists()) {
            content.addView(guideNotice(R.drawable.ic_sender_check, R.string.s_ui_guide_paired), space(top = 26))
        }
        val title = getString(current.title)
        val helpLabel = getString(R.string.s_ui_guide_help_for, title)
        content.addView(row().apply {
            addView(sectionTitle(title, R.style.TextAppearance_ezcall_SenderHeadline).apply {
                if (current == Step.SHARING) textSize = 30f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(headerButton(R.drawable.ic_sender_help, helpLabel) { showGuideHelp(current) },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) })
        }, space(top = 16))
        content.addView(if (current == Step.SHARING) body(getString(current.detail))
            else secondaryText(getString(current.detail)), space(top = 6))
        when (current) {
            Step.SHARING -> {
                sharingPanel = stack().also { content.addView(it, space(top = 16)) }
                val next = Step.afterSharing(PairKey(this).exists())
                setGuideAction(if (next == Step.AUDIO) R.string.ez_continue else R.string.s_ui_continue_pairing) {
                    moveTo(next)
                    if (next == Step.LINK && !RelayState.current.running) ensureBluetooth()
                }
            }
            Step.LINK -> connectionPanel = stack().also { content.addView(it, space(top = 20)) }
            Step.AUDIO -> {
                val configured = CallAudio.Setup.enabled(this) || liveAudio() != null
                if (!configured) content.addView(body(getString(R.string.s_ui_finish_later)), space(top = 8))
                content.addView(AudioGuideArtwork(this, color(androidx.appcompat.R.attr.colorPrimary),
                    color(MaterialR.attr.colorOutlineVariant), color(MaterialR.attr.colorOnSurface)),
                    space(top = 24).apply { height = dp(174) })
                val finishSetup = {
                    saveStep(null)
                    closeGuide()
                }
                if (configured) {
                    setGuideAction(R.string.s_ui_finish_guide, action = finishSetup)
                    guideAction.addView(textButton(getString(R.string.s_ui_audio_setup_methods), ::openAudioSetup), space(top = 8))
                } else {
                    setGuideAction(R.string.s_setup_audio, action = ::openAudioSetup)
                    guideAction.addView(textButton(getString(R.string.s_ui_finish_guide), finishSetup), space(top = 8))
                }
            }
        }
    }

    private fun setGuideAction(label: Int, canContinue: Boolean = true, action: () -> Unit) {
        guideAction.removeAllViews()
        guideAction.visibility = View.VISIBLE
        guideAction.addView(actionButton(getString(label), action = action).apply { isEnabled = canContinue }, space())
    }

    private fun showGuideHelp(step: Step) {
        val title = when (step) {
            Step.SHARING -> R.string.s_ui_help_sharing_title
            Step.LINK -> R.string.s_ui_help_pairing_title
            Step.AUDIO -> R.string.s_ui_help_audio_title
        }
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(ScrollView(this).apply {
            addView(stack().apply {
                setPaddingRelative(dp(24), dp(12), dp(24), dp(24))
                addView(View(this@MainActivity).apply {
                    background = rounded(color(MaterialR.attr.colorOutline), 2)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, centered(top = 0).apply { width = dp(34); height = dp(4) })
                addView(row().apply {
                    addView(sectionTitle(getString(title), R.style.TextAppearance_ezcall_TitleLarge),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    val close = headerButton(R.drawable.ic_sender_close, getString(R.string.ez_close), dialog::dismiss)
                    addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
                }, space(top = 6))
                addView(body(getString(step.help)), space(top = 12))
                addView(actionButton(getString(R.string.s_ui_help_done), dialog::dismiss), space(top = 32))
            })
        })
        dialog.show()
    }

    private data class TestDisplay(val report: TestCallReport?, val canTest: Boolean, val requestFailed: Boolean)

    private fun closeTestRun() {
        showTestRun = false
        renderPage()
    }

    private fun renderTest() {
        val test = RelayState.testCall
        testPanel?.let { panel ->
            val display = TestDisplay(test, RelayState.current.connected && RelayState.simulateCall != null, testRequestFailed)
            if (shownTest != display) {
                shownTest = display
                panel.removeAllViews()
                val card = stack().apply { setPaddingRelative(dp(16), dp(16), dp(16), dp(16)) }
                if (showTestRun && test != null) {
                    val waiting = test.stage == TestCallReport.Stage.STARTING || test.stage == TestCallReport.Stage.RINGING
                    card.addView(iconTile(R.drawable.ic_headset), LinearLayout.LayoutParams(dp(44), dp(44)))
                    val heading = when {
                        waiting -> R.string.s_test_waiting_receiver
                        test.verified -> R.string.ez_test_auto_passed
                        else -> test.stage.label
                    }
                    card.addView(sectionTitle(getString(heading), R.style.TextAppearance_ezcall_TitleLarge).apply {
                        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }, space(top = 12))
                    if (test.inProgress) card.addView(body(getString(R.string.s_simulate_wait)), space(top = 8))
                    panel.addView(surfaceCard(card, accent = test.verified), space())
                    panel.addView(surfaceCard(settingsRow(R.drawable.ic_sender_check, getString(R.string.s_test_report),
                        getString(R.string.s_test_report_detail), action = ::openReport).view), space(top = 12))
                    panel.addView(textButton(getString(R.string.s_ui_back_to_audio), ::closeTestRun), space(top = 12))
                    return
                }
                card.addView(sectionTitle(getString(R.string.s_simulate), R.style.TextAppearance_ezcall_TitleLarge))
                val state = when {
                    display.requestFailed -> R.string.s_simulate_retry
                    test?.inProgress == true -> test.stage.label
                    display.canTest -> R.string.s_ui_test_ready
                    else -> R.string.s_ui_test_connect
                }
                card.addView(body(getString(state)).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    if (display.requestFailed) setTextColor(color(androidx.appcompat.R.attr.colorError))
                }, space(top = 6))
                card.addView(actionButton(getString(R.string.s_ui_start_test)) {
                    if (RelayState.simulateCall?.invoke() == true) {
                        testRequestFailed = false
                        showTestRun = true
                        renderPage()
                    } else {
                        testRequestFailed = true
                        renderTest()
                    }
                }.apply { isEnabled = display.canTest && test?.inProgress != true }, space(top = 12))
                if (test != null) card.addView(outlineButton(getString(R.string.s_test_report), ::openReport), space(top = 4))
                panel.addView(surfaceCard(card), space())
            }
        }
        val panel = reportPanel ?: return
        if (panel.childCount > 0 && shownReport == test) return
        shownReport = test
        panel.removeAllViews()
        if (test == null) {
            panel.addView(sectionTitle(getString(R.string.s_ui_no_test), R.style.TextAppearance_ezcall_TitleLarge))
            if (!RelayState.current.connected) {
                panel.addView(secondaryText(getString(R.string.s_ui_test_connect)), space(top = 6))
            }
            panel.addView(actionButton(getString(R.string.s_ui_back_to_audio), ::navigateBack), space(top = 16))
            return
        }
        val outcome = when {
            test.verified -> R.string.ez_test_auto_passed
            test.stage == TestCallReport.Stage.FINISHED -> R.string.ez_test_auto_incomplete
            else -> test.stage.label
        }
        val report = row().apply {
            setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            addView(iconTile(R.drawable.ic_bluetooth), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
            addView(stack().apply {
                val titleInk = if (test.verified) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface
                addView(sectionTitle(getString(outcome), R.style.TextAppearance_ezcall_TitleMedium).apply {
                    setTextColor(color(titleInk))
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                })
                val detail = when {
                    test.playbackConfirmed -> R.string.ez_test_tone_played
                    test.inProgress -> R.string.s_simulate_wait
                    test.audioStarted -> R.string.s_test_playback_unconfirmed
                    else -> null
                }
                detail?.let { addView(body(getString(it)), space(top = 4)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(surfaceCard(report, accent = test.verified), space())
        if (!test.audioStarted) return
        val live = test.stage == TestCallReport.Stage.AUDIO
        RelayState.testLog?.let { log ->
            val ink = color(androidx.appcompat.R.attr.colorPrimary)
            panel.addView(body(getString(R.string.ez_test_sent_tone_wave)), space(top = 12))
            panel.addView(AudioWaveformView(this, log.localWaveform, ink, live).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, space().apply { height = dp(38) })
            panel.addView(body(getString(R.string.ez_test_remote_mic_wave)), space(top = 8))
            panel.addView(AudioWaveformView(this, log.remoteWaveform, ink, live).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, space().apply { height = dp(38) })
        }
        panel.addView(body(getString(if (test.microphoneReceived) R.string.s_test_mic_received else R.string.s_test_mic_missing)), space(top = 8))
        panel.addView(body(getString(if (test.echoQueued) R.string.s_test_echo_queued else R.string.s_test_echo_missing)), space(top = 8))
    }

    private fun renderSettings() {
        screenHeading(R.string.ez_settings)
        content.addView(groupLabel(R.string.s_ui_app_group), space(top = 12))
        val language = settingsRow(R.drawable.ic_language, getString(R.string.ez_language), languageName()) {
            showEzcallLanguage(getString(R.string.ez_language))
        }
        content.addView(surfaceCard(language.view), space(top = 8))
        content.addView(surfaceCard(settingsRow(R.drawable.ic_devices,
            getString(if (step == null) R.string.s_guide else R.string.s_ui_resume_guide),
            getString(R.string.s_reopen_guide), action = ::openGuide).view), space(top = 8))
        content.addView(surfaceCard(settingsRow(R.drawable.ic_headset, getString(R.string.s_call_audio),
            getString(R.string.s_ui_settings_audio)) { showDetail(Detail.SETTINGS_AUDIO) }.view), space(top = 12))
        content.addView(groupLabel(R.string.s_relay), space(top = 12))
        settingsNotifications = settingsRow(R.drawable.ic_notifications, getString(R.string.s_relay_notifications), "") {
            showDetail(Detail.NOTIFICATIONS)
        }.also {
            content.addView(surfaceCard(it.view), space(top = 8))
        }
        settingsBattery = settingsRow(R.drawable.ic_battery, getString(R.string.s_battery_action), "",
            action = { showDetail(Detail.BATTERY) }).also { content.addView(surfaceCard(it.view), space(top = 8)) }
        content.addView(surfaceCard(settingsRow(R.drawable.ic_support, getString(R.string.ez_support_title),
            getString(R.string.ez_support_optional), action = ::openSupport).view), space(top = 12))
        renderSettingsStatus()
    }

    private fun renderPermissionPage(title: Int, description: Int, action: () -> Unit) {
        screenHeading(title, description)
        content.addView(actionButton(getString(R.string.s_ok), action), space(top = 12))
    }

    private fun openSupport() = support.open(R.layout.support_wallets)

    private fun renderBatteryPage() {
        renderPermissionPage(R.string.s_battery_action, R.string.s_battery_help, ::requestBatteryExemption)
        if (!XiaomiBackground.applies()) return
        content.addView(body(getString(R.string.s_xiaomi_background_help)), space(top = 16))
        content.addView(actionButton(getString(R.string.s_xiaomi_autostart)) {
            openXiaomiSetting(XiaomiBackground.autostart())
        }, space(top = 12))
        content.addView(actionButton(getString(R.string.s_xiaomi_battery_saver)) {
            openXiaomiSetting(XiaomiBackground.batterySaver(packageName, getString(R.string.ez_brand)))
        }, space(top = 8))
    }

    private fun openXiaomiSetting(intent: Intent) {
        if (runCatching { startActivity(intent) }.isFailure) appSettings(getString(R.string.s_xiaomi_background_help))
    }

    private fun renderSettingsStatus() {
        settingsNotifications?.detail?.text = getString(if (notificationsEnabled()) R.string.s_allowed else R.string.s_android_off)
        settingsBattery?.detail?.text = getString(if (batteryExempt()) R.string.s_battery_off else R.string.s_battery_may_pause)
    }

    private fun notificationsEnabled() = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        if (batteryExempt()) {
            appSettings(getString(R.string.s_battery_already_off))
            return
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(request) }.isFailure) {
            appSettings(getString(R.string.s_battery_help))
        }
    }

    private fun showWirelessInstructions() {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.s_pair_wireless))
            .setMessage(getString(R.string.s_wireless_instructions))
            .setNegativeButton(getString(R.string.ez_cancel), null)
            .setPositiveButton(getString(R.string.s_open_dev)) { _, _ -> startWirelessPairing() }
            .show()
    }

    private fun liveAudio(): CallAudio.Status? = RelayState.audio?.takeUnless { it == CallAudio.Status.NONE }
        ?: setupAudioReady?.takeUnless { RelayState.current.running }

    private fun audioTitle(): String = when {
        audioBusy -> getString(R.string.s_starting_audio)
        liveAudio() == CallAudio.Status.TWO_WAY -> getString(R.string.s_two_way)
        liveAudio() == CallAudio.Status.LISTEN -> getString(R.string.s_listen_only)
        CallAudio.Setup.enabled(this) && RelayState.current.running -> getString(R.string.s_audio_stopped)
        CallAudio.Setup.enabled(this) -> getString(R.string.s_audio_relay_start)
        else -> getString(R.string.s_audio_not_setup)
    }

    private fun uplinkResult(): UplinkHealth.Result? = UplinkHealth.load(this)?.result

    private data class AudioDisplay(
        val title: String,
        val active: CallAudio.Status?,
        val configured: Boolean,
        val busy: Boolean,
        val relayRunning: Boolean,
        val paired: Boolean,
        val canStart: Boolean,
        val method: CallAudio.Setup.Method?,
        val overlayEnabled: Boolean,
        val error: Int?,
        val rootGranted: Boolean,
        val uplink: UplinkHealth.Result?,
        val reporting: Boolean,
        val normalCallMode: Boolean
    ) {
        val shizuku get() = method == CallAudio.Setup.Method.SHIZUKU
    }

    private fun renderAudio() {
        val panel = audioPanel ?: return
        val paired = WirelessPairing.isPaired(this)
        val configured = CallAudio.Setup.enabled(this)
        val active = liveAudio()
        val display = AudioDisplay(audioTitle(), active, configured, audioBusy, RelayState.current.running, paired,
            paired || (configured && LocalAdb.hasKey()),
            CallAudio.Setup.method(this), Settings.canDrawOverlays(this),
            setupAudioError, rootGranted, uplinkResult(), audioReporting, CallAudio.Setup.normalCallMode(this))
        if (shownAudio == display) return
        shownAudio = display
        val info = when {
            display.busy && startingRoot -> if (display.rootGranted) R.string.s_audio_root_starting else R.string.s_audio_root_request
            display.busy -> R.string.s_audio_keep_wireless
            display.active == CallAudio.Status.TWO_WAY && display.uplink == UplinkHealth.Result.REACHED ->
                R.string.s_uplink_reached_info
            display.active == CallAudio.Status.TWO_WAY -> R.string.s_audio_two_way_info
            display.active == CallAudio.Status.LISTEN -> R.string.s_audio_listen_info
            display.configured && display.shizuku && display.relayRunning -> R.string.s_audio_shizuku_detail
            display.configured && display.method == CallAudio.Setup.Method.ROOT && display.relayRunning ->
                R.string.s_audio_root_detail
            display.configured && display.relayRunning -> R.string.s_audio_restart_info
            display.configured -> R.string.s_audio_resume_info
            else -> R.string.s_audio_setup_info
        }
        panel.removeAllViews()
        val methods = detail == Detail.AUDIO_SETUP
        val ready = display.active != null && !display.busy && display.error == null
        audioIntro?.visibility = if (ready) View.GONE else View.VISIBLE
        audioMethodsLink?.apply {
            removeAllViews()
            visibility = if (ready) View.VISIBLE else View.GONE
            if (ready) addView(surfaceCard(settingsRow(R.drawable.ic_headset, getString(R.string.s_ui_ready_methods),
                getString(R.string.s_ui_ready_methods_detail), action = ::openAudioSetup).view), space())
        }
        val state = stack().apply {
            setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            if (display.busy) addView(LinearProgressIndicator(this@MainActivity).apply {
                trackThickness = dp(4)
                trackCornerRadius = dp(2)
                isIndeterminate = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, space())
            else addView(row().apply {
                addView(iconTile(R.drawable.ic_headset), LinearLayout.LayoutParams(dp(44), dp(44)))
                if (display.active != null && display.error == null) {
                    val methodName = when (display.method) {
                        CallAudio.Setup.Method.ROOT -> R.string.s_audio_root_name
                        CallAudio.Setup.Method.SHIZUKU -> R.string.s_ui_shizuku_name
                        CallAudio.Setup.Method.WIRELESS -> R.string.s_ui_wireless_method
                        CallAudio.Setup.Method.USB -> R.string.s_ui_usb_debugging
                        null -> null
                    }
                    methodName?.let {
                        val label = getString(it)
                        addView(android.widget.Space(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
                        addView(text(label, R.style.TextAppearance_ezcall_LabelMedium).apply {
                            gravity = Gravity.CENTER
                            contentDescription = getString(R.string.s_ui_audio_configured_with, label)
                            background = rounded(color(MaterialR.attr.colorSurface), 20)
                            setPaddingRelative(dp(12), dp(6), dp(12), dp(6))
                        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                    }
                }
            }, space())
            val title = if (display.error == R.string.s_audio_shizuku_failed || display.error == R.string.s_audio_root_failed)
                getString(R.string.s_ui_audio_unavailable)
                else display.title
            addView(sectionTitle(title, R.style.TextAppearance_ezcall_TitleLarge),
                space(top = 10))
            addView(body(getString(display.error ?: info)).apply {
                if (display.error != null) setTextColor(color(androidx.appcompat.R.attr.colorError))
            }, space(top = 4))
            if (!methods && !ready) addView(actionButton(getString(R.string.s_ui_audio_setup_methods), ::openAudioSetup), space(top = 12))
        }
        if (!methods || display.active != null || display.busy || display.error != null) {
            panel.addView(surfaceCard(state, accent = ready).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, space())
        }
        if (!methods) {
            if (display.configured) {
                addAudioMode(panel, display)
                addAudioReport(panel, display)
            }
            return
        }
        if (offersRoot) {
            panel.addView(surfaceCard(settingsRow(R.drawable.ic_link, getString(R.string.s_audio_root_name),
                getString(R.string.s_audio_root_detail), enabled = !display.busy) {
                setupAudio(method = CallAudio.Setup.Method.ROOT)
            }.view), space(top = if (panel.childCount == 0) 0 else 12))
        }

        panel.addView(surfaceCard(settingsRow(R.drawable.ic_link, getString(if (display.shizuku) R.string.s_audio_shizuku_restart
            else R.string.s_audio_shizuku), getString(R.string.s_audio_shizuku_detail),
            enabled = !display.busy, action = ::setupShizukuAudio).view), space(top = if (panel.childCount == 0) 0 else 12))
        panel.addView(surfaceCard(settingsRow(R.drawable.ic_link, getString(R.string.s_ui_wireless_method),
                getString(if (display.paired) R.string.s_wireless_paired else R.string.s_ui_wireless_method_detail),
                enabled = !display.busy) {
                if (display.canStart) setupAudio() else showWirelessInstructions()
            }.view), space(top = 12))
        if (display.canStart) {
            panel.addView(textButton(getString(R.string.s_pair_wireless), ::showWirelessInstructions)
                .apply { isEnabled = !display.busy }, space())
        }
        if (display.error == R.string.s_audio_port_missing || display.error == R.string.s_audio_failure) {
            panel.addView(surfaceCard(settingsRow(R.drawable.ic_bluetooth, getString(R.string.s_audio_enter_port),
                getString(R.string.s_audio_port_help), enabled = !display.busy, action = ::showAudioPort).view), space(top = 12))
        }
        panel.addView(surfaceCard(settingsRow(R.drawable.ic_devices, getString(R.string.s_ui_usb_method),
            getString(R.string.s_audio_usb_detail), enabled = !display.busy, action = ::setupUsbAudio).view), space(top = 12))
        addAudioReport(panel, display)
        panel.addView(groupLabel(R.string.s_ui_call_controls_group), space(top = 12))
        panel.addView(surfaceCard(settingsRow(R.drawable.ic_answer, getString(R.string.s_call_control),
            getString(R.string.s_ui_call_control_detail)) {
            if (audioSetupFromGuide) {
                audioSetupParent = null
                detail = Detail.GUIDE
                moveTo(Step.SHARING)
            } else {
                page = Page.SHARING
                detail = null
                navigation.selectedItemId = page.ordinal + 1
                renderPage()
            }
        }.view), space(top = 12))
        panel.addView(surfaceCard(settingsRow(R.drawable.ic_call, getString(R.string.s_call_overlay),
            getString(R.string.s_call_overlay_info)) {
            showDetail(Detail.OVERLAY)
        }.view, accent = display.overlayEnabled), space(top = 12))
    }

    private fun addAudioMode(panel: LinearLayout, display: AudioDisplay) {
        val current = getString(if (display.normalCallMode) R.string.s_audio_mode_normal else R.string.s_audio_mode_auto)
        panel.addView(surfaceCard(settingsRow(R.drawable.ic_call, getString(R.string.s_audio_mode), current,
            enabled = !display.busy, action = ::chooseAudioMode).view), space(top = if (panel.childCount == 0) 0 else 12))
        panel.addView(body(getString(R.string.s_audio_mode_help)), space(top = 8))
    }

    private fun chooseAudioMode() {
        val normal = CallAudio.Setup.normalCallMode(this)
        MaterialAlertDialogBuilder(this).setTitle(R.string.s_audio_mode)
            .setSingleChoiceItems(arrayOf(getString(R.string.s_audio_mode_auto), getString(R.string.s_audio_mode_normal)),
                if (normal) 1 else 0) { dialog, which ->
                CallAudio.Setup.setNormalCallMode(this, which == 1)
                dialog.dismiss()
                renderAudio()
            }
            .setNegativeButton(R.string.ez_cancel, null)
            .show()
    }

    private fun addAudioReport(panel: LinearLayout, display: AudioDisplay) {
        panel.addView(surfaceCard(settingsRow(R.drawable.ic_sender_help, getString(R.string.s_audio_report),
            getString(R.string.s_audio_report_detail), enabled = !display.busy && !display.reporting,
            action = ::sendAudioReport).view), space(top = if (panel.childCount == 0) 0 else 12))
    }

    private fun sendAudioReport() {
        if (audioReporting) return
        audioReporting = true
        renderAudio()
        val context = applicationContext
        Thread {
            val report = audioReport(context)
            runOnUiThread {
                audioReporting = false
                if (isDestroyed) return@runOnUiThread
                renderAudio()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "ezcall audio report")
                    .putExtra(Intent.EXTRA_TEXT, report)
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.s_audio_report))) }
            }
        }.start()
    }

    /** Device and call audio details for support. Holds no phone numbers, contacts or messages. */
    private fun audioReport(context: Context): String {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val uplink = UplinkHealth.load(context)
        val component = runCatching { CallAudio.Setup.diagnostics(context) }.getOrNull()
        return buildString {
            appendLine("ezcall Sender $version audio report")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Setup: method=${CallAudio.Setup.method(context)} enabled=${CallAudio.Setup.enabled(context)} " +
                "mode=${if (CallAudio.Setup.normalCallMode(context)) "normal" else "automatic"} " +
                "status=${RelayState.audio} relay=${RelayState.current.running} connected=${RelayState.current.connected}")
            appendLine("Last real-call check: " + (uplink?.let {
                "${it.result} in ${if (it.redirect) "redirect" else "in-call"} mode at ${java.util.Date(it.at)}"
            } ?: "none on this Android build"))
            appendLine()
            append(component ?: "Call audio component isn't running. Start call audio, then send the report again.")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        renderAudio()
        if (detail == null && page == Page.CONNECTION && step == null && PairKey(this).exists() &&
            RelayState.testCall?.inProgress != true &&
            getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL &&
            (!hasPermission(permission.READ_PHONE_STATE) ||
                runCatching { getSystemService(TelecomManager::class.java).isInCall }.getOrNull() == false)) {
            support.remind(R.string.ez_support_prompt, R.string.ez_support_action, android.R.string.cancel, ::openSupport)
        }
    }

    private fun enable(feature: Feature) {
        val missing = feature.permissions.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            feature.allow(this, true)
            refreshSharing()
            return
        }
        val description = when (feature) {
            Feature.CALLS -> getString(R.string.s_share_status)
            Feature.CALLER -> getString(R.string.s_share_caller)
            Feature.CONTROL -> getString(R.string.s_share_control)
            Feature.SMS -> getString(R.string.s_share_text)
            Feature.SEND_SMS -> getString(R.string.s_share_send)
            Feature.CONTACTS -> getString(R.string.s_share_contacts)
        }
        MaterialAlertDialogBuilder(this).setTitle(feature.title).setMessage(description)
            .setNegativeButton(getString(R.string.ez_cancel)) { _, _ -> renderSharing() }
            .setOnCancelListener { renderSharing() }
            .setPositiveButton(getString(R.string.ez_continue)) { _, _ ->
                requestingFeature = feature
                requestPermissions(missing.toTypedArray(), SHARE)
            }.show()
    }

    private fun showAudioPort() {
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            minimumHeight = dp(48)
        }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.s_audio_port)
            addView(input, space())
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.s_audio_enter_port)
            .setMessage(R.string.s_audio_port_help)
            .setView(stack().apply { setPaddingRelative(dp(24), dp(12), dp(24), 0); addView(field) })
            .setNegativeButton(R.string.ez_cancel, null)
            .setNeutralButton(R.string.s_open_dev) { _, _ -> openDeveloperOptions() }
            .setPositiveButton(R.string.ez_continue, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val port = LocalAdb.parseConnectPort(input.text ?: "")
            if (port == null) field.error = getString(R.string.s_audio_port_invalid)
            else {
                dialog.dismiss()
                setupAudio(port)
            }
        }
    }

    private fun setupShizukuAudio() {
        if (audioBusy) return
        val error = runCatching {
            when {
                !Shizuku.pingBinder() -> R.string.s_audio_shizuku_start
                !ShizukuAudioLauncher.supported() -> R.string.s_audio_shizuku_unsupported
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    setupAudio(method = CallAudio.Setup.Method.SHIZUKU)
                    null
                }
                Shizuku.shouldShowRequestPermissionRationale() -> R.string.s_audio_shizuku_denied
                else -> {
                    Shizuku.requestPermission(SHIZUKU_AUDIO)
                    null
                }
            }
        }.getOrElse { R.string.s_audio_shizuku_start }
        if (error != null) shizukuNotice(error)
    }

    private fun shizukuNotice(message: Int) {
        val title = when (message) {
            R.string.s_audio_shizuku_denied -> R.string.s_ui_shizuku_denied_title
            R.string.s_audio_shizuku_unsupported -> R.string.s_ui_shizuku_unsupported_title
            else -> R.string.s_ui_shizuku_inactive_title
        }
        val card = stack().apply { setPaddingRelative(dp(20), dp(20), dp(20), dp(20)) }
        val dialog = MaterialAlertDialogBuilder(this).setView(card).create()
        card.addView(sectionTitle(getString(title), R.style.TextAppearance_ezcall_TitleLarge))
        card.addView(body(getString(message)), space(top = 12))
        card.addView(actionButton(getString(R.string.s_ok), dialog::dismiss), space(top = 12))
        dialog.show()
    }

    private fun setupAudio(connectPort: Int? = null, method: CallAudio.Setup.Method = CallAudio.Setup.Method.WIRELESS) {
        if (audioBusy) return
        if (method == CallAudio.Setup.Method.WIRELESS && !LocalAdb.hasKey()) {
            alert(getString(R.string.s_wireless_first))
            return
        }
        audioBusy = true
        startingRoot = method == CallAudio.Setup.Method.ROOT
        rootGranted = false
        setupAudioError = null
        renderAudio()
        val context = applicationContext
        Thread {
            val onRootGranted: (() -> Unit)? = if (method != CallAudio.Setup.Method.ROOT) null else ({
                runOnUiThread {
                    if (!isDestroyed && startingRoot) {
                        rootGranted = true
                        renderAudio()
                    }
                }
            })
            val result = runCatching { CallAudio.Setup.start(context, connectPort, method, onRootGranted) }
            runOnUiThread {
                audioBusy = false
                startingRoot = false
                rootGranted = false
                if (result.isSuccess) RelayState.refreshAudio?.invoke()
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { status ->
                    setupAudioReady = status
                }.onFailure {
                    setupAudioReady = null
                    setupAudioError = when {
                        method == CallAudio.Setup.Method.ROOT -> R.string.s_audio_root_failed
                        method == CallAudio.Setup.Method.SHIZUKU -> R.string.s_audio_shizuku_failed
                        it is LocalAdb.PortNotFound -> R.string.s_audio_port_missing
                        it is AdbIdentity.Unverified -> R.string.s_audio_unverified
                        it is CallAudio.Setup.ComponentNotReady -> R.string.s_audio_component_failed
                        else -> R.string.s_audio_failure
                    }
                }
                renderAudio()
            }
        }.start()
    }

    private fun setupUsbAudio() {
        if (audioBusy) return
        val command = "adb -d shell \"set -o pipefail; content read --user ${Process.myUid() / 100_000} " +
            "--uri content://$packageName${AdbIdentity.AUTHORITY_SUFFIX}/usb | sh\""
        val request = CallAudio.Setup.requestUsb()
        val commandForDisplay = BidiFormatter.getInstance(resources.configuration.locales[0])
            .unicodeWrap(command, TextDirectionHeuristicsCompat.LTR)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s_audio_usb)
            .setMessage(getString(R.string.s_audio_usb_steps, commandForDisplay))
            .setPositiveButton(R.string.s_audio_usb_copy, null)
            .setNegativeButton(R.string.ez_cancel, null)
            .setNeutralButton(R.string.s_open_dev) { _, _ -> openDeveloperOptions() }
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB", command))
        }
        cancelUsbSetup = {
            CallAudio.Setup.cancelUsb(request)
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            CallAudio.Setup.cancelUsb(request)
            cancelUsbSetup = null
        }
        audioBusy = true
        setupAudioError = null
        renderAudio()
        val context = applicationContext
        Thread {
            val result = runCatching { CallAudio.Setup.awaitUsb(context, request) }
            runOnUiThread {
                audioBusy = false
                if (result.isSuccess) RelayState.refreshAudio?.invoke()
                if (isDestroyed) return@runOnUiThread
                if (dialog.isShowing) {
                    dialog.dismiss()
                    result.onSuccess { setupAudioReady = it }.onFailure { setupAudioError = R.string.s_audio_usb_failed }
                }
                renderAudio()
            }
        }.start()
    }

    private fun refreshSharing() {
        RelayState.refreshSharing?.invoke()
        renderSharing()
        renderAudio()
    }

    private fun ensureBluetooth() {
        if (relayStarting) return
        connectionIssue = null
        val needed = arrayOf(permission.BLUETOOTH_ADVERTISE, permission.BLUETOOTH_CONNECT)
        if (needed.any { !hasPermission(it) }) {
            requestPermissions(needed, NEARBY)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        when {
            adapter?.isEnabled == false -> {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH)
            }
            adapter == null || !adapter.isMultipleAdvertisementSupported -> showLinkIssue(R.string.s_no_advertising)
            else -> requestNotificationsThenStart()
        }
    }

    private fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(permission.POST_NOTIFICATIONS) &&
            !getPreferences(MODE_PRIVATE).getBoolean("askedNotification", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotification", true).apply()
            requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), NOTICE)
        } else startRelay()
    }

    private fun startWirelessPairing() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), WIRELESS_NOTICE)
            return
        }
        if (WirelessPairing.begin(this)) openDeveloperOptions()
        else appSettings(getString(R.string.s_wireless_notice_help))
    }

    private fun openDeveloperOptions() {
        if (runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.isFailure) {
            alert(getString(R.string.s_wireless_open_help))
        }
    }

    private fun serviceIntent() = Intent(this, RelayService::class.java).setAction(RelayService.ACTION_START)
    private fun startRelay() {
        if (relayStarting) return
        if (runCatching { startForegroundService(serviceIntent()) }.isFailure) {
            showLinkIssue(R.string.s_relay_open_app)
        } else {
            relayStarting = true
            relayBeforeStart = RelayState.current
            toolbar.postDelayed(startTimeout, 8_000)
            renderLink(RelayState.current)
        }
    }

    private fun showLinkIssue(message: Int) {
        connectionIssue = message
        renderLink(RelayState.current)
    }

    @Deprecated("Deprecated in Android framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) ensureBluetooth()
            else showLinkIssue(R.string.s_ui_bluetooth_off)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            NEARBY -> {
                if (permissions.isNotEmpty() && permissions.all { hasPermission(it) }) ensureBluetooth()
                else {
                    showLinkIssue(R.string.s_nearby_pair)
                    appSettings(getString(R.string.s_nearby_pair))
                }
            }
            NOTICE -> startRelay()
            WIRELESS_NOTICE -> {
                if (hasPermission(permission.POST_NOTIFICATIONS)) startWirelessPairing()
                else appSettings(getString(R.string.s_wireless_allow_notice))
            }
            SHARE -> requestingFeature?.let { feature ->
                requestingFeature = null
                val granted = feature.permissions.any { hasPermission(it) }
                feature.allow(this, granted)
                refreshSharing()
                if (!granted) appSettings(getString(R.string.s_permission_denied))
            }
        }
    }

    private fun alert(message: String) {
        MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton(getString(R.string.s_ok), null).show()
    }

    private fun appSettings(message: String) {
        MaterialAlertDialogBuilder(this).setMessage(message).setNegativeButton(getString(R.string.ez_close), null)
            .setPositiveButton(getString(R.string.ez_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.show()
    }

    private fun qrView(value: String) = ImageView(this).apply {
        contentDescription = getString(R.string.s_qr_label)
        setImageBitmap(qrBitmap(value))
        adjustViewBounds = true
        maxWidth = dp(256)
        maxHeight = dp(256)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = rounded(Color.WHITE, 22)
        clipToOutline = true
        foreground = rounded(Color.TRANSPARENT, 22).apply {
            setStroke(dp(1), getColor(R.color.sender_qr_border))
        }
    }

    private fun qrBitmap(value: String): Bitmap {
        if (value == qrValue) qrImage?.let { return it }
        val logo = BitmapFactory.decodeResource(resources, R.drawable.sender_header_brand)
        val brandPixels = IntArray(logo.width * logo.height)
        logo.getPixels(brandPixels, 0, logo.width, 0, 0, logo.width, logo.height)
        val size = dp(256)
        val code = SenderPairingCode(value, size, brandPixels, logo.width)
        return Bitmap.createBitmap(code.pixels, size, size, Bitmap.Config.ARGB_8888).also {
            it.density = resources.displayMetrics.densityDpi
            qrValue = value
            qrImage = it
        }
    }


    private enum class Page(val title: Int, val icon: Int) {
        CONNECTION(R.string.s_connection, R.drawable.ic_link),
        SHARING(R.string.s_ui_sharing_tab, R.drawable.ic_devices),
        AUDIO(R.string.s_ui_audio_tab, R.drawable.ic_headset)
    }

    private enum class Detail(val title: Int) {
        WELCOME(R.string.s_ui_welcome_title),
        GUIDE(R.string.s_guide),
        SETTINGS(R.string.ez_settings),
        SETTINGS_AUDIO(R.string.s_call_audio),
        NOTIFICATIONS(R.string.s_relay_notifications),
        BATTERY(R.string.s_battery_action),
        OVERLAY(R.string.s_call_overlay),
        REPORT(R.string.s_test_report),
        AUDIO_SETUP(R.string.s_ui_audio_setup_methods)
    }

    private val TestCallReport.inProgress get() = when (stage) {
        TestCallReport.Stage.STARTING, TestCallReport.Stage.RINGING, TestCallReport.Stage.AUDIO,
        TestCallReport.Stage.RECONNECTING -> true
        else -> false
    }

    private fun row() = LinearLayout(this).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    private fun sectionTitle(value: String, appearance: Int) =
        text(value, appearance).apply { isAccessibilityHeading = true }

    private fun groupLabel(label: Int) = sectionTitle(getString(label), R.style.TextAppearance_ezcall_LabelMedium)
        .apply { setTextColor(color(androidx.appcompat.R.attr.colorPrimary)) }

    private fun artwork(resource: Int) = ImageView(this).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun brandLockup(welcome: Boolean) = row().apply {
        val size = dp(if (welcome) 52 else 48)
        addView(artwork(if (welcome) R.drawable.sender_welcome_brand else R.drawable.sender_header_brand),
            LinearLayout.LayoutParams(size, size))
        addView(text(getString(R.string.ez_brand), R.style.TextAppearance_ezcall_SenderWordmark,
            if (welcome) Color.WHITE else color(MaterialR.attr.colorOnSurface)),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        if (!welcome && !largeText) {
            addView(text(getString(R.string.s_sender), R.style.TextAppearance_ezcall_LabelMedium,
                color(MaterialR.attr.colorOnPrimaryContainer)).apply {
                setPaddingRelative(dp(14), dp(5), dp(14), dp(5))
                background = rounded(color(MaterialR.attr.colorPrimaryContainer), 18)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
    }

    private fun headerButton(icon: Int, label: String, action: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(tinted(icon))
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(color(MaterialR.attr.colorOutlineVariant)),
            rounded(color(MaterialR.attr.colorSurfaceContainerLow), 24), null)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun guideNotice(icon: Int, title: Int, description: Int? = null) = row().apply {
        val ink = color(MaterialR.attr.colorOnPrimaryContainer)
        background = rounded(color(MaterialR.attr.colorPrimaryContainer), 16)
        minimumHeight = dp(53)
        setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        addView(artwork(icon).apply { imageTintList = ColorStateList.valueOf(ink) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        addView(stack().apply {
            addView(text(getString(title), R.style.TextAppearance_ezcall_TitleMedium, ink), space())
            description?.let { addView(text(getString(it), R.style.TextAppearance_ezcall_BodySmall, ink), space()) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun centered(top: Int) = LinearLayout.LayoutParams(-2, -2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(top)
    }

    private fun secondaryText(value: String) = text(value, R.style.TextAppearance_ezcall_BodyLarge,
        color(MaterialR.attr.colorOnSurfaceVariant))

    private fun body(value: String) = text(value, R.style.TextAppearance_ezcall_BodyMedium,
        color(MaterialR.attr.colorOnSurfaceVariant)).apply { textSize = 15f }

    private fun surfaceCard(content: View, accent: Boolean = false) = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(if (accent) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerLow))
        strokeWidth = 0
        addView(content)
    }

    private fun divider() = MaterialDivider(this).apply {
        dividerColor = color(MaterialR.attr.colorOutlineVariant)
        dividerInsetStart = dp(54)
        dividerInsetEnd = dp(15)
    }

    private fun spinner() = CircularProgressIndicator(this).apply {
        indicatorSize = dp(28)
        trackThickness = dp(3)
        isIndeterminate = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun actionButton(label: String, action: () -> Unit) = button(MaterialButton(this), label, action)

    private fun outlineButton(label: String, action: () -> Unit) = button(
        MaterialButton(ContextThemeWrapper(this, R.style.ThemeOverlay_ezcall_SenderOutlinedButton)), label, action
    )

    private fun textButton(label: String, action: () -> Unit) = button(
        MaterialButton(ContextThemeWrapper(this, R.style.ThemeOverlay_ezcall_TextButton)), label, action
    )

    private fun button(view: MaterialButton, label: String, action: () -> Unit) = view.apply {
        text = label
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(24)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setOnClickListener { action() }
    }

    private data class SettingsRow(val view: LinearLayout, val detail: MaterialTextView)

    private fun settingsRow(icon: Int, title: String, detail: String, enabled: Boolean = true, action: () -> Unit): SettingsRow =
        statusRow(icon, title, detail).apply {
            view.isEnabled = enabled
            view.isFocusable = true
            view.setBackgroundResource(selectableBackground())
            view.setOnClickListener { action() }
            view.addView(ImageView(this@MainActivity).apply {
                setImageDrawable(tinted(R.drawable.ic_sender_chevron))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        }

    private fun statusRow(icon: Int, title: String, detail: String): SettingsRow {
        val description = body(detail)
        val view = row().apply {
            minimumHeight = dp(68)
            setPaddingRelative(dp(12), dp(10), dp(12), dp(10))
            isScreenReaderFocusable = true
            if (!largeText) {
                addView(iconTile(icon), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            }
            addView(stack().apply {
                addView(text(title, R.style.TextAppearance_ezcall_TitleMedium))
                addView(description, space(top = 2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return SettingsRow(view, description)
    }

    private fun selectableBackground(attribute: Int = android.R.attr.selectableItemBackground): Int {
        val highlight = android.util.TypedValue()
        theme.resolveAttribute(attribute, highlight, true)
        return highlight.resourceId
    }

    private fun tinted(icon: Int) = AppCompatResources.getDrawable(this, icon)?.mutate()
        ?.apply { setTint(color(MaterialR.attr.colorOnSurfaceVariant)) }

    private fun stack() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun space(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun text(label: String, appearance: Int, ink: Int = color(MaterialR.attr.colorOnSurface)) =
        MaterialTextView(this).apply {
            text = label
            setTextAppearance(appearance)
            letterSpacing = 0f
            setTextColor(ink)
        }

    private fun color(attribute: Int) = MaterialColors.getColor(this, attribute, javaClass.simpleName)

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun iconTile(icon: Int, size: Int = 44) = ImageView(this).apply {
        val inset = dp((size - 22) / 2)
        setPadding(inset, inset, inset, inset)
        setImageResource(icon)
        background = rounded(color(MaterialR.attr.colorPrimaryContainer), 12)
        imageTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnSurface))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val largeText get() = resources.configuration.fontScale >= 1.5f

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val NEARBY = 1
        const val NOTICE = 2
        const val SHARE = 3
        const val ENABLE_BLUETOOTH = 4
        const val WIRELESS_NOTICE = 5
        const val SHIZUKU_AUDIO = 6
    }
}
