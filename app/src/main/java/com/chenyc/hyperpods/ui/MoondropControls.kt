package com.chenyc.hyperpods.ui

import android.os.Bundle
import androidx.compose.runtime.Immutable

/**
 * 耳机页上的水月雨（GAIA）功能项：能力位 + 当前值 + 命令回调。
 *
 * 为什么打包成一个载体而不是十几个参数：这些控件要从 MainUI 一路透传
 * MainTabs → EarphonesTabShell → EarphonesTabPage → PodDetailPage 五层，
 * 逐个加参数既容易漏、也让签名难以维护；打包后每层只多传一个参数。
 *
 * 显示与否一律看能力位（[supports]）——「这台耳机有什么」由蓝牙进程探测后广播过来，
 * 界面不做任何型号猜测。命令回调由 MainUI 统一以广播发回协议栈所在的进程。
 */
@Immutable
class MoondropControls(
    val connected: Boolean = false,
    private val caps: Bundle? = null,
    val ancIds: List<String> = emptyList(),
    val ancIndex: Int = -1,
    val onAncChange: (Int) -> Unit = {},
    val activeCodec: String = "",
    val gainLabels: List<String> = emptyList(),
    val gainIndex: Int = 0,
    val onGainChange: (Int) -> Unit = {},
    val ledOn: Boolean = false,
    val onLedChange: (Boolean) -> Unit = {},
    val promptToneOn: Boolean = false,
    val onPromptToneChange: (Boolean) -> Unit = {},
    val promptVolumeLabels: List<String> = emptyList(),
    val promptVolumeIndex: Int = 0,
    val onPromptVolumeChange: (Int) -> Unit = {},
    val lhdcOn: Boolean = false,
    val onLhdcChange: (Boolean) -> Unit = {},
    val dualConnectionOn: Boolean = false,
    val onDualConnectionChange: (Boolean) -> Unit = {},
    val onOpenGesture: () -> Unit = {}
) {
    /** 设备是否具备某能力；没探测到就不显示对应控件。 */
    fun supports(key: String): Boolean = caps?.getBoolean(key) ?: false

    /** 降噪档位表来自设备探测结果；空表说明还没探到，此时沿用 OPPO 那套控件。 */
    val ancVisible get() = connected && ancIds.isNotEmpty()

    /** 系统实际协商到的编码；拿不到就整行不显示，宁可没有也不摆一行空的。 */
    val codecVisible get() = connected && activeCodec.isNotBlank()

    val gainVisible get() = connected && supports(KEY_GAIN) && gainLabels.isNotEmpty()
    val ledVisible get() = connected && supports(KEY_LED)
    val promptToneVisible get() = connected && supports(KEY_PROMPT_TONE)
    val promptVolumeVisible get() = connected && supports(KEY_PROMPT_VOLUME) && promptVolumeLabels.isNotEmpty()
    val lhdcVisible get() = connected && supports(KEY_LHDC)
    val dualConnectionVisible get() = connected && supports(KEY_DUAL_CONNECTION)
    val gestureVisible get() = connected && supports(KEY_GESTURES)

    companion object {
        /** 与水月雨控制器发布的能力包键名一致（见 MoondropController.publishCapabilities）。 */
        const val KEY_GAIN = "hasGain"
        const val KEY_LED = "hasLed"
        const val KEY_PROMPT_TONE = "hasPromptTone"
        const val KEY_PROMPT_VOLUME = "hasPromptVolume"
        const val KEY_LHDC = "hasLhdc"
        const val KEY_DUAL_CONNECTION = "hasDualConnection"
        const val KEY_GESTURES = "hasGestures"
    }
}
