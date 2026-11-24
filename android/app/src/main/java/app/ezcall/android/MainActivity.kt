package app.ezcall.android

import android.Manifest.permission
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.app.NotificationManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
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
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    private lateinit var connection: LinearLayout
    private lateinit var sharing: LinearLayout
    private lateinit var sharingCard: MaterialCardView
    private lateinit var walkthroughSharing: LinearLayout
    private lateinit var sharingHome: LinearLayout
    private lateinit var status: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupActions: LinearLayout
    private var guideAudio: LinearLayout? = null
    private lateinit var connectionScroll: ScrollView
    private lateinit var sharingScroll: ScrollView
    private lateinit var statusContent: ScrollView
    private lateinit var navigation: BottomNavigationView
    private lateinit var back: OnBackPressedCallback
    private lateinit var settingsNotifications: SettingsRow
    private lateinit var settingsBattery: SettingsRow
    private var settingsSheet: BottomSheetDialog? = null
    private var page = Page.CONNECTION
    private var audioBusy = false
    private var setupAudioReady: CallAudio.Status? = null
    private var setupAudioError: Int? = null
    private var requestingFeature: Feature? = null
    private var lastState: RelayState? = null
    private var pairingAgain = false
    private var pairingHint: String? = null
    private var qrImage: Bitmap? = null
    private var qrValue: String? = null
    internal enum class Step(val title: Int, val detail: Int, val icon: Int) {
        SHARING(R.string.s_choose_sharing, R.string.s_guide_sharing, R.drawable.ic_devices),
        LINK(R.string.s_link_receiver, R.string.s_guide_link, R.drawable.ic_bluetooth),
        AUDIO(R.string.s_guide_audio, R.string.s_guide_audio_detail, R.drawable.ic_headset);

        companion object {
            fun restore(saved: String?, legacy: String?, paired: Boolean): Step? {
                if (saved == "done") return null
                entries.firstOrNull { it.name == saved }?.let { return it }
                return when (legacy) {
                    "WIRELESS", "AUDIO" -> AUDIO
                    "SHARING" -> SHARING
                    "LINK" -> if (paired) AUDIO else LINK
                    else -> if (paired) AUDIO else SHARING
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
        pairingAgain = savedInstanceState?.getBoolean("pairingAgain") == true
        pairingHint = savedInstanceState?.getString("pairingHint")
        page = Page.entries.getOrElse(savedInstanceState?.getInt("page") ?: 0) { Page.CONNECTION }
        with(getPreferences(MODE_PRIVATE)) {
            val saved = getString("senderGuideStep", null)
            step = Step.restore(saved, getString("setupStep", null), PairKey(this@MainActivity).exists())
            if (saved == null) edit().putString("senderGuideStep", step?.name).apply()
        }

        val screen = stack().apply { setBackgroundColor(color(android.R.attr.colorBackground)) }
        screen.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val gutter = dp(((resources.configuration.screenWidthDp - 600) / 2).coerceAtLeast(0))
            view.setPadding(safe.left + gutter, safe.top, safe.right + gutter, safe.bottom)
            WindowInsets.CONSUMED
        }
        screen.addView(buildToolbar(), space())
        val pages = FrameLayout(this)
        setupPanel = stack()
        setupActions = stack()
        walkthroughSharing = stack()
        connection = stack()
        val connectionContent = stack().apply {
            addView(setupPanel, space())
            addView(connection, space())
            addView(setupActions, space())
        }
        sharing = stack()
        sharingCard = surfaceCard(sharing)
        sharingHome = stack()
        val sharingContent = stack().apply {
            addView(sectionTitle(getString(R.string.s_sharing)))
            addView(secondaryText(getString(R.string.s_sharing_intro)), space(top = 6))
            addView(sharingHome, space(top = 20))
        }
        status = stack()
        statusContent = pageScroll(status)

        connectionScroll = pageScroll(connectionContent)
        sharingScroll = pageScroll(sharingContent)
        for (view in listOf(connectionScroll, sharingScroll, statusContent)) {
            pages.addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        screen.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))

        navigation = BottomNavigationView(this).apply {
            setBackgroundColor(color(MaterialR.attr.colorSurface))
            elevation = dp(3).toFloat()
            for (destination in Page.entries) {
                menu.add(0, destination.ordinal + 1, 0, destination.title).setIcon(destination.icon)
            }
            selectedItemId = page.ordinal + 1
            setOnItemSelectedListener {
                page = Page.entries[it.itemId - 1]
                renderPage()
                true
            }
            setOnItemReselectedListener { pageView().smoothScrollTo(0, 0) }
        }
        screen.addView(navigation, space())
        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (page == Page.CONNECTION) previousStep()
                else navigation.selectedItemId = Page.CONNECTION.ordinal + 1
            }
        }
        onBackPressedDispatcher.addCallback(this, back)
        setContentView(screen)
        renderSetup()
        renderPage()
        if (savedInstanceState?.getBoolean("settingsOpen") == true) window.decorView.post { openSettings() }
    }

    private fun buildToolbar() = MaterialToolbar(this).apply {
        minimumHeight = dp(64)
        setContentInsetsRelative(dp(24), dp(16))
        setPaddingRelative(0, 0, dp(10), 0)
        addView(row().apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_brand)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(stack().apply {
                addView(text(getString(R.string.ez_brand), R.style.TextAppearance_ezcall_TitleLarge)
                    .apply { isAccessibilityHeading = true })
                addView(text(getString(R.string.s_sender), R.style.TextAppearance_ezcall_LabelMedium, color(MaterialR.attr.colorOnSurfaceVariant)))
            })
        }, Toolbar.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        menu.add(getString(R.string.ez_settings)).apply {
            icon = AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_settings)?.mutate()
                ?.apply { setTint(color(MaterialR.attr.colorOnSurface)) }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { openSettings(); true }
        }
    }

    private fun pageScroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(stack().apply {
            setPaddingRelative(dp(24), dp(12), dp(24), dp(24))
            addView(content, space())
        })
    }

    private fun pageView() = when (page) {
        Page.CONNECTION -> connectionScroll
        Page.SHARING -> sharingScroll
        Page.STATUS -> statusContent
    }

    private fun renderPage() {
        val selected = pageView()
        for (view in listOf(connectionScroll, sharingScroll, statusContent)) {
            view.visibility = if (view === selected) View.VISIBLE else View.GONE
        }
        placeSharingCard()
        back.isEnabled = page != Page.CONNECTION || step != null
        if (page == Page.STATUS) renderStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        requestingFeature?.let { outState.putString("feature", it.name) }
        setupAudioReady?.let { outState.putString("setupAudioReady", it.name) }
        setupAudioError?.let { outState.putInt("setupAudioError", it) }
        outState.putBoolean("pairingAgain", pairingAgain)
        pairingHint?.let { outState.putString("pairingHint", it) }
        outState.putInt("page", page.ordinal)
        outState.putBoolean("settingsOpen", settingsSheet?.isShowing == true)
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
        if (settingsSheet?.isShowing == true) renderSettingsStatus()
    }

    override fun onStop() {
        if (RelayState.listener == ::render) RelayState.listener = null
        super.onStop()
    }

    private fun render(state: RelayState) {
        if (pairingAgain && !state.running) {
            pairingAgain = false
            pairingHint = state.detail
            if (!PairKey(this).exists()) ensureBluetooth()
        }
        if (state.connected) pairingHint = null
        if (state.connected && step == Step.LINK) moveTo(Step.AUDIO)
        if (liveAudio() != null) setupAudioError = null
        guideAudio?.let { renderAudioPanel(it, inGuide = true) }
        renderSettingsStatus()
        if (page == Page.STATUS) renderStatus()
        if (state == lastState) return
        lastState = state
        renderConnection(state)
    }

    private fun renderConnection(state: RelayState) {
        val paired = PairKey(this).exists()
        connection.removeAllViews()
        if (state.qr == null) {
            qrValue = null
            qrImage = null
        }
        if (!paired && !state.running && step == null && state.qr == null) {
            val hero = MaterialCardView(this).apply {
                radius = dp(32).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(getColor(R.color.ezcall_pairing_hero))
                addView(PairingArtwork(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            }
            connection.addView(hero, LinearLayout.LayoutParams(-1, dp(232)))
            connection.addView(sectionTitle(getString(R.string.ez_pair_phones)), space(top = 24))
            connection.addView(secondaryText(getString(R.string.s_pair_instruction)),
                space(top = 8))
            state.detail?.let { connection.addView(secondaryText(senderNotice(it)), space(top = 12)) }
            connection.addView(actionButton(getString(R.string.s_start_pair), R.drawable.ic_qr) { moveTo(Step.SHARING) }, space(top = 24))
            return
        }

        val ink = color(MaterialR.attr.colorOnPrimaryContainer)
        val qr = state.qr
        val body = stack().apply { setPaddingRelative(dp(18), dp(16), dp(18), dp(14)) }
        val title = if (qr == null) connectionTitle(state, paired) else getString(R.string.s_scan_to_pair)
        val detail = if (qr == null) connectionDetail(state, paired) else
            getString(R.string.s_scan_instruction)
        body.addView(row().apply {
            addView(iconTile(if (qr == null) R.drawable.ic_bluetooth else R.drawable.ic_qr, 44, 22,
                color(MaterialR.attr.colorSurface), ink), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            addView(stack().apply {
                addView(text(title, R.style.TextAppearance_ezcall_TitleLarge, ink).apply {
                    isAccessibilityHeading = true
                })
                addView(text(detail, R.style.TextAppearance_ezcall_BodyMedium, ink).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }, space(top = 4))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, space())
        if (qr != null) {
            pairingHint?.let { body.addView(text(senderNotice(it), R.style.TextAppearance_ezcall_BodyMedium, ink), space(top = 12)) }
            val image = ImageView(this).apply {
                contentDescription = getString(R.string.s_qr_label)
                setImageBitmap(qrBitmap(qr))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Color.WHITE, 16)
                clipToOutline = true
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val size = dp(minOf(252, (resources.configuration.screenWidthDp - 96).coerceAtLeast(96)))
            body.addView(image, LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(16)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            body.addView(textButton(getString(R.string.ez_cancel_pairing)) { RelayState.cancelPairing?.invoke() }, space(top = 12))
        } else if (!state.running) {
            body.addView(actionButton(if (paired) getString(R.string.s_reconnect) else getString(R.string.s_show_qr),
                if (paired) R.drawable.ic_link else R.drawable.ic_qr, ::ensureBluetooth), space(top = 16))
        }
        connection.addView(surfaceCard(body, accent = true), space())
        if (step == null && paired) {
            connection.addView(textButton(getString(R.string.s_guide)) { moveTo(Step.SHARING) }, space(top = 12))
            connection.addView(textButton(getString(R.string.s_forget_receiver), ::forgetLink), space())
        }
    }

    private fun connectionTitle(state: RelayState, paired: Boolean) = when {
        state.connected -> getString(R.string.s_receiver_connected)
        state.qr != null -> getString(R.string.s_code_open)
        state.running -> getString(R.string.s_waiting_receiver)
        paired -> getString(R.string.s_receiver_disconnected)
        else -> getString(R.string.s_no_receiver)
    }

    private fun connectionDetail(state: RelayState, paired: Boolean) = when {
        state.qr != null -> getString(R.string.s_scan_connection)
        state.tested -> getString(R.string.ez_test_passed)
        state.connected -> getString(R.string.s_relay_active)
        state.detail != null -> senderNotice(state.detail)
        state.running -> getString(R.string.s_nearby)
        paired -> getString(R.string.s_reconnect_detail)
        else -> getString(R.string.s_start_link)
    }

    private fun forgetLink() {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.s_forget_receiver_title))
            .setMessage(getString(R.string.s_forget_receiver_detail))
            .setNegativeButton(getString(R.string.ez_cancel), null)
            .setPositiveButton(getString(R.string.ez_forget)) { _, _ ->
                moveTo(null)
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
        sharing.removeAllViews()
        for (feature in Feature.entries) {
            if (feature in setOf(Feature.CALLER, Feature.CONTROL) && !Feature.CALLS.active(this)) continue
            val (icon, description) = when (feature) {
                Feature.CALLS -> R.drawable.ic_call to getString(R.string.s_incoming_call_alert)
                Feature.CALLER -> R.drawable.ic_caller to getString(R.string.s_incoming_number)
                Feature.CONTROL -> R.drawable.ic_answer to getString(R.string.s_control_description)
                Feature.SMS -> R.drawable.ic_sms to getString(R.string.s_sms_description)
                Feature.SEND_SMS -> R.drawable.ic_sms to getString(R.string.s_send_description)
                Feature.CONTACTS -> R.drawable.ic_contacts to getString(R.string.s_contacts_description)
            }
            if (sharing.childCount > 0) sharing.addView(MaterialDivider(this).apply {
                dividerColor = color(MaterialR.attr.colorOutlineVariant)
                dividerInsetStart = dp(68)
                dividerInsetEnd = dp(16)
            }, space())

            val active = feature.active(this)
            val toggle = MaterialSwitch(this).apply {
                contentDescription = getString(R.string.s_feature_summary, getString(feature.title), description)
                isChecked = active
                minimumWidth = dp(52)
                minimumHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) enable(feature)
                    else {
                        feature.allow(this@MainActivity, false)
                        refreshSharing()
                    }
                }
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(74)
                setPaddingRelative(dp(16), dp(10), dp(16), dp(10))
                val highlight = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, highlight, true)
                setBackgroundResource(highlight.resourceId)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { toggle.performClick() }
                isFocusable = false
            }
            row.addView(iconTile(icon, 40, 22,
                color(if (active) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainer),
                color(if (active) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant)),
                LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(stack().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(getString(feature.title), R.style.TextAppearance_ezcall_TitleMedium))
                addView(text(description, R.style.TextAppearance_ezcall_BodySmall,
                    color(MaterialR.attr.colorOnSurfaceVariant)), space(top = 2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            })
            row.addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            sharing.addView(row, space())
        }
    }


    private fun moveTo(next: Step?) {
        step = if (next == Step.LINK && RelayState.current.connected) Step.AUDIO else next
        getPreferences(MODE_PRIVATE).edit().putString("senderGuideStep", step?.name ?: "done").apply()
        renderSetup()
        renderConnection(RelayState.current)
        connectionScroll.smoothScrollTo(0, 0)
    }

    private fun previousStep() = moveTo(when (step) {
        Step.AUDIO -> if (RelayState.current.connected) Step.SHARING else Step.LINK
        Step.LINK -> Step.SHARING
        else -> null
    })

    private fun placeSharingCard() {
        val moving = if (step == Step.SHARING && page == Page.CONNECTION) walkthroughSharing else sharingHome
        if (sharingCard.parent !== moving) {
            (sharingCard.parent as? ViewGroup)?.removeView(sharingCard)
            moving.addView(sharingCard, space())
        }
    }

    private fun renderSetup() {
        val current = step
        placeSharingCard()
        setupPanel.removeAllViews()
        setupActions.removeAllViews()
        guideAudio = null
        setupPanel.visibility = if (current == null) View.GONE else View.VISIBLE
        connection.visibility = if (current == null || current == Step.LINK) View.VISIBLE else View.GONE
        back.isEnabled = page != Page.CONNECTION || current != null
        if (current == null) return

        val ink = color(MaterialR.attr.colorOnPrimaryContainer)
        val header = stack().apply {
            setPaddingRelative(dp(20), dp(18), dp(20), dp(20))
            addView(row().apply {
                addView(iconTile(current.icon, 40, 22, color(MaterialR.attr.colorSurface), ink),
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(text(getString(R.string.s_step, current.ordinal + 1, Step.entries.size),
                    R.style.TextAppearance_ezcall_LabelLarge, ink))
            })
            addView(text(getString(current.title), R.style.TextAppearance_ezcall_HeadlineMedium, ink)
                .apply { isAccessibilityHeading = true }, space(top = 14))
            addView(text(getString(current.detail), R.style.TextAppearance_ezcall_BodyLarge, ink), space(top = 6))
            addView(row().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                for (stage in Step.entries) {
                    addView(View(this@MainActivity).apply {
                        background = rounded(color(if (stage <= current) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorSurface), 2)
                    }, LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (stage != Step.SHARING) marginStart = dp(4) })
                }
            }, space(top = 18))
        }
        setupPanel.addView(surfaceCard(header, accent = true), space())
        when (current) {
            Step.SHARING -> {
                setupPanel.addView(walkthroughSharing, space(top = 12))
                setupActions.addView(actionButton(getString(R.string.s_continue_bluetooth), R.drawable.ic_bluetooth) {
                    moveTo(Step.LINK)
                }, space(top = 12))
            }
            Step.LINK -> {
                setupPanel.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
                setupActions.addView(textButton(getString(R.string.s_guide_audio_next)) { moveTo(Step.AUDIO) }, space(top = 8))
            }
            Step.AUDIO -> {
                val panel = stack()
                guideAudio = panel
                setupPanel.addView(surfaceCard(panel), space(top = 12))
                renderAudioPanel(panel, inGuide = true)
            }
        }
        if (current != Step.SHARING) setupActions.addView(textButton(getString(R.string.s_guide_back), ::previousStep), space())
        setupActions.addView(textButton(getString(if (current == Step.AUDIO) R.string.s_guide_finish else R.string.s_close_guide)) {
            moveTo(null)
        }, space())
    }

    private fun renderStatus() {
        val state = RelayState.current
        val paired = PairKey(this).exists()
        status.removeAllViews()
        status.addView(sectionTitle(getString(R.string.s_status)))
        status.addView(secondaryText(getString(R.string.s_status_intro)), space(top = 6))
        val overview = stack().apply {
            addView(statusRow(R.drawable.ic_bluetooth, connectionTitle(state, paired), connectionDetail(state, paired), state.connected))
            val shared = Feature.entries.count { it.active(this@MainActivity) }
            addView(statusRow(R.drawable.ic_link, getString(R.string.s_sharing), resources.getQuantityString(R.plurals.sender_sharing_count, shared, shared),
                shared > 0))
            addView(statusRow(R.drawable.ic_headset, getString(R.string.s_call_audio), audioTitle(), liveAudio() != null))
        }
        status.addView(surfaceCard(overview), space(top = 18))

        val phone = stack().apply {
            val alerts = notificationsEnabled()
            val screenOff = batteryExempt()
            addView(statusRow(R.drawable.ic_notifications, getString(R.string.s_relay_notifications),
                if (alerts) getString(R.string.s_allowed) else getString(R.string.s_android_off), alerts))
            addView(statusRow(R.drawable.ic_battery, getString(R.string.s_while_off),
                if (screenOff) getString(R.string.s_battery_off) else getString(R.string.s_battery_may_pause), screenOff))
        }
        status.addView(surfaceCard(phone), space(top = 10))
        if (state.connected && RelayState.simulateCall != null) {
            status.addView(textButton(getString(R.string.s_simulate)) {
                val message = if (RelayState.simulateCall?.invoke() == true) getString(R.string.s_simulate_wait)
                else getString(R.string.s_simulate_retry)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }, space(top = 12))
        }
    }

    private fun openSettings() {
        if (settingsSheet?.isShowing == true) return
        val dialog = BottomSheetDialog(this)
        settingsSheet = dialog
        val windowContent = stack()
        windowContent.addView(BottomSheetDragHandleView(this), space())
        windowContent.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(stack().apply {
                setPaddingRelative(dp(24), 0, dp(24), dp(20))
                addView(sectionTitle(getString(R.string.ez_settings)))
                addView(settingsRow(R.drawable.ic_devices, getString(R.string.s_guide), getString(R.string.s_reopen_guide)) {
                    dialog.dismiss()
                    navigation.selectedItemId = Page.CONNECTION.ordinal + 1
                    moveTo(Step.SHARING)
                }.view, space(top = 6))
                addView(settingsRow(R.drawable.ic_language, getString(R.string.ez_language), languageName()) {
                    dialog.dismiss()
                    showEzcallLanguage(getString(R.string.ez_language))
                }.view, space(top = 6))

                addView(sectionTitle(getString(R.string.s_call_audio), R.style.TextAppearance_ezcall_TitleLarge), space(top = 16))
                audioPanel = stack().apply { setPaddingRelative(0, 0, 0, dp(8)) }
                addView(surfaceCard(audioPanel), space(top = 8))

                addView(sectionTitle(getString(R.string.s_relay), R.style.TextAppearance_ezcall_TitleLarge), space(top = 20))
                settingsNotifications = settingsRow(R.drawable.ic_notifications, getString(R.string.s_relay_notifications), "") {
                    appSettings(getString(R.string.s_notifications_in_status))
                }
                settingsBattery = settingsRow(R.drawable.ic_battery, getString(R.string.s_battery_action), "", ::requestBatteryExemption)
                addView(settingsNotifications.view, space())
                addView(settingsBattery.view, space())
                val supportUrl = getString(R.string.support_url)
                if (supportUrl.startsWith("https://")) {
                    addView(textButton(getString(R.string.s_donate)) {
                        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl))
                        if (runCatching { startActivity(browser) }.isFailure) alert(getString(R.string.s_no_browser))
                    }, space(top = 10))
                }
            })
        }, space())
        dialog.setContentView(windowContent)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { if (settingsSheet === dialog) settingsSheet = null }
        renderSettingsStatus()
        dialog.show()
    }

    private fun renderSettingsStatus() {
        if (settingsSheet == null) return
        renderAudioPanel(audioPanel, inGuide = false)
        settingsNotifications.detail.text = if (notificationsEnabled()) getString(R.string.s_allowed) else getString(R.string.s_android_off)
        settingsBattery.detail.text = if (batteryExempt()) getString(R.string.s_battery_off)
            else getString(R.string.s_android_battery_setting)
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

    private fun renderAudio() {
        guideAudio?.let { renderAudioPanel(it, inGuide = true) }
        if (settingsSheet != null) renderAudioPanel(audioPanel, inGuide = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::setupPanel.isInitialized) renderAudio()
    }

    private fun renderAudioPanel(panel: LinearLayout, inGuide: Boolean) {
        val pairingSaved = WirelessPairing.isPaired(this)
        val canStart = pairingSaved || (CallAudio.Setup.enabled(this) && LocalAdb.hasKey())
        val title = audioTitle()
        val detail = when {
            audioBusy -> getString(R.string.s_audio_keep_wireless)
            liveAudio() == CallAudio.Status.TWO_WAY -> getString(R.string.s_audio_two_way_info)
            liveAudio() == CallAudio.Status.LISTEN -> getString(R.string.s_audio_listen_info)
            CallAudio.Setup.enabled(this) && RelayState.current.running -> getString(R.string.s_audio_restart_info)
            CallAudio.Setup.enabled(this) -> getString(R.string.s_audio_resume_info)
            else -> getString(R.string.s_audio_setup_info)
        }
        panel.removeAllViews()
        if (inGuide && (!Feature.CALLS.active(this) || !Feature.CONTROL.active(this) ||
                !hasPermission(permission.ANSWER_PHONE_CALLS))) {
            panel.addView(settingsRow(R.drawable.ic_answer, getString(R.string.s_call_control),
                getString(R.string.s_guide_enable_control)) { moveTo(Step.SHARING) }.view, space())
        }
        panel.addView(settingsRow(R.drawable.ic_link, getString(R.string.s_pair_wireless),
            getString(if (pairingSaved) R.string.s_pairing_saved else R.string.s_guide_wireless),
            ::showWirelessInstructions).view, space())
        panel.addView(MaterialDivider(this), space())
        panel.addView(statusRow(R.drawable.ic_headset, title, setupAudioError?.let { getString(it) } ?: detail, liveAudio() != null)
            .apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, space())
        val action = if (!canStart) R.string.s_pair_wireless else if (CallAudio.Setup.enabled(this)) R.string.s_restart_audio else R.string.s_setup_audio
        val button = actionButton(getString(action), if (canStart) R.drawable.ic_headset else R.drawable.ic_link) {
            if (canStart) setupAudio() else showWirelessInstructions()
        }
        panel.addView(button.apply { isEnabled = !audioBusy }, space(top = 4).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(12)
        })
        if (setupAudioError == R.string.s_audio_port_missing || setupAudioError == R.string.s_audio_failure) {
            panel.addView(settingsRow(R.drawable.ic_link, getString(R.string.s_audio_enter_port),
                getString(R.string.s_audio_port_help), ::showAudioPort).view, space())
        }
        if (inGuide) {
            panel.addView(statusRow(R.drawable.ic_devices, getString(R.string.s_guide_receiver_mic),
                getString(R.string.s_guide_receiver_mic_detail), false), space())
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
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.s_audio_port)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.s_audio_enter_port)
            .setMessage(R.string.s_audio_port_help)
            .setView(stack().apply { setPadding(dp(24), 0, dp(24), 0); addView(input) })
            .setNegativeButton(R.string.ez_cancel, null)
            .setNeutralButton(R.string.s_open_dev) { _, _ -> openDeveloperOptions() }
            .setPositiveButton(R.string.ez_continue, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val port = LocalAdb.parseConnectPort(input.text)
            if (port == null) input.error = getString(R.string.s_audio_port_invalid)
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
        renderStatus()
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
                if (page == Page.STATUS) renderStatus()
            }
        }.start()
    }

    private fun refreshSharing() {
        RelayState.refreshSharing?.invoke()
        renderSharing()
        if (page == Page.STATUS) renderStatus()
    }

    private fun ensureBluetooth() {
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
            adapter == null || !adapter.isMultipleAdvertisementSupported ->
                alert(getString(R.string.s_no_advertising))
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
        if (runCatching { startForegroundService(serviceIntent()) }.isFailure) {
            alert(getString(R.string.s_relay_open_app))
        }
    }

    @Deprecated("Deprecated in Android framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ENABLE_BLUETOOTH && resultCode == RESULT_OK) ensureBluetooth()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            NEARBY -> {
                if (permissions.isNotEmpty() && permissions.all { hasPermission(it) }) ensureBluetooth()
                else appSettings(getString(R.string.s_nearby_pair))
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
        SHARING(R.string.s_sharing, R.drawable.ic_devices),
        STATUS(R.string.s_status, R.drawable.ic_status)
    }

    private fun row() = LinearLayout(this).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    private fun sectionTitle(value: String, appearance: Int = R.style.TextAppearance_ezcall_HeadlineMedium) =
        text(value, appearance).apply { isAccessibilityHeading = true }

    private fun secondaryText(value: String) = text(value, R.style.TextAppearance_ezcall_BodyLarge,
        color(MaterialR.attr.colorOnSurfaceVariant))

    private fun surfaceCard(content: View, accent: Boolean = false) = MaterialCardView(this).apply {
        radius = dp(24).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(if (accent) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurface))
        setStrokeColor(ColorStateList.valueOf(color(MaterialR.attr.colorOutlineVariant)))
        strokeWidth = if (accent) 0 else dp(1)
        addView(content)
    }

    private fun actionButton(label: String, icon: Int, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        minimumHeight = dp(54)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        setIconResource(icon)
        setOnClickListener { action() }
    }

    private data class SettingsRow(val view: LinearLayout, val detail: MaterialTextView)

    private fun settingsRow(icon: Int, title: String, detail: String, action: () -> Unit): SettingsRow {
        val view = statusRow(icon, title, detail, false).apply {
            val highlight = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, highlight, true)
            setBackgroundResource(highlight.resourceId)
            isFocusable = true
            setOnClickListener { action() }
        }
        val description = (view.getChildAt(1) as LinearLayout).getChildAt(1) as MaterialTextView
        return SettingsRow(view, description)
    }

    private fun statusRow(icon: Int, title: String, detail: String, active: Boolean) = row().apply {
        minimumHeight = dp(68)
        setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        isScreenReaderFocusable = true
        val fill = color(if (active) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainer)
        val ink = color(if (active) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant)
        addView(iconTile(icon, 42, 22, fill, ink), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        addView(stack().apply {
            addView(text(title, R.style.TextAppearance_ezcall_TitleMedium))
            addView(text(detail, R.style.TextAppearance_ezcall_BodyMedium,
                color(MaterialR.attr.colorOnSurfaceVariant)), space(top = 2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun textButton(label: String, action: () -> Unit) =
        MaterialButton(ContextThemeWrapper(this, R.style.ThemeOverlay_ezcall_TextButton)).apply {
            text = label
            minimumHeight = dp(48)
            setOnClickListener { action() }
        }

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

    private fun iconTile(icon: Int, size: Int, glyph: Int, fill: Int, ink: Int) = ImageView(this).apply {
        val inset = dp((size - glyph) / 2)
        setPadding(inset, inset, inset, inset)
        setImageResource(icon)
        background = rounded(fill, if (size == 52) 16 else 12)
        imageTintList = ColorStateList.valueOf(ink)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val NEARBY = 1
        const val NOTICE = 2
        const val SHARE = 3
        const val ENABLE_BLUETOOTH = 4
        const val WIRELESS_NOTICE = 5
    }
}
