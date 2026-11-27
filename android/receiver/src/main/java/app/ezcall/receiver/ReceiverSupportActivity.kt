package app.ezcall.receiver

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import app.ezcall.android.SupportActivity
import com.google.android.material.R as M
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class ReceiverSupportActivity : SupportActivity() {
    private val onCall = { presentRingingCall() }

    override fun onStart() {
        super.onStart()
        ReceiverState.listeners.add(onCall)
    }

    override fun onPostResume() {
        super.onPostResume()
        onCall()
    }

    override fun onStop() {
        ReceiverState.listeners.remove(onCall)
        super.onStop()
    }

    override fun customize(screen: View, feedback: MaterialButton, star: MaterialButton) {
        val content = feedback.parent as LinearLayout
        val toolbar = screen.findViewWithTag<MaterialToolbar>("supportToolbar")
        toolbar.setTitle(R.string.r_call_brand)
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ezcall_icon_mark)
            background = GradientDrawable().apply {
                setColor(getColor(R.color.launcher_background))
                cornerRadius = dp(12).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, Toolbar.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
        content.findViewWithTag<TextView>("supportHeading").apply {
            setText(R.string.ez_support_title)
            setTextAppearance(R.style.TextAppearance_ezcall_HeadlineLarge)
        }
        val intro = content.findViewWithTag<TextView>("supportIntro").apply { setText(R.string.r_support_intro) }
        content.removeView(feedback)
        content.removeView(star)
        content.removeView(content.findViewWithTag("supportFeedbackNote"))
        content.removeView(content.findViewWithTag("supportFeedbackDivider"))
        val communityIndex = content.indexOfChild(intro) + 1
        content.addView(section(R.string.r_community_support), communityIndex)
        content.addView(MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(tone(M.attr.colorSurfaceContainerLow))
            addView(LinearLayout(this@ReceiverSupportActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
                addView(action(R.string.r_recommend, R.string.r_recommend_detail, R.drawable.ic_support) {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                        "https://ehsatn.github.io/EZcall/en.html")
                    startActivity(Intent.createChooser(share, getString(R.string.r_recommend)))
                })
                addView(action(R.string.r_rate, R.string.r_rate_detail, android.R.drawable.btn_star_big_on) {
                    MaterialAlertDialogBuilder(this@ReceiverSupportActivity)
                        .setTitle(R.string.r_rate)
                        .setMessage(R.string.r_rate_stores_unavailable)
                        .setPositiveButton(R.string.ez_support_feedback_action) { _, _ -> feedback.performClick() }
                        .setNeutralButton(R.string.r_open_beta) { _, _ ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, "https://testflight.apple.com/join/gRnJCZ5E".toUri()))
                            } catch (_: ActivityNotFoundException) {
                                feedback.text = feedback.hint
                            }
                        }
                        .setNegativeButton(R.string.ez_cancel, null)
                        .show()
                })
                addView(star, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(feedback, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            })
        }, communityIndex + 1, LinearLayout.LayoutParams(-1, -2))
        content.addView(section(R.string.r_financial_support), communityIndex + 2)
        (content.getChildAt(communityIndex + 3).layoutParams as LinearLayout.LayoutParams).topMargin = 0
    }

    private fun section(title: Int) = TextView(this).apply {
        setText(title)
        setTextAppearance(R.style.TextAppearance_ezcall_TitleSmall)
        setTextColor(tone(androidx.appcompat.R.attr.colorPrimary))
        isAccessibilityHeading = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(8) }
    }

    private fun action(title: Int, detail: Int, icon: Int, click: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPaddingRelative(dp(4), dp(6), dp(4), dp(6))
        val selectable = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
        setBackgroundResource(selectable.resourceId)
        isFocusable = true
        isScreenReaderFocusable = true
        setOnClickListener { click() }
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(tone(M.attr.colorOnPrimaryContainer))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setText(title)
                setTextAppearance(R.style.TextAppearance_ezcall_TitleMedium)
            })
            addView(TextView(context).apply {
                setText(detail)
                setTextAppearance(R.style.TextAppearance_ezcall_BodyMedium)
                setTextColor(tone(M.attr.colorOnSurfaceVariant))
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun tone(attr: Int) = MaterialColors.getColor(this, attr, 0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
