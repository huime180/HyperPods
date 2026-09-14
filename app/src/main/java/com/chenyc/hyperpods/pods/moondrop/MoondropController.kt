/*
 * 水月雨（MOONDROP）耳机控制器。
 *
 * 与 OPPO 侧的 RfcommController 同构：都是被注入进程里的单例，负责建链、收包、解析，
 * 并把状态用广播推给其它进程。两者的差别只在协议族——这边走 GAIA：
 *   · GAIA v3 over BLE GATT   —— 大多数机型（服务 00001100-d102-…）
 *   · GAIA v4 over RFCOMM/SPP —— 布丁 PUDDING 等（UUID 00001101-0000-1000-8000-00805f9b34fb）
 *
 * 几个必须守住的点：
 *   1. 请求/响应关联：GAIA 没有序列号，按 (feature, command) 建 pending 表并带超时；
 *      靠 delay 排序会让状态错乱。
 *   2. 单写者：所有写操作串行化，避免并发交叠。
 *   3. 能力优先：连上先探测 GET_SUPPORTED_FEATURES 位图，用真实能力决定 UI 展示，
 *      型号档案只提供差异点（映射表等）。
 *   4. 电量：先问出设备支持的电池类型，再按该集合查询；type 0（单设备）同时充填左右耳，
 *      根因见 MoondropBatteryCodec 的说明。
 */
package com.chenyc.hyperpods.pods.moondrop

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import com.chenyc.hyperpods.utils.miuiStrongToast.data.PodParams
import com.chenyc.hyperpods.utils.miuiStrongToast.data.putBatteryStatus
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "HyperPods-Moondrop"

object MoondropController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    @Volatile private var appContext: Context? = null
    /** 允许多个监听者：应用 UI 与「跨进程状态转发器」同时消费事件 */

    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var model: MoondropModel = MoondropModelRegistry.FALLBACK
    @Volatile private var useRfcomm = false
    /**
     * RFCOMM 上是否给 GAIA 帧套官方传输头（`FF 04 00 <len>`）。
     * 官方 App 实测是套的，FxxkMoondrop 发裸 PDU 也能工作，故连接时探测并记忆。
     */
    @Volatile private var rfcommUsesHeader = true
    @Volatile private var rfcommFramingProbed = false

    // BLE
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null

    // SPP
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private var pollJob: Job? = null
    /** 有界的「请系统侧重放编码」探测协程（LHDC 切换后起，断开时取消） */
    private var codecReprobeJob: Job? = null

    private val framer = MoondropFramer()
    private val batteryState = BatteryState()
    private val mainHandler = Handler(Looper.getMainLooper())
    // ---- 状态
    @Volatile private var connected = false
    @Volatile private var ancIndex = -1
    @Volatile private var ancModes: List<AncMode> = emptyList()
    @Volatile private var gainIndex = -1
    @Volatile private var ledOn: Boolean? = null
    @Volatile private var promptToneOn: Boolean? = null
    /** 提示音音量：**0..100 百分比**（官方 App 日志实测单位就是百分比） */
    @Volatile private var promptVolumeRaw = -1
    /** 提示音索引（语言/主题），随同一份配置读写 */
    @Volatile private var promptIndex = 0
    @Volatile private var lhdcOn: Boolean? = null
    /**
     * LHDC **最后一次被设备读回确认**的值 —— 乐观写入（见 [setLhdc]）失败时唯一的回退目标。
     * 只由真实回包（回读 / 主动通知，见 [applyLhdc]）更新，不被乐观值污染。
     */
    @Volatile private var lhdcConfirmed: Boolean? = null
    @Volatile private var activeCodec = ""
    @Volatile private var dualConnectionOn: Boolean? = null
    @Volatile private var lowLatencyOn: Boolean? = null
    /**
     * 手势配置（TOUCHV2）的 5 个字节（顺序见 [MoondropGaia.GestureSlot]）；null = 还没读到过。
     *
     * ⚠ 每个字节**打包双耳**：高 4 位 = 左耳动作 id，低 4 位 = 右耳动作 id
     * （字节码 `TouchNewInfo` 的 per-gesture L/R 字段，见 MoondropGaia 的 TOUCHV2 段落）。
     * 内部存 IntArray（与线格式字节一一对应），对外只经 [snapshot] 暴露不可变的
     * [MoondropGaia.GestureConf]。是否支持由能力位图判定（见 [probeCapabilities] 的 hasGestures）。
     */
    @Volatile private var gestureConf: IntArray? = null
    @Volatile private var capabilities = MoondropCapabilities()

    // 每个 feature 只保留一个等待者，避免并发请求互相覆盖（GAIA 无序列号）
    private val responses = HashMap<Int, java.util.concurrent.CompletableFuture<ByteArray>>()

    /**
     * 系统编码重放钩子：由本进程（com.android.bluetooth）的 hook 注册 —— 它读得到系统 A2DP 编码
     * 发显式广播 `UI_INIT`（那边的 hook 会回一条 `CODEC_CHANGED`）。
     * 协议层不直接依赖桥：未注入时静默跳过（纯 JVM 单测 / 桥未初始化）。
     */
    @Volatile private var systemCodecReprobe: (() -> Unit)? = null

    /** 版本探测响应的伪 feature key（真实 feature 非负，不会冲突） */
    private const val PROBE_FEATURE_KEY = -1
    private const val PREF_RFCOMM_FRAMING = "rfcomm_framing"

    private val ANC_TIMEOUT_MS = 1500L
    private val CMD_TIMEOUT_MS = 2000L

    /**
     * LHDC 写后确认：首次回读超时后再重试的次数与间隔。
     * **有界** —— 只有全部失败才回退到 [lhdcConfirmed]，绝不无限重试。
     */
    private const val LHDC_CONFIRM_RETRIES = 2
    private const val LHDC_CONFIRM_RETRY_DELAY_MS = 700L

    /**
     * LHDC 切换后请系统侧重放 A2DP 编码的次数与间隔。
     * 系统编码是**异步**重新协商的：固定几次探测即止，不是轮询。
     */
    private const val CODEC_REPROBE_ATTEMPTS = 3
    private const val CODEC_REPROBE_INTERVAL_MS = 700L

    /**
     * 初始化。
     *
     * 只记 Context：状态的唯一出口是广播（见下面的「状态发布」段），
     * 不需要外部注入监听者。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = appContext != null

    /**
     * 注册「请系统蓝牙进程重放一次真实编码」的钩子（由本进程的 hook 注册）。
     * 传 null 可注销。调用次数由本侧限死，见 [reprobeSystemCodec]。
     */
    fun setSystemCodecReprobe(action: (() -> Unit)?) {
        systemCodecReprobe = action
    }

    /**
     * 处理来自应用侧 UI / 被伪装的原生耳机页的控制命令。
     *
     * 与 [RfcommController.handleUIEvent] 同一份契约：调用方只发广播，本控制器负责解释。
     * 认不出的 action 直接忽略——同一个进程里还跑着 OPPO 那条线的广播。
     */
    fun handleUIEvent(intent: Intent, receiverContext: Context? = null) {
        (receiverContext ?: appContext)?.let { init(it) }
        when (intent.action) {
            HyperPodsAction.UI_INIT, HyperPodsAction.REQUEST_CAPABILITIES -> refreshAll()
            HyperPodsAction.REQUEST_BATTERY -> requestBatteryRefresh()
            HyperPodsAction.ANC_SELECT -> setAnc(intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1))
            HyperPodsAction.GAIN_SELECT -> setGain(intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1))
            HyperPodsAction.LED_SELECT ->
                setLed(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.PROMPT_TONE_SELECT ->
                setPromptTone(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.PROMPT_VOLUME_SELECT ->
                setPromptVolumeRaw(intent.getIntExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, -1))
            HyperPodsAction.LHDC_SELECT ->
                setLhdc(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.DUAL_CONNECTION_SELECT ->
                setDualConnection(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))
            HyperPodsAction.LOW_LATENCY_SELECT ->
                onLowLatencyChanged(intent.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false))

            // 原生耳机页的手势卡片：改某个槽位、某只耳的动作
            HyperPodsAction.GESTURE_SELECT -> {
                val slot = intent.getIntExtra(HyperPodsAction.EXTRA_GESTURE_SLOT, -1)
                val ear = intent.getIntExtra(HyperPodsAction.EXTRA_GESTURE_EAR, -1)
                val actionId = intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1)
                val slots = MoondropGaia.GestureSlot.entries
                val ears = MoondropGaia.Ear.entries
                if (slot in slots.indices && ear in ears.indices && actionId >= 0) {
                    setGesture(slots[slot], ears[ear], actionId)
                } else {
                    Log.w(TAG, "GESTURE_SELECT ignored: slot=$slot ear=$ear action=$actionId")
                }
            }

            // 系统实际协商出来的编码由本进程的蓝牙栈回调汇报
            HyperPodsAction.CODEC_CHANGED ->
                intent.getStringExtra(HyperPodsAction.EXTRA_CODEC)?.let(::onSystemCodecChanged)

            else -> Unit
        }
    }

    fun sniffModel(deviceName: String?): MoondropModel? = MoondropModelRegistry.match(deviceName)

    val currentModel: MoondropModel get() = model

    // 状态发布
    //
    // 控制器就在 com.android.bluetooth 进程内收包，状态没有别的出口：
    // 解析出什么就主动广播给关心的进程（与 RfcommController 同一套做法）。
    // 应用侧 UI、被伪装的原生耳机页、融合设备中心、通知层都从这里取数。

    /** 显式广播（必须 setPackage：Android 14+ 会丢弃未指定包名的隐式广播）。 */
    private fun sendTo(pkg: String, action: String, configure: (Intent) -> Unit = {}) {
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(action).setPackage(pkg)
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            configure(intent)
            ctx.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "sendTo $pkg/$action failed: ${it.message}") }
    }

    /** 带上设备身份，接收方靠 MAC 判断「是不是当前这台」。 */
    private fun Intent.withDevice(): Intent = apply {
        putExtra(HyperPodsAction.EXTRA_MAC, device?.address)
        putExtra(HyperPodsAction.EXTRA_DEVICE_NAME, device?.name)
        putExtra(HyperPodsAction.EXTRA_DEVICE, device)
    }

    /**
     * 三路电量：复用跨进程唯一的 BatteryStatusIntent helper，不自建 extra 格式。
     * level 为 BATTERY_UNKNOWN(-1) 时回落成 0 并把 isConnected 置 false，
     * 让原生页把「没有这只耳」与「电量未知」区分开。
     */
    private fun currentBatteryParams(): BatteryParams {
        fun slot(slot: BatterySlot) = PodParams(
            battery = slot.level.coerceAtLeast(0),
            isCharging = slot.charging,
            isConnected = slot.known,
        )
        return BatteryParams(
            left = slot(batteryState.currentLeft),
            right = slot(batteryState.currentRight),
            case = slot(batteryState.currentCase),
        )
    }

    private fun publishConnected() {
        CONSUMERS.forEach { sendTo(it, HyperPodsAction.PODS_CONNECTED) { i -> i.withDevice() } }
        publishCapabilities()
        publishBattery()
        publishAnc()
    }

    private fun publishDisconnected() {
        CONSUMERS.forEach { sendTo(it, HyperPodsAction.PODS_DISCONNECTED) { i -> i.withDevice() } }
        sendTo(PKG_XIAOMI_BLUETOOTH, HyperPodsAction.CANCEL_PODS_NOTIFICATION) { i -> i.withDevice() }
    }

    private fun publishBattery() {
        if (!batteryState.currentLeft.known && !batteryState.currentRight.known &&
            !batteryState.currentCase.known
        ) {
            return
        }
        val params = currentBatteryParams()
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.BATTERY_CHANGED) { i ->
                i.withDevice().putBatteryStatus(params)
            }
        }
        // 写进系统蓝牙栈：融合设备中心与系统蓝牙页显示的电量来自 AdapterService
        val level = batteryState.systemLevel()
        if (level >= 0) {
            sendTo(PKG_BLUETOOTH, HyperPodsAction.UPDATE_SYSTEM_BATTERY) { i ->
                i.withDevice()
                    .putExtra(HyperPodsAction.EXTRA_LEVEL, level)
                    .putExtra(HyperPodsAction.EXTRA_STATUS, level)
            }
        }
        // 通知 / 超级岛
        sendTo(PKG_XIAOMI_BLUETOOTH, HyperPodsAction.UPDATE_PODS_NOTIFICATION) { i ->
            i.withDevice().putBatteryStatus(params)
        }
    }

    private fun publishAnc() {
        // 档位表为空 = 还没探测出 ANC 路径，此时不发布（免得界面显示一个假档位）
        if (ancModes.isEmpty()) return
        val ids = ArrayList<String>(ancModes.size)
        ancModes.forEach { ids.add(it.id) }
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.ANC_CHANGED) { i ->
                i.withDevice()
                    .putExtra(HyperPodsAction.EXTRA_STATUS, ancIndex)
                    .putStringArrayListExtra(HyperPodsAction.EXTRA_ANC_IDS, ids)
            }
        }
    }

    private fun publishGain() {
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.GAIN_CHANGED) { i ->
                i.withDevice().putExtra(HyperPodsAction.EXTRA_STATUS, gainIndex)
            }
        }
    }

    private fun publishLed() {
        val on = ledOn ?: return
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.LED_CHANGED) { i ->
                i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
            }
        }
    }

    private fun publishPromptTone() {
        val on = promptToneOn ?: return
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.PROMPT_TONE_CHANGED) { i ->
                i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
            }
        }
    }

    private fun publishPromptVolume() {
        if (promptVolumeRaw < 0) return
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.PROMPT_VOLUME_CHANGED) { i ->
                i.withDevice().putExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, promptVolumeRaw)
            }
        }
    }

    private fun publishLhdc() {
        val on = lhdcOn ?: return
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.LHDC_CHANGED) { i ->
                i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
            }
        }
    }

    private fun publishDualConnection() {
        val on = dualConnectionOn ?: return
        // 融合设备中心是 multipoint 门的判定方：不知道真实值时会一直按「未知」保守处理，
        // 所以这个开关必须送到 milink。
        sendTo(PKG_MILINK, HyperPodsAction.DUAL_CONNECTION_CHANGED) { i ->
            i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
        }
        sendTo(PKG_SETTINGS, HyperPodsAction.DUAL_CONNECTION_CHANGED) { i ->
            i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
        }
        sendTo(PKG_APP, HyperPodsAction.DUAL_CONNECTION_CHANGED) { i ->
            i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
        }
    }

    private fun publishGesture() {
        // 内部保存的是裸 IntArray；线上载荷要按 GestureConf 编码（每字节高 4 位左耳、低 4 位右耳），
        // 所以这里包一层再取 toPayload()，而不是把内部数组直接当载荷发出去。
        val slots = gestureConf ?: return
        val payload = MoondropGaia.GestureConf(slots.copyOf()).toPayload()
        sendTo(PKG_SETTINGS, HyperPodsAction.GESTURE_CHANGED) { i ->
            i.withDevice().putExtra(HyperPodsAction.EXTRA_GESTURE_PAYLOAD, payload)
        }
    }

    private fun publishLowLatency() {
        val on = lowLatencyOn ?: return
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.LOW_LATENCY_CHANGED) { i ->
                i.withDevice().putExtra(HyperPodsAction.EXTRA_ENABLED, on)
            }
        }
    }

    private fun publishCapabilities() {
        val caps = capabilities
        val bundle = Bundle().apply {
            putBoolean(CAP_PROBED, caps.probed)
            putBoolean(CAP_GAIN, caps.hasGain)
            putBoolean(CAP_LED, caps.hasLed)
            putBoolean(CAP_PROMPT_TONE, caps.hasPromptTone)
            putBoolean(CAP_PROMPT_VOLUME, caps.hasPromptVolume)
            putBoolean(CAP_LHDC, caps.hasLhdc)
            putBoolean(CAP_DUAL_CONNECTION, caps.hasDualConnection)
            putBoolean(CAP_GESTURES, caps.hasGestures)
            putBoolean(CAP_LOW_LATENCY, caps.hasLowLatency)
            // 自适应档位看档位表，不看位图：有的机型位图里有 ANC 但只有开关两档
            putBoolean(CAP_ADAPTIVE, ancModes.contains(AncMode.ADAPTIVE))
            putBoolean(CAP_SPATIAL, caps.hasSpatial)
            // 界面要显示档位名与音量上限，能力位本身不够
            putStringArrayList(CAP_GAIN_LABELS, ArrayList(model.dc.gainLabels))
            putInt(CAP_PROMPT_VOLUME_MAX, model.features.promptVolumeMax)
        }
        CONSUMERS.forEach { pkg ->
            sendTo(pkg, HyperPodsAction.CAPABILITIES_CHANGED) { i ->
                i.withDevice()
                    .putExtra(HyperPodsAction.EXTRA_CAPS_BUNDLE, bundle)
                    .putExtra(HyperPodsAction.EXTRA_MODEL_NAME, model.nameZh)
            }
        }
    }

    /** GAIA 收发帧只进 logcat：除错用，不进广播（避免高频帧刷爆被注入进程）。 */
    private fun logFrame(direction: String, hex: String, decoded: String) {
        Log.d(TAG, "GAIA $direction $hex $decoded")
    }

    private const val PKG_BLUETOOTH = "com.android.bluetooth"
    private const val PKG_MILINK = "com.milink.service"
    private const val PKG_XIAOMI_BLUETOOTH = "com.xiaomi.bluetooth"
    private const val PKG_SETTINGS = "com.android.settings"

    // 能力包的键名：应用侧从 Bundle 里按这些键读取（见 HyperPodsAction.EXTRA_CAPS_BUNDLE）
    private const val CAP_PROBED = "probed"
    private const val CAP_GAIN = "hasGain"
    private const val CAP_LED = "hasLed"
    private const val CAP_PROMPT_TONE = "hasPromptTone"
    private const val CAP_PROMPT_VOLUME = "hasPromptVolume"
    private const val CAP_LHDC = "hasLhdc"
    private const val CAP_DUAL_CONNECTION = "hasDualConnection"
    private const val CAP_GESTURES = "hasGestures"
    private const val CAP_LOW_LATENCY = "hasLowLatency"
    private const val CAP_ADAPTIVE = "hasAdaptive"
    private const val CAP_SPATIAL = "hasSpatial"
    private const val CAP_GAIN_LABELS = "gainLabels"
    private const val CAP_PROMPT_VOLUME_MAX = "promptVolumeMax"

    /** 本模块自己的包名（详情页要从这里取数）。 */
    private val PKG_APP = com.chenyc.hyperpods.BuildConfig.APPLICATION_ID

    /**
     * 水月雨状态的目标进程。
     *
     * 声明顺序不能挪到上面几个常量之前：object 的成员按声明顺序初始化，
     * 提前引用会拿到未初始化值。
     */
    private val CONSUMERS = listOf(PKG_SETTINGS, PKG_MILINK, PKG_APP)

    // 连接

    fun connect(btDevice: BluetoothDevice, preferRfcomm: Boolean = false) {
        if (connected && device?.address == btDevice.address) return
        disconnectInternal(notify = false)
        device = btDevice
        model = MoondropModelRegistry.match(btDevice.name) ?: MoondropModelRegistry.FALLBACK
        useRfcomm = preferRfcomm || model.transports.firstOrNull() == PodTransport.RFCOMM_GAIA
        framer.reset()
        batteryState.reset()
        capabilities = MoondropCapabilities()
        scope.launch {
            if (useRfcomm) connectRfcomm(btDevice) else connectGattInternal(btDevice)
        }
    }

    fun disconnect() = disconnectInternal(notify = true)

    private fun disconnectInternal(notify: Boolean) {
        connected = false
        pollJob?.cancel(); pollJob = null
        readerJob?.cancel(); readerJob = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null; cmdChar = null
        device = null
        responses.clear()
        // 迟到的编码重放探测不能跨越会话继续存活
        codecReprobeJob?.cancel(); codecReprobeJob = null
        // ⚠ 系统编码属于**本次会话**：不清掉的话，重连后 UI 会把上一段会话的编码
        //   （例如 AAC）当成当前编码显示。空串 = UI 的「未知」占位
        //   （见 PodDetailPage.activeCodecLabel 规则 ④）。
        if (activeCodec.isNotEmpty()) {
            Log.i(TAG, "disconnect: clear activeCodec=$activeCodec")
            activeCodec = ""
        }
        // 乐观更新的回退基线同样只在本会话内有效
        lhdcConfirmed = null
        if (notify) publishDisconnected()
    }
    // ---- BLE GATT
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected status=$status")
                if (connected) disconnectInternal(notify = true)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "discover failed $status"); return }
            val svc = g.getService(UUID.fromString(MoondropGaia.GATT_SERVICE)) ?: run {
                Log.w(TAG, "GAIA service not found; falling back to RFCOMM")
                // 双模设备：BLE 上没有 GAIA 服务时回退 SPP
                val d = device
                if (d != null) { useRfcomm = true; scope.launch { connectRfcomm(d) } }
                return
            }
            cmdChar = svc.getCharacteristic(UUID.fromString(MoondropGaia.GATT_COMMAND))
            enableNotify(g, svc.getCharacteristic(UUID.fromString(MoondropGaia.GATT_RESPONSE)))
            enableNotify(g, svc.getCharacteristic(UUID.fromString(MoondropGaia.GATT_DATA)))
            if (cmdChar == null) { Log.w(TAG, "command characteristic missing"); return }
            connected = true
            Log.i(TAG, "GAIA GATT ready: ${device?.name} model=${model.nameZh}")
            scope.launch { afterConnected() }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onBytes(value)
        }

        @Deprecated("kept for API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onBytes(it) }
        }
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?) {
        ch ?: return
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString(MoondropGaia.CCCD)) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enableNotify failed", t)
        }
    }

    private fun connectGattInternal(btDevice: BluetoothDevice) {
        val ctx = appContext ?: return
        try {
            gatt = if (Build.VERSION.SDK_INT >= 26) {
                btDevice.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                btDevice.connectGatt(ctx, false, gattCallback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt failed", t)
        }
    }
    // ---- RFCOMM / SPP
    private fun connectRfcomm(btDevice: BluetoothDevice) {
        try {
            val uuid = UUID.fromString(MoondropGaia.SPP_UUID)
            val s = try {
                btDevice.createRfcommSocketToServiceRecord(uuid)
            } catch (t: Throwable) {
                Log.w(TAG, "createRfcommSocketToServiceRecord failed, trying channel 1", t)
                @Suppress("DiscouragedPrivateApi")
                btDevice.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(btDevice, 1) as BluetoothSocket
            }
            s.connect()
            socket = s
            connected = true
            Log.i(TAG, "GAIA SPP ready: ${btDevice.name} model=${model.nameZh}")
            startReader(s)
            scope.launch { afterConnected() }
        } catch (t: Throwable) {
            Log.e(TAG, "RFCOMM connect failed", t)
            Log.w(TAG, "RFCOMM 连接失败: ${t.message}")
        }
    }

    private fun startReader(s: BluetoothSocket) {
        readerJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (connected && s.isConnected) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val burstEnd = try { input.available() == 0 } catch (_: Throwable) { true }
                    val chunk = buf.copyOf(n)
                    for (pdu in framer.feed(chunk, burstEnd)) onPdu(pdu)
                }
            } catch (t: Throwable) {
                if (connected) Log.w(TAG, "SPP reader stopped: ${t.message}")
            }
            if (connected) disconnectInternal(notify = true)
        }
    }

    // 写 / 收

    private fun write(pkt: ByteArray) {
        scope.launch {
            writeLock.withLock {
                try {
                    if (useRfcomm) {
                        val onWire = if (rfcommUsesHeader) MoondropGaia.wrapRfcomm(pkt) else pkt
                        socket?.outputStream?.apply { write(onWire); flush() }
                    } else {
                        val g = gatt ?: return@withLock
                        val c = cmdChar ?: return@withLock
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            c.value = pkt
                            @Suppress("DEPRECATION")
                            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(c)
                        }
                    }
                    logFrame("TX", MoondropGaia.hex(pkt), decode(pkt))
                } catch (t: Throwable) {
                    Log.w(TAG, "write failed", t)
                }
            }
        }
    }

    /**
     * 发送并等待匹配的响应；超时返回 null。
     *
     * 注意：这里必须用 `CompletableFuture.get(timeout)`（阻塞带超时），
     * 而不是 `withTimeoutOrNull { fut.get() }` —— 后者无法中断阻塞中的 get()。
     */
    private suspend fun request(pkt: ByteArray, timeoutMs: Long = CMD_TIMEOUT_MS): ByteArray? {
        // ⚠ 版本探测包走 vendor 0x000A，MoondropGaia.parse 只认 0x001D 会返回 null，
        //   所以必须先判探测包，再走常规解析，否则探测请求根本发不出去。
        val isVersionProbe = pkt.size >= 2 &&
            (pkt[0].toInt() and 0xFF) == 0x00 && (pkt[1].toInt() and 0xFF) == 0x0A
        val key = if (isVersionProbe) PROBE_FEATURE_KEY else (MoondropGaia.parse(pkt)?.feature ?: return null)
        val fut = java.util.concurrent.CompletableFuture<ByteArray>()
        responses[key] = fut
        write(pkt)
        return try {
            withContext(Dispatchers.IO) { fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        } catch (_: Throwable) {
            null
        } finally {
            responses.remove(key)
        }
    }

    private fun onBytes(value: ByteArray) {
        for (pdu in framer.feed(value, true)) onPdu(pdu)
    }

    private fun onPdu(pdu: ByteArray) {
        // GAIA 版本探测用的是 V1/V2 包（vendor 0x000A），MoondropGaia.parse 只认 0x001D，
        // 因此这里单独识别，用于 RFCOMM 封装的探测。
        if (pdu.size >= 4) {
            val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
            if (vendor == MoondropGaia.VENDOR_CSR) {
                logFrame("RX", MoondropGaia.hex(pdu), "GAIA version response (vendor 0x000A)")
                responses.remove(PROBE_FEATURE_KEY)?.complete(pdu)
                return
            }
        }
        val f = MoondropGaia.parse(pdu) ?: return
        logFrame("RX", MoondropGaia.hex(pdu), "feature=${f.feature} type=${f.type} cmd=${f.command}")
        // 唤醒等待者
        responses.remove(f.feature)?.complete(f.payload)
        dispatch(f)
    }

    private fun decode(pkt: ByteArray): String {
        val f = MoondropGaia.parse(pkt) ?: return ""
        return "feature=${f.feature} cmd=${f.command} payload=${MoondropGaia.hex(f.payload)}"
    }

    // 连接后的初始化：探测能力 → 读取全量状态 → 起轮询

    private suspend fun afterConnected() {
        // 先把 RFCOMM 封装和 GAIA 版本一起探测掉（版本探测帧同时用作封装探针）。
        runCatching { probeRfcommFraming() }
        probeCapabilities()
        // 先让各进程知道「接上了」，refreshAll() 再把电量/降噪等分项状态补齐
        publishConnected()
        refreshAll()
        startPolling()
    }

    /**
     * 探测 RFCOMM 上是否需要官方传输头（`FF 04 00 <len>`）。
     *
     * 官方 App 实测是套头的；另有实现对布丁直接发裸 PDU 也能工作。两者都试一遍，
     * 结果写入 prefs，后续连接直接复用，避免每次连接都多花一次超时。
     * 探测用的 `00 0A 03 00` 同时就是 GAIA 版本探测帧（设备 GAIA v3 才走 vendor 0x001D）。
     */
    private suspend fun probeRfcommFraming() {
        val ctx = appContext
        val pref = ctx?.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val saved = pref?.getInt(PREF_RFCOMM_FRAMING, -1) ?: -1
        if (!useRfcomm) {
            // BLE：无封装问题，仅发版本探测
            runCatching { request(MoondropGaia.getApiVersion(), ANC_TIMEOUT_MS) }
            return
        }
        if (saved == 0 || saved == 1) {
            rfcommUsesHeader = saved == 1
            rfcommFramingProbed = true
            request(MoondropGaia.getApiVersion(), ANC_TIMEOUT_MS)
            Log.i(TAG, "RFCOMM framing (cached): header=$rfcommUsesHeader")
            return
        }
        rfcommUsesHeader = true
        if (request(MoondropGaia.getApiVersion(), 1200) != null) {
            rfcommFramingProbed = true
            pref?.edit()?.putInt(PREF_RFCOMM_FRAMING, 1)?.apply()
            Log.i(TAG, "RFCOMM framing probed: WITH FF 04 00 header")
            return
        }
        rfcommUsesHeader = false
        if (request(MoondropGaia.getApiVersion(), 1200) != null) {
            rfcommFramingProbed = true
            pref?.edit()?.putInt(PREF_RFCOMM_FRAMING, 0)?.apply()
            Log.i(TAG, "RFCOMM framing probed: BARE PDU")
        } else {
            // 都没回应：保持官方形态，后续靠正常请求自愈
            rfcommUsesHeader = true
            Log.w(TAG, "RFCOMM framing probe inconclusive; keeping FF header")
        }
    }

    private suspend fun probeCapabilities() {
        val feats = HashSet<Int>()
        var cmd = MoondropGaia.C_BASIC_GET_SUPPORTED_FEATURES
        var guard = 0
        while (guard++ < 4) {
            val p = request(MoondropGaia.command(MoondropGaia.F_BASIC, cmd), ANC_TIMEOUT_MS) ?: break
            // 实测格式（Pudding 真机，官方 App logcat）：
            //   payload = [moreFlag:1][featureId:1][version:1]...
            //   例：00 | 00 02 | 01 01 | 05 01 | 0D 01 | 0E 01 | 0F 01 | 10 01 | 13 01 | 14 01 | 16 01 | 20 01
            //   → features {0,1,5,13,14,15,16,19,20,22,32}
            // ⚠ 注意 moreFlag 属于**同一份 payload**，不能再在别处剥一次，
            //   否则 feature/version 会整体错位一格（曾经的能力探测错误）。
            val (more, entries) = MoondropGaia.parseFeatureEntries(p)
            if (entries.isNotEmpty()) {
                feats.addAll(entries.map { it.feature })
                if (!more) break
                cmd = MoondropGaia.C_BASIC_GET_SUPPORTED_FEATURES_NEXT
            } else {
                // 回退：老固件的 32-bit 位图形式（无分页标志）
                feats.addAll(MoondropGaia.parseSupportedFeatures(p))
                break
            }
        }

        // 设备上报的电池类型（修复「右耳不显示」的第一步）
        val supported = request(MoondropGaia.batteryGetAllV4(), ANC_TIMEOUT_MS)?.let { MoondropBatteryCodec.parseSupported(it) }

        val path = MoondropGaia.ancPathFrom(feats).let {
            if (it != MoondropGaia.ANC_PATH_UNKNOWN) it else (model.anc?.path?.feature ?: MoondropGaia.ANC_PATH_UNKNOWN)
        }

        capabilities = MoondropCapabilities(
            features = feats,
            ancPath = path,
            batteryTypes = supported?.toList() ?: emptyList(),
            hasGain = model.dc.hasGain || MoondropGaia.F_DAC_GAIN in feats,
            hasLed = model.dc.hasLed || MoondropGaia.F_LED in feats,
            hasSpatial = model.dc.hasSpatial || MoondropGaia.F_SPATIAL_AUDIO in feats,
            hasHeadTracking = model.dc.hasHeadTracking,
            hasPromptTone = model.features.promptTone || MoondropGaia.F_VOICE in feats,
            hasPromptVolume = model.features.promptVolume || MoondropGaia.F_VOICE in feats,
            hasLhdc = model.features.lhdc || MoondropGaia.F_CODEC_TYPE in feats,
            hasDualConnection = model.features.dualConnection || MoondropGaia.F_ONEBRINGTWO in feats,
            // 手势：**只看能力位图**（Pudding 真机位图里确有 feature 22）；型号档案不参与判定
            hasGestures = MoondropGaia.F_TOUCHV2 in feats,
            hasLowLatency = model.features.lowLatency,
            probed = true,
        )
        ancModes = model.anc?.modes ?: emptyList()
        publishCapabilities()
        Log.i(TAG, "capabilities: $capabilities")
    }

    /** 非挂起入口：供 BroadcastReceiver 之类的同步上下文触发一次电量刷新。 */
    fun requestBatteryRefresh() {
        scope.launch { runCatching { refreshBattery() } }
    }

    /** 读取全量状态。 */
    fun refreshAll() {
        scope.launch {
            refreshBattery()
            refreshAnc()
            if (capabilities.hasGain) refreshGain()
            if (capabilities.hasLed) refreshLed()
            // 提示音开关与音量是**同一份配置**，读一次即可
            if (capabilities.hasPromptTone || capabilities.hasPromptVolume) refreshPromptVoice()
            if (capabilities.hasLhdc) refreshLhdc()
            if (capabilities.hasDualConnection) refreshDualConnection()
            // 手势：一次读回 5 个槽位的整份配置（能力位图门控，与其它功能同一套写法）
            if (capabilities.hasGestures) refreshGestures()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (connected) {
                delay(30_000L)
                refreshBattery()
            }
        }
    }

    // 各功能：读

    /**
     * 电量：先按设备上报的支持类型查询；设备不说就退回「全部类型」。
     * 这是「右耳不显示」的第一处修复——必须把要查的类型列全。
     */
    suspend fun refreshBattery() {
        val ids = capabilities.batteryTypes.takeIf { it.isNotEmpty() }?.toIntArray()
            ?: MoondropBatteryCodec.FALLBACK_QUERY_IDS
        val p = request(MoondropGaia.batteryGet(ids), ANC_TIMEOUT_MS)
            ?: request(MoondropGaia.batteryGetAll(), ANC_TIMEOUT_MS)
            ?: return
        applyBatteryPayload(p)
    }

    private fun applyBatteryPayload(payload: ByteArray) {
        val pairs = MoondropBatteryCodec.parse(payload)
        // 兜底：payload 只有单值且我们知道在查哪一类时，用第一个类型当 hint
        val effective = if (pairs.isEmpty() && payload.size == 1) {
            val hint = capabilities.batteryTypes.firstOrNull() ?: -1
            MoondropBatteryCodec.parse(payload, hint)
        } else pairs
        if (batteryState.applyPairs(effective)) {
            BatteryStateAccess.systemLevel = batteryState.systemLevel()
            publishBattery()
        }
    }

    suspend fun refreshAnc() {
        val path = capabilities.ancPath
        val pkt = when (path) {
            MoondropGaia.ANC_PATH_ANC_V2 -> MoondropGaia.ancV2GetMode()
            MoondropGaia.ANC_PATH_AUDIO_CURATION -> MoondropGaia.audioCurationGetMode()
            MoondropGaia.ANC_PATH_ANC_V1 -> MoondropGaia.ancV1GetState()
            else -> return
        }
        val p = request(pkt, ANC_TIMEOUT_MS) ?: return
        applyAncPayload(p)
    }

    private fun applyAncPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        val dev = payload[0].toInt() and 0xFF
        val prof = model.anc
        ancIndex = when (capabilities.ancPath) {
            MoondropGaia.ANC_PATH_ANC_V2 -> if (prof != null) prof.deviceToUi(dev) else dev
            MoondropGaia.ANC_PATH_AUDIO_CURATION -> prof?.deviceToUi(dev) ?: (dev - 1)
            MoondropGaia.ANC_PATH_ANC_V1 -> if (dev == 0) 0 else 1
            else -> -1
        }
        if (ancIndex >= 0) {
            val mode = ancModes.getOrNull(ancIndex)
            if (mode != null) publishAnc()
        }
    }

    suspend fun refreshGain() {
        val p = request(MoondropGaia.gainGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        gainIndex = model.dc.gainDeviceToUi(p[0].toInt() and 0xFF)
        publishGain()
    }

    suspend fun refreshLed() {
        val p = request(MoondropGaia.ledGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        ledOn = (p[0].toInt() and 0xFF) == 1
        publishLed()
    }

    /**
     * 读提示音配置（cmd 1）。
     *
     * 官方 App 日志实测：回包 payload = `[enabled, volume, index]`，
     * `VoiceRepositoryData: updateV2VoiceConf: enabled=true, volume=20, index=1`。
     * 开关与音量来自**同一次读**。
     */
    suspend fun refreshPromptVoice() {
        val p = request(MoondropGaia.voiceGetConf(model.features.cmdVoiceGetEnable), ANC_TIMEOUT_MS) ?: return
        val conf = MoondropGaia.parseVoiceConf(p) ?: return
        applyVoiceConf(conf)
    }

    private fun applyVoiceConf(conf: MoondropGaia.VoiceConf) {
        promptToneOn = conf.enabled
        promptVolumeRaw = conf.volume
        promptIndex = conf.index
        publishPromptTone()
        publishPromptVolume()
    }

    /**
     * 读 LHDC 开关。
     *
     * @return 读到并已落地 = true；超时 / 空包 = false 且**不改动** [lhdcOn]
     *   （[setLhdc] 依赖这一点：读不到时保留乐观值，而不是悄悄弹回旧值）。
     */
    suspend fun refreshLhdc(): Boolean {
        val p = request(MoondropGaia.lhdcGet(), ANC_TIMEOUT_MS) ?: return false
        if (p.isEmpty()) return false
        applyLhdc((p[0].toInt() and 0xFF) == 1)
        return true
    }

    /** 落地一个**已被设备确认**的 LHDC 值（回读结果或主动通知），并刷新回退基线。 */
    private fun applyLhdc(on: Boolean) {
        lhdcConfirmed = on
        lhdcOn = on
        publishLhdc()
    }

    suspend fun refreshDualConnection() {
        val p = request(MoondropGaia.dualConnectionGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        dualConnectionOn = (p[0].toInt() and 0xFF) == 1
        publishDualConnection()
    }

    /**
     * 读手势配置（TOUCHV2 cmd 2）。
     *
     * 固件只提供「整份 5 字节配置」的读写 —— 既没有单槽位读、也没有按耳读：
     * 所以一次全读，UI 的 10 行（5 手势 × 2 耳）都取自这一份配置。
     */
    suspend fun refreshGestures() {
        if (!capabilities.hasGestures) return
        val slots = fetchGestureConf() ?: return
        applyGestureConf(slots)
    }

    /** 读回 5 个槽位（不落状态）；超时/回包过短返回 null。 */
    private suspend fun fetchGestureConf(): IntArray? {
        val p = request(MoondropGaia.touchV2GetConf(), ANC_TIMEOUT_MS) ?: return null
        return MoondropGaia.parseGestureConf(p)?.slots
    }

    private fun applyGestureConf(slots: IntArray) {
        gestureConf = slots
        publishGesture()
    }

    // 各功能：写（乐观更新 + 读回确认）

    fun setAnc(uiIndex: Int) {
        val prof = model.anc ?: return
        val dev = prof.uiToDevice(uiIndex)
        if (dev < 0) return
        scope.launch {
            when (capabilities.ancPath) {
                MoondropGaia.ANC_PATH_ANC_V2 -> write(MoondropGaia.ancV2SetMode(dev))
                MoondropGaia.ANC_PATH_AUDIO_CURATION -> {
                    // EDGE 真机确认：AC 的 SET_MODE payload 是位掩码 1/2/4
                    val bit = MoondropGaia.AC_SET_PAYLOAD.getOrNull(uiIndex) ?: dev
                    write(MoondropGaia.audioCurationSetMode(bit))
                }
                MoondropGaia.ANC_PATH_ANC_V1 -> write(MoondropGaia.ancV1SetState(if (dev == 0) 0 else 1))
            }
            delay(300)
            refreshAnc()
        }
    }

    fun setGain(uiIndex: Int) {
        val dev = model.dc.gainUiToDevice(uiIndex)
        if (dev < 0) return
        scope.launch { write(MoondropGaia.gainSet(dev)); delay(300); refreshGain(); }
    }

    fun setLed(on: Boolean) {
        scope.launch { write(MoondropGaia.ledSet(if (on) 1 else 0)); delay(300); refreshLed(); }
    }

    /**
     * 写提示音配置（cmd 2）。
     *
     * ⚠ 必须下发**完整三字节** `[enabled, volume, index]`：固件把三者当作一份配置，
     * 只发开关会把音量/索引写坏（反之亦然）。
     *
     * @param volumePercent 0..100
     */
    private fun setPromptVoice(enabled: Boolean, volumePercent: Int, index: Int) {
        scope.launch {
            write(
                MoondropGaia.voiceSetConf(
                    enabled = enabled,
                    volumePercent = volumePercent,
                    index = index,
                    cmdSet = model.features.cmdVoiceSetEnable,
                )
            )
            delay(300)
            refreshPromptVoice()
        }
    }

    fun setPromptTone(on: Boolean) {
        // 保留当前音量与索引，只改开关
        setPromptVoice(on, promptVolumeRaw.takeIf { it in 0..100 } ?: 50, promptIndex)
    }

    /** @param raw 提示音音量百分比 0..100（与官方 App 同一单位） */
    fun setPromptVolumeRaw(raw: Int) {
        setPromptVoice(promptToneOn ?: true, raw.coerceIn(0, MoondropGaia.VOICE_VOLUME_MAX), promptIndex)
    }

    /**
     * LHDC 开关。关掉 LHDC 后耳机回到基础编码（AAC / SBC / LDAC），
     * 这正是「默认 AAC」的表现：出厂默认 LHDC 关闭。
     *
     * 与 [setAnc]/[setGain] 同构，但多做两步，因为「开关不生效」的真因在这里：
     *   ① **乐观更新**：立刻置位并广播，UI 不等耳机回包；
     *   ② `write` 后 500ms 回读确认，读到即以设备为准；
     *   ③ 回读**超时**：保留乐观值，隔 [LHDC_CONFIRM_RETRY_DELAY_MS] 有界重试
     *      [LHDC_CONFIRM_RETRIES] 次（旧实现直接 return，[lhdcOn] 留在旧值，
     *      UI 就把开关弹回去 —— 用户看到的「打开 LHDC 没反应」）；
     *   ④ 只有全部重试都失败才回退到最后一次被设备确认的值（[lhdcConfirmed]，
     *      可能为 null = 未知），并打日志 —— 绝不留一个永远错误的值；
     *   ⑤ 最后请系统侧重放一次真实编码：系统 A2DP 是异步重新协商的，
     *      见 [reprobeSystemCodec]。
     */
    fun setLhdc(on: Boolean) {
        val lastConfirmed = lhdcConfirmed
        lhdcOn = on
        Log.i(TAG, "setLhdc($on): optimistic; lastConfirmed=$lastConfirmed")
        publishLhdc()
        scope.launch {
            write(MoondropGaia.lhdcSet(on))
            delay(500)
            val settled = if (refreshLhdc()) {
                Log.i(TAG, "setLhdc($on): confirmed by read-back lhdcOn=$lhdcOn")
                true
            } else {
                Log.w(
                    TAG,
                    "setLhdc($on): read-back timed out; keeping optimistic value, " +
                        "retrying ${LHDC_CONFIRM_RETRIES}x",
                )
                retryLhdcConfirm(on)
            }
            if (!settled) {
                Log.w(
                    TAG,
                    "setLhdc($on): unconfirmed after ${LHDC_CONFIRM_RETRIES} retries; " +
                        "falling back to lastConfirmed=$lastConfirmed",
                )
                lhdcOn = lastConfirmed
                lastConfirmed?.let { publishLhdc() }
            }
            reprobeSystemCodec()
        }
    }

    /** 回读确认的有界重试；成功时 [refreshLhdc] 已把真实值落地。 */
    private suspend fun retryLhdcConfirm(on: Boolean): Boolean {
        repeat(LHDC_CONFIRM_RETRIES) { i ->
            delay(LHDC_CONFIRM_RETRY_DELAY_MS)
            if (refreshLhdc()) {
                Log.i(TAG, "setLhdc($on): confirmed on retry #${i + 1} lhdcOn=$lhdcOn")
                return true
            }
        }
        return false
    }

    /**
     * LHDC 切换后请系统侧重放一次真实 A2DP 编码。
     *
     * 为什么需要：耳机侧开关生效后，系统 A2DP 会话要**异步**重新协商才会从 AAC 切到
     * LHDC；而 `CODEC_CHANGED` 只在蓝牙栈自己的回调时机才来（这个时机不保证），
     * 于是「当前编码」可能长期停在切换前的旧值。这里主动问：由本进程的
     * HeadsetStateDispatcher 注册的钩子去读一次系统 A2DP 编码并回一条 `CODEC_CHANGED`
     * （见它的 setSystemCodecReprobe 注册点）。
     *
     * **有界**：固定 [CODEC_REPROBE_ATTEMPTS] 次、间隔 [CODEC_REPROBE_INTERVAL_MS]，
     * 发完即止（不是轮询）；断开时会取消本协程（见 [disconnectInternal]）。
     */
    private fun reprobeSystemCodec() {
        val probe = systemCodecReprobe
        if (probe == null) {
            Log.d(TAG, "codec re-probe skipped: no system codec probe registered in this process")
            return
        }
        codecReprobeJob?.cancel()
        codecReprobeJob = scope.launch {
            repeat(CODEC_REPROBE_ATTEMPTS) { i ->
                if (i > 0) delay(CODEC_REPROBE_INTERVAL_MS)
                runCatching { probe() }
                    .onFailure { Log.w(TAG, "codec re-probe #${i + 1} failed", it) }
                Log.i(TAG, "codec re-probe #${i + 1}/$CODEC_REPROBE_ATTEMPTS requested after LHDC switch")
            }
        }
    }

    fun setDualConnection(on: Boolean) {
        scope.launch { write(MoondropGaia.dualConnectionSet(on)); delay(500); refreshDualConnection(); }
    }

    /**
     * 写「某只手势的某只耳朵」的动作 —— **只改该字节里的那一个半字节**。
     *
     * ⚠ **必须下发完整 5 字节**（见 [MoondropGaia.touchV2SetConf]）：固件把 5 个字节当作一份
     * 配置，只发一部分会把其余字节写坏 —— 与 [setPromptVoice] 是同一个坑。
     * 因此这里：若还没读到过配置，先读一次再写；**读不到就放弃本次写入**，
     * 绝不用 0 补齐（那等于把用户其余手势 / 另一只耳朵悄悄清成「无」）。
     * [MoondropGaia.GestureConf.with] 负责半字节读改写：另一只耳朵与其余 4 个字节原样保留。
     *
     * 写完按既有模式 delay 后回读，保证 UI 显示的是固件真实接受了的值。
     *
     * @param slot     手势种类（单击 / 双击 / 三击 / 长按1秒 / 长按3秒）
     * @param ear      哪只耳朵（左 = 高 4 位，右 = 低 4 位）
     * @param actionId 动作 id（半字节 0..15；不在 [MoondropGaia.TouchActions] 里的值也原样下发）
     */
    fun setGesture(slot: MoondropGaia.GestureSlot, ear: MoondropGaia.Ear, actionId: Int) {
        scope.launch {
            val current = gestureConf ?: fetchGestureConf()
            if (current == null) {
                Log.w(TAG, "setGesture($slot/$ear) skipped: gesture config unknown")
                return@launch
            }
            val next = MoondropGaia.GestureConf(current.copyOf()).with(slot, ear, actionId)
            Log.i(
                TAG,
                "setGesture ${slot.index}/${slot.labelZh}/${ear.labelZh} -> " +
                    "${MoondropGaia.TouchActions.matchOrUnknown(actionId)} ($next)",
            )
            write(MoondropGaia.touchV2SetConf(next.toPayload()))
            delay(300)
            refreshGestures()
        }
    }

    /**
     * 由系统侧（A2DP 编解码协商）回调进来，用于 UI 显示当前实际编码。
     *
     * ⚠ 断线时 [disconnectInternal] 会清空 [activeCodec]；此时**迟到**的
     * `CODEC_CHANGED`（LHDC 切换后的重放探测、系统回调排队）绝不能把旧编码写回来，
     * 否则「断开清空」会被立刻撤销。未连接时只记日志。
     */
    fun onSystemCodecChanged(codecName: String) {
        if (!connected) {
            Log.d(TAG, "onSystemCodecChanged($codecName) ignored: not connected")
            return
        }
        activeCodec = codecName
        Log.i(TAG, "system codec -> $codecName")
    }

    /** 由系统侧（低延迟开关）回调进来。 */
    fun onLowLatencyChanged(on: Boolean) {
        lowLatencyOn = on
        publishLowLatency()
    }

    // 响应分发（除了唤醒 request()，还要处理主动通知）

    private fun dispatch(f: MoondropGaia.Frame) {
        when (f.feature) {
            MoondropGaia.F_BATTERY -> if (f.command == MoondropGaia.C_BATT_GET_BATTERY_LEVELS ||
                f.command == MoondropGaia.C_BATT_GET_BATTERY_LEVELS_V4
            ) {
                applyBatteryPayload(f.payload)
            }
            MoondropGaia.F_ANC_V2 -> if (f.command == MoondropGaia.C_ANC2_GET_CURRENT_MODE ||
                f.command == MoondropGaia.C_ANC2_SET_CURRENT_MODE
            ) {
                applyAncPayload(f.payload)
            }
            MoondropGaia.F_AUDIO_CURATION -> if (f.command == MoondropGaia.C_AC_GET_CURRENT_MODE) {
                applyAncPayload(f.payload)
            }
            MoondropGaia.F_ANC -> if (f.command == MoondropGaia.C_ANC1_GET_ANC_STATE) applyAncPayload(f.payload)
            MoondropGaia.F_DAC_GAIN -> if (f.command == MoondropGaia.C_DAC_GET_GAIN && f.payload.isNotEmpty()) {
                gainIndex = model.dc.gainDeviceToUi(f.payload[0].toInt() and 0xFF)
                publishGain()
            }
            MoondropGaia.F_LED -> if (f.command == MoondropGaia.C_LED_GET_STATE && f.payload.isNotEmpty()) {
                ledOn = (f.payload[0].toInt() and 0xFF) == 1
                publishLed()
            }
            MoondropGaia.F_CODEC_TYPE -> if (f.command == MoondropGaia.C_CODEC_GET_LHDC_STATE && f.payload.isNotEmpty()) {
                // 主动/回读到的真实值都算「已确认」，统一走 applyLhdc 以刷新回退基线
                applyLhdc((f.payload[0].toInt() and 0xFF) == 1)
            }
            MoondropGaia.F_ONEBRINGTWO -> if (f.command == MoondropGaia.C_OBT_GET_STATE && f.payload.isNotEmpty()) {
                dualConnectionOn = (f.payload[0].toInt() and 0xFF) == 1
                publishDualConnection()
            }
            MoondropGaia.F_VOICE -> if (f.command == MoondropGaia.C_VOICE_GET_CONF ||
                f.command == MoondropGaia.C_VOICE_SET_CONF
            ) {
                MoondropGaia.parseVoiceConf(f.payload)?.let { applyVoiceConf(it) }
            }
            MoondropGaia.F_TOUCHV2 -> if (f.command == MoondropGaia.C_TOUCHV2_GET_ACTION_CONF ||
                f.command == MoondropGaia.C_TOUCHV2_SET_ACTION_CONF
            ) {
                // 读回与写入回显都是同一份 5 字节配置
                MoondropGaia.parseGestureConf(f.payload)?.let { applyGestureConf(it.slots) }
            }
            MoondropGaia.F_BASIC -> if (f.command == MoondropGaia.C_BASIC_GET_SUPPORTED_FEATURES) {
                // request() 已经消费；这里只做通知型位图的增量合并
                capabilities = capabilities.copy(
                    features = capabilities.features + MoondropGaia.parseSupportedFeaturesSmart(f.payload),
                )
            }
        }
    }

    // 9ECA 私有协议（中科蓝讯系：音源切换 / EQ / MIC）

    fun srcGetAudioSource(seq: Int) = write(MoondropSrcProtocol.command(MoondropSrcProtocol.CMD_GET_AUDIO_SOURCE, seq, null))
    fun srcSetAudioSource(seq: Int, sourceId: Int) =
        write(MoondropSrcProtocol.setAudioSource(seq, sourceId))

}

/** 让 hook 进程读到当前系统电量，避免直接暴露内部对象。 */
object BatteryStateAccess {
    @Volatile var systemLevel: Int = BATTERY_UNKNOWN
}
