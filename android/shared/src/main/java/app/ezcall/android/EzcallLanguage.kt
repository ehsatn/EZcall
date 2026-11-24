
package app.ezcall.android

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal fun AppCompatActivity.languageName() =
    if (resources.configuration.locales[0].language == "fa") "فارسی" else "English"

internal fun AppCompatActivity.showEzcallLanguage(title: String) {
    val tags = arrayOf("en", "fa")
    val labels = arrayOf("English", "فارسی")
    val current = tags.indexOf(resources.configuration.locales[0].language).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this).setTitle(title)
        .setSingleChoiceItems(labels, current) { dialog, index ->
            dialog.dismiss()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[index]))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
