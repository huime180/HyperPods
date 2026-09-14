package com.chenyc.hyperpods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import com.chenyc.hyperpods.BuildConfig
import com.chenyc.hyperpods.pods.PodBrand
import com.chenyc.hyperpods.pods.PodCatalog
import com.chenyc.hyperpods.pods.RfcommController
import com.chenyc.hyperpods.pods.moondrop.MoondropController
import com.chenyc.hyperpods.utils.SystemApisUtils.setIconVisibility
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction

object HeadsetStateDispatcher : HookContext() {

    /** 日志 TAG（object 内是普通成员，不能放 companion：standalone object 不允许 companion）。 */
    private const val TAG = "HyperPods-Bluetooth"
    private var appRequestReceiverRegistered = false

    /**
     * 水月雨侧的控制命令（应用 UI / 被伪装的原生耳机页 → 本进程的控制器）。
     *
     * 为什么要在蓝牙进程收：水月雨的协议栈（[MoondropController]）就跑在本进程，
     * 命令必须回到协议栈所在进程执行，跨进程只有广播这一条路。
     */
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
        HyperPodsAction.GESTURE_SELECT,
        HyperPodsAction.CODEC_CHANGED
    )

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("HyperPods", "AdapterService.onCreate hook skipped", it)
        }

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
                registerAppRequestReceiver(context)
                // 品牌分流：这台设备归哪套协议栈管。认不出来就什么都不做
                // （不是本模块支持的耳机时，绝不能去动系统的蓝牙状态）。
                val brand = resolveBrand(context, device)
                Log.d(TAG, "A2DP state $fromState -> $currState device=${device.address} brand=$brand")
                if (brand == null) return@post

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    connectBrand(context, device, brand)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    disconnectBrand(context, device, brand)
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    HyperPodsAction.ACTION_PODS_UI_INIT,
                    HyperPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(HyperPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                    HyperPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        // 设备选择页是手选入口，两个品牌的设备都会从这里进来，
                        // 所以同样要先分流——否则水月雨设备会被交给 OPPO 的 RFCOMM 控制器，
                        // 连不上（UUID 与通道都不是它那套）。
                        val brand = resolveBrand(context, device)
                        Log.d(TAG, "connect request from app device=${device.address} brand=$brand")
                        when (brand) {
                            PodBrand.OPPO ->
                                RfcommController.connectPod(context, device, prefs, appRequested = true)

                            PodBrand.MOONDROP -> {
                                MoondropController.init(context)
                                MoondropController.connect(device)
                            }

                            null -> Unit
                        }
                    }
                    HyperPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        val brand = resolveBrand(context, device)
                        Log.d(TAG, "disconnect request from app device=${device.address} brand=$brand")
                        when (brand) {
                            PodBrand.OPPO -> RfcommController.disconnectedPod(context, device)
                            PodBrand.MOONDROP -> MoondropController.disconnect()
                            null -> Unit
                        }
                    }

                    else -> {
                        // 水月雨命令统一交给本进程的控制器解释（认不出的 action 会被它忽略）
                        val action = intent?.action ?: return
                        if (MOONDROP_CONTROL_ACTIONS.contains(action)) {
                            MoondropController.init(context)
                            MoondropController.handleUIEvent(intent, context)
                        }
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_PODS_UI_INIT)
            addAction(HyperPodsAction.ACTION_REFRESH_STATUS)
            addAction(HyperPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(HyperPodsAction.ACTION_DISCONNECT_POD_REQUEST)
            // 水月雨侧
            MOONDROP_CONTROL_ACTIONS.forEach { addAction(it) }
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /**
     * 品牌判定：设备名 + MAC 交给 [PodCatalog]，判定规则集中在 pods/。
     *
     * 顺序很关键（见 PodBrand 的说明）：水月雨侧是白名单精确匹配，OPPO 侧是宽匹配，
     * 先查白名单才不会让一台水月雨被 OPPO 协议栈抢走。
     */
    @SuppressLint("MissingPermission")
    private fun resolveBrand(context: Context, device: BluetoothDevice): PodBrand? =
        runCatching { PodCatalog.brandOf(context, device.name ?: device.alias, device.address) }
            .onFailure { Log.w(TAG, "brand resolve failed for ${device.address}", it) }
            .getOrNull()

    /** 兼容入口：只判 OPPO 系（宽匹配）。新代码用 [resolveBrand]。 */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean =
        runCatching { PodCatalog.isOppoName(device.name) }.getOrDefault(false)

    /** 两条协议线的唯一接管点：连接与断开都在这里分流。 */
    private fun connectBrand(context: Context, device: BluetoothDevice, brand: PodBrand) {
        when (brand) {
            PodBrand.OPPO -> RfcommController.connectPod(context, device, prefs)
            PodBrand.MOONDROP -> {
                // 控制器跑在本进程（com.android.bluetooth）内，先确保它拿到 Context
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

}
