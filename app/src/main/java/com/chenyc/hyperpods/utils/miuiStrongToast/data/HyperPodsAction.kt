package com.chenyc.hyperpods.utils.miuiStrongToast.data

object HyperPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.hyperpods.ui_init"
    const val ACTION_PODS_CONNECTED = "chen.action.hyperpods.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "chen.action.hyperpods.pods_disconnected"
    const val ACTION_PODS_BATTERY_CHANGED = "chen.action.hyperpods.pods_battery_changed"
    const val ACTION_ANC_SELECT = "chen.action.hyperpods.anc_select"
    const val ACTION_PODS_ANC_CHANGED = "chen.action.hyperpods.pods_anc_select"
    const val ACTION_GET_PODS_MAC = "chen.action.hyperpods.get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = "chen.action.hyperpods.pods_mac_received"
    const val ACTION_REFRESH_STATUS = "chen.action.hyperpods.refresh_status"
    const val ACTION_GAME_MODE_SET = "chen.action.hyperpods.game_mode_set"
    const val ACTION_PODS_GAME_MODE_CHANGED = "chen.action.hyperpods.pods_game_mode_changed"
    const val ACTION_EQ_PRESET_SET = "chen.action.hyperpods.eq_preset_set"
    const val ACTION_PODS_EQ_PRESET_CHANGED = "chen.action.hyperpods.pods_eq_preset_changed"
    const val ACTION_EQ_PRESET_SAVE = "chen.action.hyperpods.eq_preset_save"
    const val ACTION_EQ_PRESET_DELETE = "chen.action.hyperpods.eq_preset_delete"
    const val ACTION_SPATIAL_AUDIO_SET = "chen.action.hyperpods.spatial_audio_set"
    const val ACTION_PODS_SPATIAL_AUDIO_CHANGED = "chen.action.hyperpods.pods_spatial_audio_changed"
    const val ACTION_SPATIAL_SOUND_SET = "chen.action.hyperpods.spatial_sound_set"
    const val ACTION_PODS_SPATIAL_SOUND_CHANGED = "chen.action.hyperpods.pods_spatial_sound_changed"
    const val ACTION_NOISE_LEVEL_SET = "chen.action.hyperpods.noise_level_set"
    const val ACTION_PODS_NOISE_LEVEL_CHANGED = "chen.action.hyperpods.pods_noise_level_changed"
    const val ACTION_PODS_SMART_ANC_LEVEL_CHANGED = "chen.action.hyperpods.pods_smart_anc_level_changed"
    const val ACTION_AUTO_PLAY_PAUSE_SET = "chen.action.hyperpods.auto_play_pause_set"
    const val ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED = "chen.action.hyperpods.pods_auto_play_pause_changed"
    const val ACTION_DUAL_DEVICE_SET = "chen.action.hyperpods.dual_device_set"
    const val ACTION_PODS_DUAL_DEVICE_CHANGED = "chen.action.hyperpods.pods_dual_device_changed"
    const val ACTION_PODS_CONNECTED_DEVICES_CHANGED = "chen.action.hyperpods.pods_connected_devices_changed"
    const val ACTION_CYCLE_ANC = "chen.action.hyperpods.cycle_anc"
    const val ACTION_NOTIFICATION_SETTINGS_CHANGED = "chen.action.hyperpods.notification_settings_changed"
    const val ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED = "chen.action.hyperpods.milink_spatial_audio_option_changed"
    // 自定义按钮（MiLink 面板劫持控件）功能变更广播（App → com.milink.service）
    const val ACTION_CUSTOM_BUTTON_FUNCTION_CHANGED = "chen.action.hyperpods.custom_button_function_changed"
    // 自定义按钮位置变更广播（App → com.milink.service）
    const val ACTION_CUSTOM_BUTTON_POSITION_CHANGED = "chen.action.hyperpods.custom_button_position_changed"
    // 当前设备配置档变更广播（App → com.android.bluetooth），携带 EXTRA_PROFILE_JSON
    const val ACTION_ACTIVE_PROFILE_CHANGED = "chen.action.hyperpods.active_profile_changed"
    // RFCOMM 按 productId 精确识别后的配置档（com.android.bluetooth → App）
    const val ACTION_PODS_PROFILE_CHANGED = "chen.action.hyperpods.pods_profile_changed"

    const val EXTRA_PROFILE_JSON = "profile_json"
    const val EXTRA_EQ_ENTRIES_JSON = "eq_entries_json"
    const val EXTRA_ALLOW_RFCOMM_RECONNECT = "allow_rfcomm_reconnect"
    const val EXTRA_RFCOMM_CONNECTED = "rfcomm_connected"

    const val ACTION_BT_LOG_ENTRY = "chen.action.hyperpods.bt_log_entry"
    const val EXTRA_BT_LOG_IS_SEND = "is_send"
    const val EXTRA_BT_LOG_HEX = "hex"
    const val EXTRA_BT_LOG_LABEL = "label"

    // ── 水月雨（MOONDROP / GAIA）────────────────────────────────────────────
    // 方向总览（与 OPPO 侧共用同一份契约，字符串带 .moondrop. 段以便在被注入进程里区分来源）：
    //   com.android.bluetooth --PODS_CONNECTED/DISCONNECTED--> com.android.settings / com.milink.service
    //   com.android.bluetooth --BATTERY_CHANGED/ANC_CHANGED--> 被注入进程（伪装的原生耳机页与融合设备中心）
    //   com.android.bluetooth --UPDATE_PODS_NOTIFICATION/SEND_STRONG_TOAST--> com.xiaomi.bluetooth（通知/超级岛）
    //   应用 UI --*_SELECT / GESTURE_SELECT / UI_INIT--> com.android.bluetooth（协议栈所在进程）
    //   应用 UI --RESTART_SCOPE--> 5 个被注入进程（各自退出，由系统重新拉起后重新注入）
    const val SHOW_UI = "chen.action.hyperpods.moondrop.show_ui"
    const val SHOW_POPUP = "chen.action.hyperpods.moondrop.show_popup"
    const val GET_PODS_MAC = "chen.action.hyperpods.moondrop.get_pods_mac"
    const val PODS_MAC_RECEIVED = "chen.action.hyperpods.moondrop.get_pods_mac"
    const val PODS_CONNECTED = "chen.action.hyperpods.moondrop.pods_connected"
    const val PODS_DISCONNECTED = "chen.action.hyperpods.moondrop.pods_disconnected"
    const val BATTERY_CHANGED = "chen.action.hyperpods.moondrop.battery_changed"
    const val ANC_CHANGED = "chen.action.hyperpods.moondrop.anc_changed"
    const val GAIN_CHANGED = "chen.action.hyperpods.moondrop.gain_changed"
    const val LED_CHANGED = "chen.action.hyperpods.moondrop.led_changed"
    const val PROMPT_TONE_CHANGED = "chen.action.hyperpods.moondrop.prompt_tone_changed"
    const val PROMPT_VOLUME_CHANGED = "chen.action.hyperpods.moondrop.prompt_volume_changed"
    const val LHDC_CHANGED = "chen.action.hyperpods.moondrop.lhdc_changed"
    const val DUAL_CONNECTION_CHANGED = "chen.action.hyperpods.moondrop.dual_connection_changed"
    const val GESTURE_CHANGED = "chen.action.hyperpods.moondrop.gesture_changed"
    const val CODEC_CHANGED = "chen.action.hyperpods.moondrop.codec_changed"
    const val CAPABILITIES_CHANGED = "chen.action.hyperpods.moondrop.capabilities_changed"
    const val DEBUG_LOG = "chen.action.hyperpods.moondrop.debug_log"
    const val UI_INIT = "chen.action.hyperpods.moondrop.ui_init"
    const val ANC_SELECT = "chen.action.hyperpods.moondrop.anc_select"
    const val GESTURE_SELECT = "chen.action.hyperpods.moondrop.gesture_select"
    const val GAIN_SELECT = "chen.action.hyperpods.moondrop.gain_select"
    const val LED_SELECT = "chen.action.hyperpods.moondrop.led_select"
    const val PROMPT_TONE_SELECT = "chen.action.hyperpods.moondrop.prompt_tone_select"
    const val PROMPT_VOLUME_SELECT = "chen.action.hyperpods.moondrop.prompt_volume_select"
    const val LHDC_SELECT = "chen.action.hyperpods.moondrop.lhdc_select"
    const val DUAL_CONNECTION_SELECT = "chen.action.hyperpods.moondrop.dual_connection_select"
    const val REQUEST_CAPABILITIES = "chen.action.hyperpods.moondrop.request_capabilities"
    const val REQUEST_BATTERY = "chen.action.hyperpods.moondrop.request_battery"
    const val REQUEST_GESTURE = "chen.action.hyperpods.moondrop.request_gesture"
    const val SEND_STRONG_TOAST = "chen.action.hyperpods.moondrop.sendstrongtoast"
    const val UPDATE_PODS_NOTIFICATION = "chen.action.hyperpods.moondrop.updatepodsnotification"
    const val CANCEL_PODS_NOTIFICATION = "chen.action.hyperpods.moondrop.cancelpodsnotification"
    const val UPDATE_SYSTEM_BATTERY = "chen.action.hyperpods.moondrop.update_system_battery"
    const val RESTART_SCOPE = "chen.action.hyperpods.moondrop.restart_scope"
    const val EXTRA_STATUS = "status"
    const val EXTRA_BATTERY = "batteryParams"
    const val EXTRA_DEVICE = "device"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_MAC = "mac"
    const val EXTRA_LEVEL = "level"
    const val EXTRA_PROMPT_VOLUME_RAW = "prompt_volume_raw"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_CODEC = "codec"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_GESTURE_PAYLOAD = "gesture_payload"
    const val EXTRA_GESTURE_SLOT = "gesture_slot"
    const val EXTRA_GESTURE_EAR = "gesture_ear"

    /**
     * CAPABILITIES_CHANGED 携带的能力包（Bundle）。
     *
     * 应用侧要按「这台耳机到底有什么」决定显示哪些功能页/控件，所以能力必须一起发过来；
     * 键名见 MoondropController.publishCapabilities()。
     */
    const val EXTRA_CAPS_BUNDLE = "caps_bundle"
    /** 型号中文名（String），用于标题与能力页展示。 */
    const val EXTRA_MODEL_NAME = "model_name"
    /**
     * 降噪档位标识列表（StringArrayList，值为 MOONDROP 侧 AncMode.id）。
     *
     * 应用侧用它把「第几个档位」翻译成界面上的 OFF / 降噪 / 通透 / 自适应，
     * 也用它把用户点选的模式反查回档位下标再发 ANC_SELECT。
     */
    const val EXTRA_ANC_IDS = "anc_ids"
}
