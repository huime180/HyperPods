package com.chenyc.hyperpods.utils.miuiStrongToast.data

import android.content.Intent
import android.content.SharedPreferences

data class NotificationSettings(
    val showConnectionBatteryIsland: Boolean = HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND,
    val temporaryBatteryIslandDurationSeconds: Int =
        HyperPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
    val showConnectionPopup: Boolean = HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP,
    val connectionPopupDismissSeconds: Int = HyperPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS,
    val showConnectionNotification: Boolean = HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION,
    val notificationIslandStyle: Boolean = HyperPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE,
    val updatedAt: Long = 0L
) {
    val showNotificationAsIsland: Boolean
        get() = showConnectionNotification && notificationIslandStyle

    fun putExtras(intent: Intent) {
        intent.putExtra(HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, showConnectionBatteryIsland)
        intent.putExtra(
            HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
            temporaryBatteryIslandDurationSeconds
        )
        intent.putExtra(HyperPodsPrefsKey.SHOW_CONNECTION_POPUP, showConnectionPopup)
        intent.putExtra(HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, connectionPopupDismissSeconds)
        intent.putExtra(HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, showConnectionNotification)
        intent.putExtra(HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, notificationIslandStyle)
        intent.putExtra(HyperPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT, updatedAt)
    }

    fun writeToPrefs(prefs: SharedPreferences, commit: Boolean = false): Boolean {
        val editor = prefs.edit()
        editor.putBoolean(HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, showConnectionBatteryIsland)
        editor.putInt(
            HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
            temporaryBatteryIslandDurationSeconds
        )
        editor.putBoolean(HyperPodsPrefsKey.SHOW_CONNECTION_POPUP, showConnectionPopup)
        editor.putInt(HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, connectionPopupDismissSeconds)
        editor.putBoolean(HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, showConnectionNotification)
        editor.putBoolean(HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, notificationIslandStyle)
        editor.putLong(HyperPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT, updatedAt)
        return if (commit) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    fun withUpdatedAtIfMissing(now: Long = System.currentTimeMillis()): NotificationSettings {
        return if (updatedAt > 0L) this else copy(updatedAt = now)
    }

    companion object {
        private val PREF_KEYS = listOf(
            HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
            HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
            HyperPodsPrefsKey.SHOW_CONNECTION_POPUP,
            HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
            HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
            HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
            HyperPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT
        )

        fun fromPrefs(prefs: SharedPreferences): NotificationSettings {
            return NotificationSettings(
                showConnectionBatteryIsland = prefs.getBoolean(
                    HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                    HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND
                ),
                temporaryBatteryIslandDurationSeconds = prefs.getInt(
                    HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
                    HyperPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
                ).normalizedTemporaryBatteryIslandDurationSeconds(),
                showConnectionPopup = prefs.getBoolean(
                    HyperPodsPrefsKey.SHOW_CONNECTION_POPUP,
                    HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP
                ),
                connectionPopupDismissSeconds = prefs.getInt(
                    HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    HyperPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
                ).normalizedPopupDismissSeconds(),
                showConnectionNotification = prefs.getBoolean(
                    HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                    HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION
                ),
                notificationIslandStyle = prefs.getBoolean(
                    HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                    HyperPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
                ),
                updatedAt = prefs.getLong(HyperPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT, 0L)
            )
        }

        fun fromPrefsOrNull(prefs: SharedPreferences): NotificationSettings? {
            return if (PREF_KEYS.any { prefs.contains(it) }) fromPrefs(prefs) else null
        }

        fun fromIntent(intent: Intent?, fallback: NotificationSettings): NotificationSettings {
            if (intent == null) return fallback
            return NotificationSettings(
                showConnectionBatteryIsland = intent.getBooleanExtra(
                    HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                    fallback.showConnectionBatteryIsland
                ),
                temporaryBatteryIslandDurationSeconds = intent.getIntExtra(
                    HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
                    fallback.temporaryBatteryIslandDurationSeconds
                ).normalizedTemporaryBatteryIslandDurationSeconds(),
                showConnectionPopup = intent.getBooleanExtra(
                    HyperPodsPrefsKey.SHOW_CONNECTION_POPUP,
                    fallback.showConnectionPopup
                ),
                connectionPopupDismissSeconds = intent.getIntExtra(
                    HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    fallback.connectionPopupDismissSeconds
                ).normalizedPopupDismissSeconds(),
                showConnectionNotification = intent.getBooleanExtra(
                    HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                    fallback.showConnectionNotification
                ),
                notificationIslandStyle = intent.getBooleanExtra(
                    HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                    fallback.notificationIslandStyle
                ),
                updatedAt = intent.getLongExtra(
                    HyperPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT,
                    fallback.updatedAt
                )
            )
        }

        fun newerOf(first: NotificationSettings, second: NotificationSettings?): NotificationSettings {
            return if (second != null && second.updatedAt > first.updatedAt) second else first
        }

        private fun Int.normalizedPopupDismissSeconds(): Int {
            return if (this in HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECOND_OPTIONS) {
                this
            } else {
                HyperPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
            }
        }

        private fun Int.normalizedTemporaryBatteryIslandDurationSeconds(): Int {
            return if (this in HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECOND_OPTIONS) {
                this
            } else {
                HyperPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
            }
        }
    }
}
