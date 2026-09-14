package com.chenyc.hyperpods.utils.miuiStrongToast.data

object HyperPodsPrefsKey {
    const val EAR_DETECTION = "ear_detection"
    const val EAR_DETECTION_SWITCH_SPEAKER = "ear_detection_switch_speaker"

    // ---- 水月雨（MOONDROP）配置键 ----
    // 与 OPPO 侧同用一个 RemotePreferences 组，键名互不重叠。
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
