package app.ezcall.android

import android.Manifest.permission

internal object SharingPermissions {
    val nearby = arrayOf(permission.BLUETOOTH_ADVERTISE, permission.BLUETOOTH_CONNECT)

    fun missing(
        selected: Set<Feature>, includeNearby: Boolean, includeNotifications: Boolean,
        hasPermission: (String) -> Boolean
    ): Array<String> = buildSet {
        selected.forEach { addAll(it.permissions) }
        if (includeNearby) addAll(nearby)
        if (includeNotifications) add(permission.POST_NOTIFICATIONS)
    }.filterNot(hasPermission).toTypedArray()

    fun granted(selected: Set<Feature>, alertsActive: Boolean, hasPermission: (String) -> Boolean): Set<Feature> {
        val granted = selected.filterTo(mutableSetOf()) { it.permissions.any(hasPermission) }
        if (!alertsActive && Feature.CALLS !in granted) granted.removeAll { it.needsCallAlerts }
        return granted
    }
}
