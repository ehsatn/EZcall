package app.ezcall.android

import android.Manifest.permission
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
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
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.WindowCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var guideAction: LinearLayout
    private lateinit var navigation: BottomNavigationView
    private lateinit var back: OnBackPressedCallback
    private var page = Page.CONNECTION
    private var detail: Detail? = null
    private var guideFromSettings = false
    private var audioSetupFromGuide = false
    private var connectionPanel: LinearLayout? = null
    private var sharingPanel: LinearLayout? = null
    private var audioPanel: LinearLayout? = null
    private var testPanel: LinearLayout? = null
    private var reportPanel: LinearLayout? = null
    private var settingsNotifications: SettingsRow? = null
    private var settingsBattery: SettingsRow? = null
    private var shownAudio: AudioDisplay? = null
    private var shownTest: TestDisplay? = null
    private var shownReport: TestCallReport? = null
    private var testRequestFailed = false
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
    private var setupAudioReady: CallAudio.Status? = null
    private var setupAudioError: Int? = null
    private var requestingFeature: Feature? = null
    private var lastState: RelayState? = null
    private var pairingAgain = false
    private var pairingHint: String? = null
    private var qrImage: Bitmap? = null
    private var qrValue: String? = null
    internal enum class Step(
        val title: Int, val detail: Int, val help: Int, val image: Int, val imageDescription: Int
    ) {
        SHARING(R.string.s_sharing, R.string.s_ui_guide_sharing, R.string.s_ui_guide_help_sharing,
            R.drawable.sender_guide_sharing, R.string.s_ui_guide_image_sharing),
        LINK(R.string.ez_pair_phones, R.string.s_ui_guide_pairing, R.string.s_ui_guide_help_pairing,
            R.drawable.sender_guide_pairing, R.string.s_ui_guide_image_pairing),
        AUDIO(R.string.s_call_audio, R.string.s_ui_guide_optional_audio, R.string.s_ui_guide_help_audio,
            R.drawable.sender_guide_audio, R.string.s_ui_guide_image_audio);

        fun previous() = entries.getOrNull(ordinal - 1)

        companion object {
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        requestingFeature = savedInstanceState?.getString("feature")?.let(Feature::valueOf)
        setupAudioReady = savedInstanceState?.getString("setupAudioReady")?.let(CallAudio.Status::valueOf)
        setupAudioError = savedInstanceState?.getInt("setupAudioError")?.takeIf { it != 0 }
        connectionIssue = savedInstanceState?.getInt("connectionIssue")?.takeIf { it != 0 }
        pairingAgain = savedInstanceState?.getBoolean("pairingAgain") == true
        pairingHint = savedInstanceState?.getString("pairingHint")
        page = Page.entries.getOrElse(savedInstanceState?.getInt("page") ?: 0) { Page.CONNECTION }
        detail = savedInstanceState?.getString("detail")?.let(Detail::valueOf)
            ?: Detail.SETTINGS.takeIf { savedInstanceState?.getBoolean("settingsOpen") == true }
        guideFromSettings = savedInstanceState?.getBoolean("guideFromSettings") == true
        with(getPreferences(MODE_PRIVATE)) {
            val saved = getString("senderGuideStep", null)
            step = Step.restore(saved, getString("setupStep", null), PairKey(this@MainActivity).exists())
            if (saved == null) {
                saveStep(step)
                if (step != null && savedInstanceState == null) detail = Detail.GUIDE
            }
        }
        audioSetupFromGuide = detail == Detail.AUDIO_SETUP && step == Step.AUDIO &&
            savedInstanceState?.getBoolean("audioSetupFromGuide") == true
        if (detail == Detail.GUIDE && step == null) detail = null

        val screen = stack().apply { setBackgroundColor(color(MaterialR.attr.colorSurface)) }
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
            setContentInsetsRelative(dp(20), dp(12))
            setNavigationOnClickListener { navigateBack() }
            menu.add(getString(R.string.ez_settings)).apply {
                icon = tinted(R.drawable.ic_settings)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener { showDetail(Detail.SETTINGS); true }
            }
        }
        content = stack().apply { setPaddingRelative(dp(20), dp(16), dp(20), dp(24)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, space())
        }
        guideAction = stack().apply {
            setPaddingRelative(dp(20), dp(4), dp(20), dp(8))
            setBackgroundColor(color(MaterialR.attr.colorSurface))
        }
        navigation = BottomNavigationView(this).apply {
            for (destination in Page.entries) {
                menu.add(0, destination.ordinal + 1, 0, destination.title).setIcon(destination.icon)
            }
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setLabelMaxLines(2)
            setLabelFontScalingEnabled(true)
            isItemHorizontalTranslationEnabled = false
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
        val previous = step?.previous()
        when {
            detail == Detail.AUDIO_SETUP && audioSetupFromGuide -> {
                audioSetupFromGuide = false
                showDetail(Detail.GUIDE)
            }
            detail == Detail.GUIDE && previous != null -> moveTo(previous)
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
        audioSetupFromGuide = detail == Detail.GUIDE
        showDetail(Detail.AUDIO_SETUP)
    }

    private fun renderPage() {
        connectionPanel = null
        sharingPanel = null
        audioPanel = null
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
        toolbar.title = getString(detail?.title ?: R.string.ez_brand)
        toolbar.subtitle = getString(R.string.s_sender).takeIf { detail == null }
        toolbar.logo = if (detail == null) AppCompatResources.getDrawable(this, R.drawable.ic_brand) else null
        toolbar.setTitleMarginStart(if (detail == null) dp(10) else 0)
        toolbar.navigationIcon = detail?.let { tinted(R.drawable.ic_sender_back) }
        toolbar.setNavigationContentDescription(when {
            detail == Detail.GUIDE && step?.previous() != null -> R.string.s_guide_back
            detail == Detail.GUIDE -> R.string.s_close_guide
            else -> R.string.s_ui_back
        })
        toolbar.menu.getItem(0).isVisible = detail == null
        content.accessibilityPaneTitle = getString(detail?.title ?: if (page == Page.SHARING) R.string.s_sharing else page.title)
        back.isEnabled = detail != null || page != Page.CONNECTION

        when (detail) {
            Detail.GUIDE -> renderGuide()
            Detail.SETTINGS -> renderSettings()
            Detail.REPORT -> reportPanel = content
            Detail.AUDIO_SETUP -> audioPanel = content
            null -> when (page) {
                Page.CONNECTION -> connectionPanel = content
                Page.SHARING -> {
                    content.addView(sectionTitle(getString(R.string.s_sharing), R.style.TextAppearance_ezcall_HeadlineLarge))
                    content.addView(secondaryText(getString(R.string.s_sharing_intro)), space(top = 6))
                    sharingPanel = stack().also { content.addView(it, space(top = 24)) }
                }
                Page.AUDIO -> {
                    content.addView(sectionTitle(getString(R.string.s_call_audio), R.style.TextAppearance_ezcall_HeadlineLarge))
                    content.addView(secondaryText(getString(R.string.s_ui_audio_intro)), space(top = 6))
                    audioPanel = stack().also { content.addView(it, space(top = 20)) }
                    testPanel = stack().also { content.addView(it, space(top = 24)) }
                }
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
        outState.putInt("page", page.ordinal)
        detail?.let { outState.putString("detail", it.name) }
        outState.putBoolean("guideFromSettings", guideFromSettings)
        outState.putBoolean("audioSetupFromGuide", audioSetupFromGuide)
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

    override fun onDestroy() {
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
        val connectedNow = lastState?.connected == false && state.connected
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
        if (connectedNow && detail == Detail.GUIDE && step == Step.LINK) {
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
        if (!inGuide) panel.addView(sectionTitle(getString(R.string.s_connection), R.style.TextAppearance_ezcall_HeadlineLarge))

        val welcome = !inGuide && !paired && !state.running && !relayStarting && state.detail == null && connectionIssue == null
        val expired = state.detail == "Pairing code expired. Start pairing again."
        val issue = connectionIssue?.let(::getString) ?: state.detail?.let(::senderNotice)
        val guideQr = inGuide && qr != null
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
            guideQr -> getString(R.string.s_ui_guide_qr_expiry)
            qr != null -> getString(R.string.s_scan_instruction)
            state.connected -> getString(if (state.tested) R.string.ez_test_passed else R.string.s_relay_active)
            relayStarting -> getString(R.string.s_nearby)
            issue != null && issue != title -> issue
            state.running -> getString(R.string.s_nearby)
            paired -> getString(R.string.s_reconnect_detail)
            welcome -> getString(R.string.s_pair_instruction)
            else -> getString(R.string.s_start_link)
        }
        val foreground = if (state.connected) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface
        val card = stack().apply {
            setPaddingRelative(dp(20), dp(20), dp(20), dp(20))
            if (!guideQr) {
                if (relayStarting || (state.running && !state.connected && qr == null)) {
                    addView(spinner(), LinearLayout.LayoutParams(-2, -2))
                } else {
                    addView(iconTile(if (qr != null) R.drawable.ic_qr else R.drawable.ic_bluetooth),
                        LinearLayout.LayoutParams(dp(44), dp(44)))
                }
                addView(text(title, R.style.TextAppearance_ezcall_HeadlineSmall, color(foreground)).apply {
                    isAccessibilityHeading = true
                }, space(top = 14))
            }
            addView(text(description, R.style.TextAppearance_ezcall_BodyMedium,
                color(if (state.connected) foreground else if (issue != null && !state.running && qr == null)
                    androidx.appcompat.R.attr.colorError else MaterialR.attr.colorOnSurfaceVariant)),
                space(top = if (guideQr) 0 else 6))
            if (welcome) {
                addView(PairingArtwork(this@MainActivity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background = rounded(getColor(R.color.ezcall_pairing_hero), 20)
                    clipToOutline = true
                }, space(top = 16).apply { height = dp(140) })
            }
            if (qr != null) {
                pairingHint?.let { addView(secondaryText(senderNotice(it)), space(top = 12)) }
                val image = ImageView(this@MainActivity).apply {
                    contentDescription = getString(R.string.s_qr_label)
                    setImageBitmap(qrBitmap(qr))
                    adjustViewBounds = true
                    maxWidth = dp(252)
                    maxHeight = dp(252)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(Color.WHITE, 16)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                addView(image, LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(16)
                })
                addView(textButton(getString(R.string.ez_cancel_pairing)) { RelayState.cancelPairing?.invoke() }, space(top = 8))
            }
            if (!inGuide && qr == null && !state.running && !relayStarting) {
                val action = when {
                    welcome && step != null -> R.string.s_ui_resume_guide
                    welcome -> R.string.s_start_pair
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
            }, space(top = if (inGuide) 0 else 16))
        }
        if (inGuide) {
            if (!state.connected && !relayStarting) {
                panel.addView(textButton(getString(R.string.s_guide_audio_next)) { moveTo(Step.AUDIO) }, space(top = 8))
            }
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
            null to listOf(Feature.CONTACTS)
        )
        for ((heading, features) in sections) {
            if (heading != null) {
                panel.addView(sectionTitle(getString(heading), R.style.TextAppearance_ezcall_TitleLarge),
                    space(top = if (panel.childCount == 0) 0 else 20))
            }
            val group = stack()
            for (feature in features) {
                if (group.childCount > 0) group.addView(divider(), space())
                val enabled = callsEnabled || (feature != Feature.CALLER && feature != Feature.CONTROL)
                group.addView(sharingRow(feature, enabled), space())
            }
            panel.addView(surfaceCard(group), space(top = if (heading == null) 20 else 8))
            if (detail == Detail.GUIDE && heading == R.string.s_ui_calls_group && !callsEnabled) {
                panel.addView(body(getString(R.string.s_ui_guide_call_dependency)), space(top = 6))
            }
        }
    }

    private fun sharingRow(feature: Feature, enabled: Boolean): View {
        val (icon, description) = when (feature) {
            Feature.CALLS -> R.drawable.ic_call to R.string.s_incoming_call_alert
            Feature.CALLER -> R.drawable.ic_caller to R.string.s_incoming_number
            Feature.CONTROL -> R.drawable.ic_answer to R.string.s_control_description
            Feature.SMS -> R.drawable.ic_sms to R.string.s_sms_description
            Feature.SEND_SMS -> R.drawable.ic_sms to R.string.s_send_description
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
            minimumHeight = dp(72)
            setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (enabled) {
                setBackgroundResource(selectableBackground())
                setOnClickListener { toggle.performClick() }
                isFocusable = false
            }
            if (!largeText) {
                addView(iconTile(icon, active), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            }
            addView(stack().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(title, R.style.TextAppearance_ezcall_TitleMedium))
                if (detail != Detail.GUIDE) addView(body(summary), space(top = 2))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(toggle, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun openGuide() {
        guideFromSettings = detail == Detail.SETTINGS
        saveStep(step ?: Step.SHARING)
        showDetail(Detail.GUIDE)
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
        content.addView(text(getString(R.string.s_step, current.ordinal + 1, Step.entries.size),
            R.style.TextAppearance_ezcall_LabelLarge, color(androidx.appcompat.R.attr.colorPrimary)))
        content.addView(LinearProgressIndicator(this).apply {
            max = Step.entries.size
            progress = current.ordinal + 1
            trackThickness = dp(4)
            trackCornerRadius = dp(2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, space(top = 8))
        val title = getString(current.title)
        val helpLabel = getString(R.string.s_ui_guide_help_for, title)
        content.addView(row().apply {
            addView(sectionTitle(title, R.style.TextAppearance_ezcall_HeadlineLarge), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageButton(this@MainActivity).apply {
                setImageDrawable(tinted(R.drawable.ic_sender_help))
                setBackgroundResource(selectableBackground(android.R.attr.selectableItemBackgroundBorderless))
                contentDescription = helpLabel
                setOnClickListener { showGuideHelp(current, helpLabel) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        }, space(top = 16))
        content.addView(secondaryText(getString(current.detail)), space(top = 6))
        when (current) {
            Step.SHARING -> {
                sharingPanel = stack().also { content.addView(it, space(top = 24)) }
                setGuideAction(R.string.ez_continue) { moveTo(Step.LINK) }
            }
            Step.LINK -> connectionPanel = stack().also { content.addView(it, space(top = 20)) }
            Step.AUDIO -> {
                val setup = if (wirelessAudioReady()) textButton(getString(R.string.s_ui_audio_setup_methods), ::openAudioSetup)
                    else outlineButton(getString(R.string.s_setup_audio), ::openAudioSetup)
                content.addView(setup, space(top = 16))
                setGuideAction(R.string.s_ui_finish_guide) {
                    saveStep(null)
                    closeGuide()
                }
            }
        }
    }

    private fun setGuideAction(label: Int, canContinue: Boolean = true, action: () -> Unit) {
        guideAction.removeAllViews()
        guideAction.visibility = View.VISIBLE
        guideAction.addView(actionButton(getString(label), action = action).apply { isEnabled = canContinue }, space())
    }

    private fun showGuideHelp(step: Step, title: String) {
        if (step == Step.AUDIO && wirelessAudioReady()) {
            MaterialAlertDialogBuilder(this).setTitle(title).setMessage(R.string.s_wireless_paired)
                .setPositiveButton(R.string.ez_close, null).show()
            return
        }
        val help = ScrollView(this).apply {
            addView(stack().apply {
                setPaddingRelative(dp(24), dp(8), dp(24), dp(8))
                addView(body(getString(step.help)), space())
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(step.image)
                    adjustViewBounds = true
                    maxHeight = dp(220)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = getString(step.imageDescription)
                }, space(top = 14))
            })
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(help).setPositiveButton(R.string.ez_close, null).show()
    }

    private data class TestDisplay(val report: TestCallReport?, val canTest: Boolean, val requestFailed: Boolean)

    private fun renderTest() {
        val test = RelayState.testCall
        testPanel?.let { panel ->
            val display = TestDisplay(test, RelayState.current.connected && RelayState.simulateCall != null, testRequestFailed)
            if (shownTest != display) {
                shownTest = display
                panel.removeAllViews()
                panel.addView(sectionTitle(getString(R.string.s_simulate), R.style.TextAppearance_ezcall_TitleLarge))
                val state = when {
                    display.requestFailed -> R.string.s_simulate_retry
                    test?.inProgress == true -> test.stage.label
                    display.canTest -> R.string.s_simulate_wait
                    else -> R.string.s_ui_test_connect
                }
                panel.addView(body(getString(state)).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    if (display.requestFailed) setTextColor(color(androidx.appcompat.R.attr.colorError))
                }, space(top = 6))
                panel.addView(actionButton(getString(R.string.s_ui_start_test)) {
                    if (RelayState.simulateCall?.invoke() == true) {
                        testRequestFailed = false
                        showDetail(Detail.REPORT)
                    } else {
                        testRequestFailed = true
                        renderTest()
                    }
                }.apply { isEnabled = display.canTest && test?.inProgress != true }, space(top = 12))
                panel.addView(outlineButton(getString(R.string.s_test_report)) { showDetail(Detail.REPORT) }, space(top = 4))
            }
        }
        val panel = reportPanel ?: return
        if (panel.childCount > 0 && shownReport == test) return
        shownReport = test
        panel.removeAllViews()
        if (test == null) {
            panel.addView(sectionTitle(getString(R.string.s_ui_no_test), R.style.TextAppearance_ezcall_HeadlineLarge))
            if (!RelayState.current.connected) {
                panel.addView(secondaryText(getString(R.string.s_ui_test_connect)), space(top = 6))
            }
            panel.addView(actionButton(getString(R.string.s_ui_back_to_audio)) { showDetail(null) }, space(top = 16))
            return
        }
        val report = stack().apply {
            setPaddingRelative(dp(16), dp(16), dp(16), dp(12))
            if (test.inProgress) addView(spinner(), LinearLayout.LayoutParams(-2, -2))
            val outcome = when {
                test.verified -> R.string.ez_test_auto_passed
                test.stage == TestCallReport.Stage.FINISHED -> R.string.ez_test_auto_incomplete
                else -> test.stage.label
            }
            addView(sectionTitle(getString(outcome), R.style.TextAppearance_ezcall_TitleLarge).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                if (!test.inProgress && test.stage != TestCallReport.Stage.FINISHED && !test.verified) {
                    setTextColor(color(androidx.appcompat.R.attr.colorError))
                }
            }, space(top = if (test.inProgress) 12 else 0))
            if (test.stage in setOf(TestCallReport.Stage.STARTING, TestCallReport.Stage.RINGING, TestCallReport.Stage.AUDIO)) {
                addView(body(getString(R.string.s_simulate_wait)), space(top = 6))
            }
            if (test.audioStarted) {
                val live = test.stage == TestCallReport.Stage.AUDIO
                RelayState.testLog?.let { log ->
                    val ink = color(androidx.appcompat.R.attr.colorPrimary)
                    addView(body(getString(R.string.ez_test_sent_tone_wave)), space(top = 16))
                    addView(AudioWaveformView(this@MainActivity, log.localWaveform, ink, live).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, space().apply { height = dp(38) })
                    addView(body(getString(R.string.ez_test_remote_mic_wave)), space(top = 8))
                    addView(AudioWaveformView(this@MainActivity, log.remoteWaveform, ink, live).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, space().apply { height = dp(38) })
                }
                val checks = listOf(
                    when {
                        test.playbackConfirmed -> R.string.ez_test_tone_played
                        test.cue == TestCallReport.Cue.FAILED -> test.cue.label
                        else -> R.string.ez_test_wait_tone
                    },
                    if (test.microphoneSignal) R.string.ez_test_mic_detected else R.string.ez_test_wait_mic,
                    if (test.echoConfirmed) R.string.ez_test_echo_played else R.string.ez_test_wait_echo
                )
                addView(divider(), space(top = 12))
                for (check in checks) addView(body(getString(check)), space(top = 8))
            }
        }
        panel.addView(surfaceCard(report, accent = test.verified), space())
        RelayState.testLog?.let { log ->
            panel.addView(outlineButton(getString(R.string.ez_test_log)) {
                log.show(this, R.string.ez_test_log, R.string.ez_test_copy)
            }, space(top = 12))
        }
    }

    private fun renderSettings() {
        val app = stack().apply {
            addView(settingsRow(R.drawable.ic_language, getString(R.string.ez_language), languageName()) {
                showEzcallLanguage(getString(R.string.ez_language))
            }.view, space())
            addView(divider(), space())
            addView(settingsRow(R.drawable.ic_devices, getString(if (step == null) R.string.s_guide else R.string.s_ui_resume_guide),
                getString(R.string.s_reopen_guide), action = ::openGuide).view, space())
        }
        content.addView(surfaceCard(app), space())
        content.addView(sectionTitle(getString(R.string.s_relay), R.style.TextAppearance_ezcall_TitleLarge), space(top = 24))
        val relay = stack().apply {
            settingsNotifications = settingsRow(R.drawable.ic_notifications, getString(R.string.s_relay_notifications), "") {
                appSettings(getString(R.string.s_notifications_in_status))
            }.also { addView(it.view, space()) }
            addView(divider(), space())
            settingsBattery = settingsRow(R.drawable.ic_battery, getString(R.string.s_battery_action), "", action = ::requestBatteryExemption)
                .also { addView(it.view, space()) }
        }
        content.addView(surfaceCard(relay), space(top = 8))
        val supportUrl = getString(R.string.support_url)
        if (supportUrl.startsWith("https://")) {
            content.addView(textButton(getString(R.string.s_donate)) {
                val browser = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl))
                if (runCatching { startActivity(browser) }.isFailure) alert(getString(R.string.s_no_browser))
            }, space(top = 12))
        }
        renderSettingsStatus()
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

    private fun wirelessAudioReady(active: CallAudio.Status? = liveAudio(), paired: Boolean = WirelessPairing.isPaired(this)) =
        active != null && paired && LocalAdb.hasVerifiedSetup(this)

    private fun audioTitle(): String = when {
        audioBusy -> getString(R.string.s_starting_audio)
        liveAudio() == CallAudio.Status.TWO_WAY -> getString(R.string.s_two_way)
        liveAudio() == CallAudio.Status.LISTEN -> getString(R.string.s_listen_only)
        CallAudio.Setup.enabled(this) && RelayState.current.running -> getString(R.string.s_audio_stopped)
        CallAudio.Setup.enabled(this) -> getString(R.string.s_audio_relay_start)
        else -> getString(R.string.s_audio_not_setup)
    }

    private data class AudioDisplay(
        val title: String,
        val active: CallAudio.Status?,
        val configured: Boolean,
        val busy: Boolean,
        val relayRunning: Boolean,
        val paired: Boolean,
        val canStart: Boolean,
        val wirelessReady: Boolean,
        val overlayEnabled: Boolean,
        val callsReady: Boolean,
        val error: Int?
    )

    private fun renderAudio() {
        val panel = audioPanel ?: return
        val paired = WirelessPairing.isPaired(this)
        val configured = CallAudio.Setup.enabled(this)
        val active = liveAudio()
        val display = AudioDisplay(audioTitle(), active, configured, audioBusy, RelayState.current.running, paired,
            paired || (configured && LocalAdb.hasKey()), wirelessAudioReady(active, paired), Settings.canDrawOverlays(this),
            Feature.CALLS.active(this) && Feature.CONTROL.active(this) && hasPermission(permission.ANSWER_PHONE_CALLS),
            setupAudioError)
        if (shownAudio == display) return
        shownAudio = display
        val info = when {
            display.busy -> R.string.s_audio_keep_wireless
            display.active == CallAudio.Status.TWO_WAY -> R.string.s_audio_two_way_info
            display.active == CallAudio.Status.LISTEN -> R.string.s_audio_listen_info
            display.configured && display.relayRunning -> R.string.s_audio_restart_info
            display.configured -> R.string.s_audio_resume_info
            else -> R.string.s_audio_setup_info
        }
        panel.removeAllViews()
        val state = stack().apply {
            setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            if (display.busy) addView(spinner(), LinearLayout.LayoutParams(-2, -2))
            addView(sectionTitle(display.title, R.style.TextAppearance_ezcall_TitleLarge),
                space(top = if (display.busy) 12 else 0))
            addView(body(getString(display.error ?: info)).apply {
                if (display.error != null) setTextColor(color(androidx.appcompat.R.attr.colorError))
            }, space(top = 4))
        }
        panel.addView(surfaceCard(state, accent = display.active != null).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, space())
        if (detail == null) {
            panel.addView(surfaceCard(settingsRow(R.drawable.ic_headset, getString(R.string.s_ui_audio_setup_methods),
                getString(if (display.wirelessReady) R.string.s_wireless_paired else R.string.s_ui_audio_setup_choices),
                action = ::openAudioSetup).view), space(top = 12))
            return
        }

        val methods = stack().apply {
            if (display.wirelessReady) {
                addView(body(getString(R.string.s_wireless_paired)).apply {
                    setPaddingRelative(dp(16), dp(12), dp(16), 0)
                }, space())
                addView(textButton(getString(R.string.s_restart_audio)) { setupAudio() }
                    .apply { isEnabled = !display.busy }, space())
                return@apply
            }
            val wirelessAction = if (!display.canStart) R.string.s_pair_wireless
                else if (display.configured) R.string.s_restart_audio else R.string.s_setup_audio
            addView(settingsRow(R.drawable.ic_link, getString(wirelessAction),
                getString(if (display.paired) R.string.s_wireless_paired else R.string.s_guide_wireless),
                enabled = !display.busy) {
                if (display.canStart) setupAudio() else showWirelessInstructions()
            }.view, space())
            if (display.canStart) {
                addView(textButton(getString(R.string.s_pair_wireless), ::showWirelessInstructions)
                    .apply { isEnabled = !display.busy }, space())
            }
            if (display.error == R.string.s_audio_port_missing || display.error == R.string.s_audio_failure) {
                addView(divider(), space())
                addView(settingsRow(R.drawable.ic_bluetooth, getString(R.string.s_audio_enter_port),
                    getString(R.string.s_audio_port_help), enabled = !display.busy, action = ::showAudioPort).view, space())
            }
            addView(divider(), space())
            addView(settingsRow(R.drawable.ic_devices, getString(R.string.s_audio_usb),
                getString(R.string.s_audio_usb_detail), enabled = !display.busy, action = ::setupUsbAudio).view, space())
        }
        panel.addView(surfaceCard(methods), space(top = 8))
        if (!display.callsReady || !display.overlayEnabled || audioSetupFromGuide) {
            val controls = stack()
            if (!display.callsReady) {
                controls.addView(settingsRow(R.drawable.ic_answer, getString(R.string.s_call_control),
                    getString(R.string.s_guide_enable_control)) {
                    if (audioSetupFromGuide) {
                        audioSetupFromGuide = false
                        detail = Detail.GUIDE
                        moveTo(Step.SHARING)
                    } else {
                        detail = null
                        navigation.selectedItemId = Page.SHARING.ordinal + 1
                    }
                }.view, space())
            }
            if (!display.overlayEnabled) {
                if (controls.childCount > 0) controls.addView(divider(), space())
                controls.addView(settingsRow(R.drawable.ic_call, getString(R.string.s_call_overlay),
                    getString(R.string.s_call_overlay_info)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.view, space())
            }
            if (audioSetupFromGuide) {
                if (controls.childCount > 0) controls.addView(divider(), space())
                controls.addView(statusRow(R.drawable.ic_headset, getString(R.string.s_guide_receiver_mic),
                    getString(R.string.s_guide_receiver_mic_detail)).view, space())
            }
            panel.addView(surfaceCard(controls), space(top = 12))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderAudio()
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

    private fun setupAudio(connectPort: Int? = null) {
        if (audioBusy) return
        if (!LocalAdb.hasKey()) {
            alert(getString(R.string.s_wireless_first))
            return
        }
        audioBusy = true
        setupAudioError = null
        renderAudio()
        val context = applicationContext
        Thread {
            val result = runCatching { CallAudio.Setup.start(context, connectPort) }
            runOnUiThread {
                audioBusy = false
                if (result.isSuccess) RelayState.refreshAudio?.invoke()
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { status ->
                    setupAudioReady = status
                }.onFailure {
                    setupAudioReady = null
                    setupAudioError = when (it) {
                        is LocalAdb.PortNotFound -> R.string.s_audio_port_missing
                        is AdbIdentity.Unverified -> R.string.s_audio_unverified
                        is CallAudio.Setup.ComponentNotReady -> R.string.s_audio_component_failed
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
        dialog.setOnDismissListener { CallAudio.Setup.cancelUsb(request) }
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

    private fun qrBitmap(value: String): Bitmap {
        if (value == qrValue) qrImage?.let { return it }
        val side = dp(252)
        val grid = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, side, side)
        val pixels = IntArray(side * side) { offset ->
            if (grid[offset % side, offset / side]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).also {
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
        GUIDE(R.string.s_guide),
        SETTINGS(R.string.ez_settings),
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

    private fun secondaryText(value: String) = text(value, R.style.TextAppearance_ezcall_BodyLarge,
        color(MaterialR.attr.colorOnSurfaceVariant))

    private fun body(value: String) = text(value, R.style.TextAppearance_ezcall_BodyMedium,
        color(MaterialR.attr.colorOnSurfaceVariant))

    private fun surfaceCard(content: View, accent: Boolean = false) = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(if (accent) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerLow))
        strokeWidth = 0
        addView(content)
    }

    private fun divider() = MaterialDivider(this).apply {
        dividerColor = color(MaterialR.attr.colorOutlineVariant)
        dividerInsetStart = dp(12)
        dividerInsetEnd = dp(12)
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
        minimumHeight = dp(52)
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
            setTextColor(ink)
        }

    private fun color(attribute: Int) = MaterialColors.getColor(this, attribute, javaClass.simpleName)

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun iconTile(icon: Int, active: Boolean = false) = ImageView(this).apply {
        val inset = dp(11)
        setPadding(inset, inset, inset, inset)
        setImageResource(icon)
        background = rounded(color(if (active) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerHigh), 12)
        imageTintList = ColorStateList.valueOf(
            color(if (active) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant))
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
    }
}
