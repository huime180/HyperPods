package com.chenyc.hyperpods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.chenyc.hyperpods.BuildConfig
import com.chenyc.hyperpods.pods.PodCatalog
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction

/**
 * 原生「蓝牙设置里的耳机设备页」整页重定向到模块自己的耳机页。
 *
 * 为什么走重定向而不是在设置进程里重画：模块耳机页是 Compose/Miuix 写的，而
 * `com.android.settings` 进程里跑不了（也不该硬塞）Compose 运行时；把那一页用
 * `android.view` 再实现一遍是两套 UI 同步维护，且每个控件都要处理厂商布局差异。
 * 重定向之后品牌分流、手势、ANC、电量、增益等全部复用模块已有实现。
 *
 * 入口只有一个 Activity：反汇编 `MiuiHeadsetActivity.onCreate` 确认它就是设备页
 * （`setContentView(activity_headset)` -> 从 intent 取 DEVICE / bluetoothaddress ->
 * `MiuiHeadsetFragment`）；蓝牙设置列表里点设备那条路（`MiuiBluetoothSettings`）启动的
 * 也是它。`MiuiHeadsetActivityPlugin` 是 Qigsaw 版容器，它同时被「连接帮助」入口使用
 * （`MiuiBluetoothSettings$12` 带 `COME_FROM=MIUI_BT_CONNECT_HELP`），所以只在该来源为
 * 蓝牙设置时才当成设备页处理，免得把帮助页也劫持掉。
 *
 * 只对受管设备生效：判定交给 `pods/` 里的唯一入口 [PodCatalog.brandOf]，
 * 认不出来的普通蓝牙设备完全不受影响（不 finish、不启动任何东西）。
 *
 * 防死循环：模块页顶栏还有一个「系统蓝牙设置」图标，它启动的正是同一个 Activity，
 * 且 intent 里的 extra 与「从蓝牙设置点设备」完全一致（没有可区分的标记）。两道闸：
 *   1. `Activity.getReferrer()` 的 authority 是本模块包名时直接放行 —— 这一条覆盖了
 *      「模块自己打开的入口」，是精确判定；
 *   2. 兜底的一次性时间窗：刚为某地址重定向过，30 秒内同一地址再次进来就当成
 *      「模块页弹回来的那一次」放行并作废标记（放行一次后可再次重定向），
 *      所以不存在自我循环，只可能把「重定向后 30 秒内用户又点了一次该设备」也放行到原生页。
 *
 * 降级：每个 hook 与每个回调体都整段 runCatching，任何一步失败只留下一条日志并让
 * 「这一路失效」——模块页打不开时**不会** finish 原生页，用户至少还留在系统页面上，
 * 绝不把异常抛回设置进程。
 */
@SuppressLint("MissingPermission")
object HeadsetPageRedirectHook {

    private const val TAG = "HyperPods-Redirect"

    /** 厂商在 intent 里放设备的两个键（顺序与反汇编一致：先 DEVICE，再按地址兜底找）。 */
    private const val EXTRA_DEVICE_PARCEL = "android.bluetooth.device.extra.DEVICE"
    private const val EXTRA_ADDRESS = "bluetoothaddress"
    private const val EXTRA_COME_FROM = "COME_FROM"

    private const val COME_FROM_BLUETOOTH_SETTINGS = "MIUI_BLUETOOTH_SETTINGS"

    /** 模块耳机页所在 Activity（MainActivity 是常驻的耳机控制页容器）。 */
    private const val MODULE_ACTIVITY_SUFFIX = ".MainActivity"

    /** 一次性防抖窗口：见文件头「防死循环」第 2 条。 */
    private const val BOUNCE_WINDOW_MS = 30_000L

    /**
     * 一个原生入口。
     *
     * @param requiredComeFrom 非空时，只有 intent 的 COME_FROM 等于它才当设备页处理。
     */
    private class Entry(val className: String, val requiredComeFrom: String?)

    private val ENTRIES = listOf(
        Entry("com.android.settings.bluetooth.MiuiHeadsetActivity", null),
        Entry("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", COME_FROM_BLUETOOTH_SETTINGS),
    )

    /** 上一次重定向到的地址与时刻，只用于防抖窗口。 */
    private var lastRedirectAddress: String? = null
    private var lastRedirectAt = 0L

    /** 由 [SettingsHeadsetHook] 在 onHook() 里调用安装。 */
    fun install(host: HookContext) {
        for (entry in ENTRIES) {
            runCatching {
                host.hookAfter(host.findMethod(entry.className, "onCreate", Bundle::class.java)) {
                    val activity = instance as? Activity ?: return@hookAfter
                    runCatching { redirectIfManaged(activity, entry) }
                        .onFailure { Log.w(TAG, "${entry.className} 重定向判定失败，保留原生页", it) }
                }
            }.onFailure { Log.w(TAG, "hook ${entry.className}.onCreate skipped", it) }
        }
    }

    private fun redirectIfManaged(activity: Activity, entry: Entry) {
        val intent = activity.intent ?: return
        val comeFrom = intent.getStringExtra(EXTRA_COME_FROM)
        if (entry.requiredComeFrom != null && entry.requiredComeFrom != comeFrom) {
            Log.d(TAG, "跳过 ${entry.className}: COME_FROM=$comeFrom 不算设备页入口")
            return
        }
        val device = resolveDevice(intent) ?: run {
            Log.d(TAG, "跳过重定向: 这次启动没带可识别的设备 comeFrom=$comeFrom")
            return
        }
        val address = runCatching { device.address }.getOrNull()
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        // 受管判定走 pods/ 的唯一入口（水月雨白名单 + OPPO 名称/注册表兜底），
        // 认不出来就什么都不做 —— 普通耳机必须完全不受影响。
        val brand = runCatching { PodCatalog.brandOf(activity, name, address) }.getOrNull()
        if (brand == null) {
            Log.d(TAG, "不是本模块接管的耳机，保持原生页 name=$name address=$address")
            return
        }
        if (isLaunchedByModule(activity)) {
            Log.d(TAG, "这次是模块自己的入口（referrer 为本模块），不重定向 address=$address")
            return
        }
        if (consumeBounceGuard(address)) return
        if (!launchModuleHeadsetPage(activity, device, address, name)) return
        markRedirect(address)
        runCatching { activity.finish() }
            .onFailure { Log.w(TAG, "finish 原生页失败，用户会看到原生页与模块页叠着", it) }
        Log.d(TAG, "已重定向到模块耳机页 brand=$brand address=$address comeFrom=$comeFrom")
    }

    /**
     * 从 intent 里取设备。
     *
     * 厂商自己也是这么做的：先取 DEVICE 包裹，取不到再拿 bluetoothaddress 去已配对列表里找。
     * 沿用它是因为只有走列表才能拿到**真实设备名**（水月雨那套还认 MAC 前缀，OPPO 侧
     * 只有名字可用），而这次重定向之后原生页不会再去解析这些 extra。
     */
    private fun resolveDevice(intent: Intent): BluetoothDevice? {
        runCatching { intent.getParcelableExtra(EXTRA_DEVICE_PARCEL, BluetoothDevice::class.java) }
            .getOrNull()
            ?.let { return it }
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return null
        return runCatching {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices
                ?.firstOrNull { it.address.equals(address, ignoreCase = true) }
        }.getOrNull()
            ?: runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
    }

    /** 发起这次启动的是不是本模块自己（模块页顶栏的「系统蓝牙设置」图标就走这条）。 */
    private fun isLaunchedByModule(activity: Activity): Boolean = runCatching {
        activity.referrer?.authority == BuildConfig.APPLICATION_ID
    }.getOrDefault(false)

    /**
     * 一次性防抖窗口。
     *
     * 命中即作废标记（返回 true 表示这次放行原生页）：这样「用户回到蓝牙设置再点一次设备」
     * 立刻能重新重定向，而不是被窗口吞掉。
     */
    private fun consumeBounceGuard(address: String?): Boolean {
        val previous = lastRedirectAddress ?: return false
        if (address == null || !previous.equals(address, ignoreCase = true)) return false
        val elapsed = SystemClock.elapsedRealtime() - lastRedirectAt
        if (elapsed > BOUNCE_WINDOW_MS) return false
        lastRedirectAddress = null
        Log.d(TAG, "命中防抖窗口(${elapsed}ms)，这次放行原生页 address=$address")
        return true
    }

    private fun markRedirect(address: String?) {
        lastRedirectAddress = address
        lastRedirectAt = SystemClock.elapsedRealtime()
    }

    /**
     * 打开模块耳机页。
     *
     * 用**显式组件**启动而不是只发广播：模块侧目前没有 SHOW_UI 的接收器（全仓库只有
     * `HyperPodsAction.SHOW_UI` 这一个常量，没有任何 registerReceiver），靠广播有
     * 「没人收 -> 用户点了设备却什么都没打开」的风险；而且就算模块侧用运行时接收器
     * 去 startActivity，后台进程启动 Activity 会被 Android 10+ 的后台启动限制挡掉。
     * 设置进程此刻是前台，直接起 Activity 最稳。action 仍带 SHOW_UI，
     * 模块页据此知道「是从系统设备页进来的」。
     *
     * @return true 只有确实 startActivity 成功；false 时调用方不会 finish 原生页。
     */
    private fun launchModuleHeadsetPage(
        activity: Activity,
        device: BluetoothDevice,
        address: String?,
        name: String?,
    ): Boolean = runCatching {
        val intent = Intent(HyperPodsAction.SHOW_UI).apply {
            setClassName(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + MODULE_ACTIVITY_SUFFIX)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(HyperPodsAction.EXTRA_DEVICE, device)
            putExtra(HyperPodsAction.EXTRA_MAC, address)
            putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, name)
        }
        activity.startActivity(intent)
        true
    }.onFailure { Log.w(TAG, "打开模块耳机页失败，保留原生页", it) }.getOrDefault(false)
}
