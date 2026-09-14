package com.chenyc.hyperpods.pods

import android.content.Context
import android.util.Log
import com.chenyc.hyperpods.pods.moondrop.MoondropModel
import com.chenyc.hyperpods.pods.moondrop.MoondropModelRegistry

/**
 * 耳机品牌（一个模块同时接管两个厂牌，被注入进程必须先分流再动手）。
 *
 * 为什么需要它：本模块由两个上游合并而来，两边的协议栈、配置档、能力模型完全不同——
 *   · OPPO/HeyMelody：`pods/` 下的 RFCOMM 控制器 + `Packets.kt` + `device_models.json` 白名单，
 *     由 `com.android.bluetooth` 进程内的 [RfcommController] 主持；
 *   · 水月雨/MOONDROP：`pods/moondrop/` 下的 GAIA 协议 + `MoondropModelRegistry` 型号档案，
 *     由 `pods/moondrop/MoondropController` 主持，形态与 [RfcommController] 同构
 *     （同样是 `object` + 在 `com.android.bluetooth` 进程内建链、收包、广播状态）。
 *
 * 因此「这个刚连上的设备归谁管」是全局第一个判断，且必须在每个被注入进程里都能独立算出
 * （各进程只共享广播，不共享内存）。[PodCatalog] 就是这个唯一判断入口。
 */
enum class PodBrand {
    /** OPPO / 一加 / HeyMelody 系（RFCOMM 私有协议）。 */
    OPPO,

    /** 水月雨 MOONDROP（GAIA over GATT/SPP）。 */
    MOONDROP,
}

/**
 * 品牌与型号识别：把「设备名 / MAC / productId」翻译成品牌与型号档案。
 *
 * 设计约束（与 AGENTS.md 一致）：识别逻辑集中在 `pods/`，Hook 层与 UI 层**不得**自己
 * `contains("oppo")` 或自己写型号表。两侧的权威来源分别是：
 *   · OPPO   → [DeviceModelRegistry]（`assets/device_models.json`，按 productId / 名称匹配）
 *   · 水月雨 → `pods/moondrop/MoondropModelRegistry`（代码内型号档案，按名称 / MAC 匹配别名）
 *
 * 全部方法都必须容错：识别不出来返回 null / false，绝不抛给被注入进程。
 */
object PodCatalog {
    private const val TAG = "HyperPods-Catalog"

    /**
     * 水月雨型号档案。
     *
     * 传入多个候选键（设备名、MAC），按顺序取第一个能匹配上的——
     * 部分机型名称里带用户自定义后缀，只有 MAC 前缀能稳定认出来。
     */
    fun moondropModelOf(vararg keys: String?): MoondropModel? =
        keys.asSequence()
            .filterNotNull()
            .filter { it.isNotBlank() }
            .firstNotNullOfOrNull { key ->
                runCatching { MoondropModelRegistry.match(key) }
                    .onFailure { Log.w(TAG, "MoondropModelRegistry.match($key) failed", it) }
                    .getOrNull()
            }

    /**
     * OPPO 配置档能力模型。
     *
     * 优先用 RFCOMM 握手拿到的 productId（精确，[RfcommController] 侧才会有），
     * 没有时退回设备名匹配（被注入进程只能拿到名字）。
     */
    fun oppoCapabilitiesOf(context: Context, deviceName: String?, productId: String? = null): DeviceCapabilities? {
        if (!deviceName.isNullOrBlank() || !productId.isNullOrBlank()) {
            runCatching { DeviceModelRegistry.ensureLoaded(context) }
                .onFailure { Log.w(TAG, "DeviceModelRegistry.ensureLoaded failed", it) }
        }
        productId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { DeviceModelRegistry.byProductId(context, id) }.getOrNull()?.let { return it }
        }
        return runCatching { DeviceModelRegistry.byDeviceName(context, deviceName) }.getOrNull()
    }

    /**
     * 品牌判定。
     *
     * 顺序不能反：水月雨型号档案是**白名单式**精确匹配（别名 + MAC 前缀），
     * 而 OPPO 侧历史上用「名称含 oppo」的宽匹配；先查白名单可以避免
     * 「一台水月雨因为名字里恰好有 oppo 字样被 OPPO 协议栈抢走」。
     */
    fun brandOf(context: Context, deviceName: String?, mac: String? = null): PodBrand? {
        if (moondropModelOf(deviceName, mac) != null) return PodBrand.MOONDROP
        if (isOppoName(deviceName)) return PodBrand.OPPO
        // 名称认不出但注册表里有（例如用户自定义了耳机名），按注册表兜底。
        if (oppoCapabilitiesOf(context, deviceName)?.isSupported == true) return PodBrand.OPPO
        return null
    }

    /**
     * OPPO 系的名称兜底判定（与上游 `HeadsetStateDispatcher.isOppoPod` 行为保持一致）。
     *
     * 这是**宽匹配**，只用于「没有更权威信息时」的兜底；能拿到 productId 时不要用它。
     */
    fun isOppoName(deviceName: String?): Boolean {
        val name = deviceName?.trim().orEmpty()
        if (name.isEmpty()) return false
        return name.contains("oppo", ignoreCase = true) ||
            name.contains("oneplus", ignoreCase = true) ||
            name.contains("oplus", ignoreCase = true)
    }

    /** 水月雨名称兜底判定（走型号档案，不做 `contains` 宽匹配）。 */
    fun isMoondropName(deviceName: String?): Boolean = moondropModelOf(deviceName) != null
}
