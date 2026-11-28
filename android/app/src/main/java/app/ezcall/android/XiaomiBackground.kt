package app.ezcall.android

import android.content.ComponentName
import android.content.Intent
import android.os.Build

/**
 * Xiaomi, Redmi and POCO phones restrict background apps through their own Autostart and battery saver settings,
 * separately from Android's battery optimization. A relay left restricted there stops reaching the receiving phone
 * while the screen is off.
 */
internal object XiaomiBackground {
    fun applies(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND) =
        manufacturer.equals("Xiaomi", ignoreCase = true) ||
            brand.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun autostart(): Intent = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))

    fun batterySaver(packageName: String, label: String): Intent = Intent().setComponent(
        ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", packageName).putExtra("package_label", label)
}
