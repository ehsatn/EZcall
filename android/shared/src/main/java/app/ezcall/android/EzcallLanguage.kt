
package app.ezcall.android

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

private val languages = linkedMapOf(
    "en" to "English", "fa" to "فارسی", "ar" to "العربية", "es" to "Español",
    "fr" to "Français", "tr" to "Türkçe", "it" to "Italiano"
)

class EzcallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags("en")
        } else {
            val saved = LocaleManagerCompat.getApplicationLocales(this)
            AppCompatDelegate.setApplicationLocales(if (saved.isEmpty) LocaleListCompat.forLanguageTags("en") else saved)
        }
    }
}

internal fun Context.ezcallContext(): Context {
    val locales = if (Build.VERSION.SDK_INT < 33) AppCompatDelegate.getApplicationLocales()
        else LocaleManagerCompat.getApplicationLocales(this)
    val locale = locales[0] ?: Locale.ENGLISH
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}

internal fun AppCompatActivity.languageName() =
    languages[resources.configuration.locales[0].language] ?: languages.getValue("en")

internal fun AppCompatActivity.showEzcallLanguage(title: String) {
    val tags = languages.keys.toList()
    val labels = languages.values.toTypedArray()
    val current = tags.indexOf(resources.configuration.locales[0].language).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this).setTitle(title)
        .setSingleChoiceItems(labels, current) { dialog, index ->
            dialog.dismiss()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[index]))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
