package com.chenyc.hyperpods.pods.moondrop

/** 连接后探测到的能力。凡是从位图/真实回包得到的信息都放这里。 */
data class MoondropCapabilities(
    /** GET_SUPPORTED_FEATURES 位图（可含多页） */
    val features: Set<Int> = emptySet(),
    /** 实际使用的 ANC 路径（探测结果优先于型号档案） */
    val ancPath: Int = MoondropGaia.ANC_PATH_UNKNOWN,
    /** 设备真实上报的电池类型（cmd 0 的结果） */
    val batteryTypes: List<Int> = emptyList(),
    val hasGain: Boolean = false,
    val hasLed: Boolean = false,
    val hasSpatial: Boolean = false,
    val hasHeadTracking: Boolean = false,
    /** 提示音：能力位图含 F_VOICE 或收到过响应 */
    val hasPromptTone: Boolean = false,
    val hasPromptVolume: Boolean = false,
    /** LHDC 开关：位图含 F_CODEC_TYPE */
    val hasLhdc: Boolean = false,
    /** 双设备连接：位图含 F_ONEBRINGTWO */
    val hasDualConnection: Boolean = false,
    /**
     * 手势操作（feature 22 TOUCHV2）：位图含 F_TOUCHV2。
     *
     * ⚠ 只由**能力位图**决定（本机型不使用 feature 11 GESTURE_CONFIGURATION，
     * 也不做型号档案标记）。
     */
    val hasGestures: Boolean = false,
    /** 探测是否已经完成（避免 UI 在探测中途闪来闪去） */
    val probed: Boolean = false,
) {
    fun supports(feature: Int): Boolean = features.contains(feature)

    override fun equals(other: Any?): Boolean {
        if (other !is MoondropCapabilities) return false
        return features == other.features && ancPath == other.ancPath &&
            batteryTypes == other.batteryTypes && hasGain == other.hasGain &&
            hasLed == other.hasLed && hasSpatial == other.hasSpatial &&
            hasHeadTracking == other.hasHeadTracking && hasPromptTone == other.hasPromptTone &&
            hasPromptVolume == other.hasPromptVolume && hasLhdc == other.hasLhdc &&
            hasDualConnection == other.hasDualConnection && hasGestures == other.hasGestures &&
            probed == other.probed
    }

    override fun hashCode(): Int = features.hashCode() * 31 + ancPath
}
