package app.ezcall.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class Support(private val activity: AppCompatActivity) {
    private val prefs = activity.getSharedPreferences("support", Context.MODE_PRIVATE)
    private val firstOpened = prefs.getLong("firstOpened", 0).takeIf { it > 0 }
        ?: System.currentTimeMillis().also { prefs.edit { putLong("firstOpened", it) } }
    private var reminder: AlertDialog? = null

    fun remind(
        @StringRes message: Int,
        @StringRes viewOptions: Int,
        @StringRes decline: Int,
        open: () -> Unit
    ) {
        if (!activity.hasWindowFocus() || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!reminderDue(System.currentTimeMillis(), firstOpened, prefs.getBoolean("seen", false))) return
        reminder = MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(viewOptions) { _, _ -> open() }
            .setNegativeButton(decline, null)
            .show()
        markSeen()
    }

    fun dismissReminder() {
        reminder?.dismiss()
        reminder = null
    }

    fun open(@LayoutRes layout: Int, screen: Class<out SupportActivity> = SupportActivity::class.java) {
        markSeen()
        activity.startActivity(Intent(activity, screen).putExtra("layout", layout))
    }

    private fun markSeen() {
        prefs.edit { putBoolean("seen", true) }
    }

    companion object {
        internal const val REMINDER_DELAY = 48 * 60 * 60 * 1000L

        internal fun reminderDue(now: Long, firstOpened: Long, seen: Boolean) =
            !seen && firstOpened > 0 && now - firstOpened >= REMINDER_DELAY
    }
}

internal open class SupportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = intent.getIntExtra("layout", 0)
        if (layout == 0) {
            finish()
            return
        }
        val screen = layoutInflater.inflate(layout, null)
        setContentView(screen)
        ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        screen.findViewWithTag<MaterialToolbar>("supportToolbar").setNavigationOnClickListener { finish() }
        val feedback = screen.findViewById<MaterialButton>(android.R.id.button1)
        val star = screen.findViewWithTag<MaterialButton>("supportStar")
        supportLink(feedback, "https://github.com/ehsatn/EZcall/issues")
        supportLink(star, "https://github.com/ehsatn/EZcall")
        customize(screen, feedback, star)
    }

    private fun supportLink(button: MaterialButton, url: String) {
        button.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (_: ActivityNotFoundException) {
                button.text = button.hint
            }
        }
    }

    protected open fun customize(screen: View, feedback: MaterialButton, star: MaterialButton) = Unit
}

internal class SupportCopyButton(context: Context, attrs: AttributeSet) : MaterialButton(context, attrs) {
    init {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener {
            val address = tag as CharSequence
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(null, address))
            text = hint
            icon = null
        }
    }
}
