package com.chenyc.hyperpods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import com.chenyc.hyperpods.pods.PodBrand
import com.chenyc.hyperpods.pods.PodCatalog
import com.chenyc.hyperpods.pods.RfcommController
import com.chenyc.hyperpods.pods.moondrop.MoondropController
import com.chenyc.hyperpods.utils.SystemApisUtils.setIconVisibility
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction

object HeadsetStateDispatcher : HookContext() {
    private const val CONNECTED_DEVICE_BOOTSTRAP_DELAY_MS = 1_500L
    private const val TAG = "HyperPods-Bluetooth"
    private var notificationSettingsReceiverRegistered = false
    private var notificationSettingsContext: Context? = null
    private var notificationSettingsReceiver: BroadcastReceiver? = null
    private var bootstrapHandler: Handler? = null
    private var bootstrapRunnable: Runnable? = null
    private var podControlReceiver: BroadcastReceiver? = null
    private var podControlContext: Context? = null

    override fun onHook() {
        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                val context = instance as ContextWrapper
                registerNotificationSettingsReceiver(context)
                registerPodControlReceiver(context)
                // 品牌分流：这台设备归哪套协议栈管。认不出来就什么都不做
                // （不是我们支持的耳机时，绝不能去动系统的蓝牙状态）。
                val brand = resolveBrand(context, device)
                Log.d(TAG, "A2DP state $fromState -> $currState device=${device.address} brand=$brand")
                if (brand == null) return@post

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    connectBrand(context, device, brand)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING ||
                    currState == BluetoothHeadset.STATE_DISCONNECTED
                ) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    disconnectBrand(context, device, brand)
                }
            }
        }

        // A module may be installed while the earbuds are already connected. In that case
        // handleConnectionStateChanged() does not run again, so the RFCOMM controller never
        // receives a device and all first-install state broadcasts are missing. Bootstrap once
        // after the Bluetooth service starts; normal connection callbacks remain the source of
        // truth for subsequent connections.
        runCatching {
            val serviceCreateMethod = runCatching {
                findMethod("com.android.bluetooth.a2dp.A2dpService", "onCreate")
            }.getOrElse {
                // AOSP/HyperOS commonly declares Service.onCreate in ProfileService rather
                // than overriding it in each profile implementation.
                findMethod("com.android.bluetooth.btservice.ProfileService", "onCreate")
            }
            hookAfter(serviceCreateMethod) {
                val context = instance as? Context ?: return@hookAfter
                scheduleConnectedDeviceBootstrap(context)
            }
            Log.d(TAG, "hooked ${serviceCreateMethod.declaringClass.name}.onCreate for connected-device bootstrap")
        }.onFailure { Log.w(TAG, "hook connected-device bootstrap skipped", it) }
    }

    override fun onHotReloading() {
        bootstrapRunnable?.let { runnable -> bootstrapHandler?.removeCallbacks(runnable) }
        bootstrapRunnable = null
        bootstrapHandler = null
        notificationSettingsReceiver?.let { receiver ->
            runCatching { notificationSettingsContext?.unregisterReceiver(receiver) }
        }
        notificationSettingsReceiver = null
        notificationSettingsContext = null
        notificationSettingsReceiverRegistered = false
        podControlReceiver?.let { receiver ->
            runCatching { podControlContext?.unregisterReceiver(receiver) }
        }
        podControlReceiver = null
        podControlContext = null
        RfcommController.shutdownForHotReload()
    }

    private fun registerNotificationSettingsReceiver(context: Context) {
        if (notificationSettingsReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != HyperPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) return
                    RfcommController.syncNotificationSettings(
                        receiverContext ?: context,
                        intent,
                        refreshNotification = false
                    )
                }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(HyperPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED),
            Context.RECEIVER_EXPORTED
        )
        notificationSettingsContext = context.applicationContext ?: context
        notificationSettingsReceiver = receiver
        notificationSettingsReceiverRegistered = true
    }

    /**
     * 品牌判定：设备名 + MAC 交给 [PodCatalog]，判定规则集中放在 pods/。
     *
     * 顺序很关键（见 PodBrand 的说明）：水月雨侧是白名单精确匹配，OPPO 侧是宽匹配，
     * 先查白名单才不会让一台水月雨被 OPPO 协议栈抢走。
     */
    @SuppressLint("MissingPermission")
    private fun resolveBrand(context: Context, device: BluetoothDevice): PodBrand? =
        runCatching {
            PodCatalog.brandOf(context, device.name ?: device.alias, device.address)
        }.onFailure { Log.w(TAG, "brand resolve failed for ${device.address}", it) }.getOrNull()

    /** 两条协议线的唯一接管点：连接与断开都在这里分流。 */
    private fun connectBrand(context: Context, device: BluetoothDevice, brand: PodBrand) {
        when (brand) {
            PodBrand.OPPO -> RfcommController.connectPod(context, device, prefs)
            PodBrand.MOONDROP -> {
                // 控制器跑在本进程（com.android.bluetooth）内，先确保它拿到 Context。
                MoondropController.init(context)
                MoondropController.connect(device)
            }
        }
    }

    private fun disconnectBrand(context: Context, device: BluetoothDevice, brand: PodBrand) {
        when (brand) {
            PodBrand.OPPO -> RfcommController.disconnectedPod(context, device)
            PodBrand.MOONDROP -> MoondropController.disconnect()
        }
    }

    /**
     * 控制命令接收器：应用侧 UI 与（被伪装成原生耳机页的）设置页把命令广播进来，
     * 由本进程的 [MoondropController] 执行——协议栈在本进程，命令必须回到这里。
     *
     * 与 OPPO 那条线同一形态：控制器跑在被 hook 的蓝牙进程内，UI 只发广播。
     */
    private fun registerPodControlReceiver(context: Context) {
        if (podControlReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == null) return
                val ctx = receiverContext ?: context
                MoondropController.init(ctx)
                MoondropController.handleUIEvent(intent, ctx)
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply { MOONDROP_CONTROL_ACTIONS.forEach { addAction(it) } }, Context.RECEIVER_EXPORTED)
        podControlContext = context.applicationContext ?: context
        podControlReceiver = receiver
    }

    private fun scheduleConnectedDeviceBootstrap(context: Context) {
        bootstrapRunnable?.let { runnable -> bootstrapHandler?.removeCallbacks(runnable) }
        val handler = Handler(context.mainLooper)
        val runnable = Runnable { bootstrapConnectedDevice(context) }
        bootstrapHandler = handler
        bootstrapRunnable = runnable
        handler.postDelayed(runnable, CONNECTED_DEVICE_BOOTSTRAP_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun bootstrapConnectedDevice(context: Context) {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val candidates = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            .asSequence()
            .flatMap { profile ->
                runCatching { bluetoothManager.getConnectedDevices(profile).asSequence() }
                    .getOrElse { emptySequence() }
            }
            .distinctBy { it.address }
            .mapNotNull { device -> resolveBrand(context, device)?.let { device to it } }
            .firstOrNull()
            ?: run {
                Log.d(TAG, "connected-device bootstrap found no supported earbuds")
                return
            }

        val (device, brand) = candidates
        Log.i(TAG, "connected-device bootstrap found ${device.address} brand=$brand")
        connectBrand(context, device, brand)
    }

    /** 应用侧 → 本进程控制器的命令（与 HyperPodsAction 的水月雨段一一对应）。 */
    private val MOONDROP_CONTROL_ACTIONS = arrayOf(
        HyperPodsAction.UI_INIT,
        HyperPodsAction.REQUEST_BATTERY,
        HyperPodsAction.REQUEST_CAPABILITIES,
        HyperPodsAction.ANC_SELECT,
        HyperPodsAction.GAIN_SELECT,
        HyperPodsAction.LED_SELECT,
        HyperPodsAction.PROMPT_TONE_SELECT,
        HyperPodsAction.PROMPT_VOLUME_SELECT,
        HyperPodsAction.LHDC_SELECT,
        HyperPodsAction.DUAL_CONNECTION_SELECT,
        HyperPodsAction.LOW_LATENCY_SELECT,
        HyperPodsAction.GESTURE_SELECT,
        HyperPodsAction.CODEC_CHANGED,
    )
}
