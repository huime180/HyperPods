package com.chenyc.hyperpods.utils.miuiStrongToast.data

object HyperPodsPrefsKey {
    const val EAR_DETECTION = "ear_detection"
    const val EAR_DETECTION_SWITCH_SPEAKER = "ear_detection_switch_speaker"
    const val SHOW_CONNECTION_BATTERY_ISLAND = "show_connection_battery_island"
    const val TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS = "temporary_battery_island_duration_seconds"
    const val SHOW_CONNECTION_POPUP = "show_connection_popup"
    const val CONNECTION_POPUP_DISMISS_SECONDS = "connection_popup_dismiss_seconds"
    const val SHOW_CONNECTION_NOTIFICATION = "show_connection_notification"
    const val NOTIFICATION_ISLAND_STYLE = "notification_island_style"
    const val NOTIFICATION_SETTINGS_UPDATED_AT = "notification_settings_updated_at"
    const val NOTIFICATION_SETTINGS_CACHE_PREFS_NAME = "hyperpods_notification_settings_cache"
    const val MILINK_SPATIAL_AUDIO_OPTION_ENABLED = "milink_spatial_audio_option_enabled"
    /** 配置来源模式：见 ProfileMode（auto / model）。 */
    const val PROFILE_MODE = "profile_mode"
    /** 手动指定型号时的白名单 productId。 */
    const val SELECTED_MODEL_ID = "selected_model_id"

    const val DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND = true
    const val DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS = 4
    val TEMPORARY_BATTERY_ISLAND_DURATION_SECOND_OPTIONS = listOf(1, 3, 4, 5, 10)
    const val DEFAULT_SHOW_CONNECTION_POPUP = false
    const val DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS = 8
    val CONNECTION_POPUP_DISMISS_SECOND_OPTIONS = listOf(3, 5, 8, 10, 15, 30)
    const val DEFAULT_SHOW_CONNECTION_NOTIFICATION = true
    const val DEFAULT_NOTIFICATION_ISLAND_STYLE = false
    const val DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED = true

    // ── 水月雨（MOONDROP / GAIA）────────────────────────────────────────────
    // 与 OPPO 侧同用一个 RemotePreferences 组（hyperpods_settings），键名互不重叠：
    // OPPO 侧偏「面板/弹窗/空间音频」开关，水月雨侧偏「型号档案/命令号覆盖/显示项」。
    const val ENABLE = "enable"
    const val MODEL_ID = "model_id"
    const val MODEL_MODE = "model_mode" // auto | manual
    const val ANC_MAP_CUSTOM = "anc_map_custom"
    const val VOICE_CMD_GET_ENABLE = "voice_cmd_get_enable"
    const val VOICE_CMD_SET_ENABLE = "voice_cmd_set_enable"
    const val VOICE_CMD_GET_VOLUME = "voice_cmd_get_volume"
    const val VOICE_CMD_SET_VOLUME = "voice_cmd_set_volume"
    const val VOICE_VOLUME_MAX = "voice_volume_max"
    const val SHOW_NOTIFICATION = "show_notification"
    const val SHOW_STRONG_TOAST = "show_strong_toast"
    const val SHOW_FOCUS_ISLAND = "show_focus_island"
    const val DEBUG_LOG = "debug_log"
}
