package com.chenyc.hyperpods.hook.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.BuildConfig
import com.chenyc.hyperpods.config.ConfigManager
import com.chenyc.hyperpods.hook.HookContext
import com.chenyc.hyperpods.hook.Log
import com.chenyc.hyperpods.hook.callMethod
import com.chenyc.hyperpods.hook.getObjectField
import com.chenyc.hyperpods.hook.setObjectField
import com.chenyc.hyperpods.pods.PodBrand
import com.chenyc.hyperpods.pods.PodCatalog
import com.chenyc.hyperpods.pods.RfcommController
import com.chenyc.hyperpods.pods.moondrop.AncMode
import com.chenyc.hyperpods.utils.miuiStrongToast.data.batteryStatusCompat
import com.chenyc.hyperpods.pods.detectDeviceCapabilities
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import com.chenyc.hyperpods.utils.miuiStrongToast.data.PodParams

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    internal const val TAG = "HyperPods-MiLink"
    private const val PREFS_NAME = "hyperpods_milink_state"
    /**
     * 已被本模块接管的地址（两个厂牌共用）。
     *
     * 不再叫 knownOppo*：融合设备中心侧的判据是「这台是不是我们接管的耳机」，
     * 而不是「这台是不是 OPPO」—— 水月雨的地址同样要进来。
     */
    private val knownAddresses = linkedSetOf<String>()
    internal var context: Context? = null
    private var currentProductId: String? = null
    private var receiverRegistered = false
    internal var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    /** 当前设备厂牌：由广播记录下来，冷启动时从偏好恢复（融合设备中心侧也要按厂牌分流）。 */
    private var currentBrand: PodBrand? = null
    /** 水月雨档位表（id 顺序即下标顺序）与当前下标 —— 小窗要按家族答「关/降噪/通透」。 */
    private var currentAncIds: List<String> = emptyList()
    private var currentAncIndex: Int = -1
    /** 内存状态是否已从偏好读过：判据不能只看内存，否则进程刚起来时第一台设备会被判成不认识。 */
    private var stateLoaded = false
    private var currentGameMode = false
    internal var currentSpatialAudioMode = ConfigManager.SPATIAL_AUDIO_OFF
    internal var lastAncBatteryController: Any? = null
    internal var lastProfileContext: Any? = null
    private var gameModeIcon: Drawable? = null
    private val spatialAudioHook = MiLinkSpatialAudioHook(this)

    override fun onHook() {
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        spatialAudioHook.hookCirculateHeadsetServiceInfo()
        hookGameModeCard()
    }

    private fun hookContextEntry() {
        listOf(
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        ).forEach { className ->
            runCatching {
                hookBefore(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) {
                    registerStatusReceiver(args[0] as? Context)
                }
            }.onFailure { Log.w(TAG, "hook $className.getInstanceForIsMiTWS skipped", it) }
        }
    }

    private fun hookMxBluetoothRuntime() {
        val classes = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
        )
        classes.forEach { className ->
            hookBluetoothDeviceResult(className, "checkIsMiTWS") { 1 }
            hookBluetoothDeviceResult(className, "getDeviceId") { fakeDeviceId() }
            hookBluetoothDeviceResult(className, "getBatteryLevel") { 1 }
            hookBluetoothDeviceResult(className, "getAncState") { miLinkAncState() }
            hookBluetoothDeviceResult(className, "getDeviceRunInfo") { 0 }
            hookBluetoothDeviceResult(className, "getWearStatus") { "0,0" }
            hookBluetoothDeviceResult(className, "isLeAudio") { false }
            hookAncCommand(className, "openAnc", 2, 1)
            hookAncCommand(className, "closeAnc", 1, 0)
            hookAncCommand(className, "openTransparent", 3, 2)
        }
        classes.forEach { className ->
            hookStringAddressResult(className, "isMiTWS") { true }
            hookStringAddressResult(className, "isSupportAudioSwitch") { miLinkSwitchState() }
            hookStringAddressResult(className, "getRingFindState") { false }
        }
        spatialAudioHook.hookMxBluetoothRuntime(classes)
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getDeviceId") { fakeDeviceId() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getBatteryLevel") { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getDeviceId") { fakeDeviceId() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getAncState") { miLinkAncState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getBatteryLevelCache") { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getHeadsetPropertyBlock") { batteryPercentForMiLink() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getFindRingState") { miLinkGameModeState() }
        hookStringAddressResult("com.miui.headset.runtime.AncBatteryController", "getSwitchState") { miLinkSwitchState() }
        hookAncStateBlock()
        spatialAudioHook.hookHeadsetRuntimeDisplay()
        hookHeadsetInfoNoArg("getDeviceId") { fakeDeviceId() }
        hookHeadsetInfoNoArg("component3") { fakeDeviceId() }
        hookHeadsetInfoNoArg("getPowers") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("component4") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("getMode") { miLinkAncState() }
        hookHeadsetInfoNoArg("component5") { miLinkAncState() }
        hookHeadsetInfoNoArg("getSwitchState") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("component8") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("getFindRingState") { miLinkGameModeState() }
        hookHeadsetInfoNoArg("component11") { miLinkGameModeState() }
    }

    internal fun hookBluetoothDeviceResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isManagedPod(device)) return@hookAfter
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                this.result = result()
                if (className == "com.miui.headset.runtime.AncBatteryController" && methodName == "getHeadsetPropertyBlock") {
                    notifyHeadsetPropertyChanged(instance, device, 4)
                }
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(BluetoothDevice) skipped", it) }
    }

    internal fun hookStringAddressResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookAfter
                if (!isManagedAddress(address)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, oppoAnc: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isManagedPod(device)) return@hookBefore
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                currentAnc = oppoAnc
                if (brandOf(device) == PodBrand.MOONDROP) {
                    // 水月雨：小窗里点「降噪/通透」必须落到设备上，
                    // 走的是水月雨自己的 ANC_SELECT（下标语义），OPPO 那套 1/2/3/4 它不认。
                    selectMoondropAnc(familyOfOppoAnc(oppoAnc))
                } else {
                    sendOppoAnc(oppoAnc)
                    sendAncChanged(oppoAnc)
                }
                this.result = result
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName command skipped", it) }
    }

    private fun hookAncStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setAncStateBlock", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isManagedPod(device)) return@hookBefore
                lastAncBatteryController = instance
                captureRuntimeContext(instance)
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val oppoAnc = oppoAncFromMiLink(miLinkMode)
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                if (brandOf(device) == PodBrand.MOONDROP) {
                    // 融合设备中心只会问三档，水月雨的档位是型号相关 id：按家族挑一个下标回传
                    selectMoondropAnc(familyOfOppoAnc(oppoAnc))
                    notifyHeadsetPropertyChanged(instance, device, 8)
                    notifyHeadsetPropertyChanged(instance, device, 4)
                } else {
                    currentAnc = oppoAnc
                    sendOppoAnc(oppoAnc, instanceContext)
                    sendAncChanged(oppoAnc, instanceContext)
                    notifyHeadsetPropertyChanged(instance, device, 8)
                    notifyHeadsetPropertyChanged(instance, device, 4)
                }
                this.result = miLinkAncState()
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setAncStateBlock skipped", it) }
    }

    internal fun hookHeadsetInfoNoArg(methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethodByParamCount("com.miui.headset.api.HeadsetInfo", methodName, 0)) {
                if (!isTargetHeadsetInfo(instance)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook HeadsetInfo.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        val filter = IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_PODS_CONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(HyperPodsAction.ACTION_CONFIG_CHANGED)
            // 水月雨线
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            addAction(HyperPodsAction.BATTERY_CHANGED)
            addAction(HyperPodsAction.ANC_CHANGED)
        }
        context?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    HyperPodsAction.ACTION_CONFIG_CHANGED -> {
                        refreshConfig()
                        notifyHeadsetPropertyChanged(lastAncBatteryController, currentBluetoothDevice(), 10)
                    }
                    HyperPodsAction.ACTION_PODS_CONNECTED -> {
                        currentBrand = PodBrand.OPPO
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        currentProductId = intent.getStringExtra("product_id") ?: currentProductId
                        currentAddress?.let { knownAddresses.add(it.uppercase()) }
                    }
                    HyperPodsAction.ACTION_PODS_DISCONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentProductId = null
                    }
                    HyperPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        currentBrand = PodBrand.OPPO
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentBattery = intent.batteryStatusFromExtras() ?: intent.parcelableStatus() ?: currentBattery
                        currentAddress?.let { knownAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                    HyperPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentBrand = PodBrand.OPPO
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        currentAddress?.let { knownAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                    HyperPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        currentBrand = PodBrand.OPPO
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentGameMode = intent.getBooleanExtra("enabled", currentGameMode)
                        currentAddress?.let { knownAddresses.add(it.uppercase()) }
                        saveState(context)
                        notifyHeadsetPropertyChanged(lastAncBatteryController, currentBluetoothDevice(), 10)
                    }
                    // ---- 水月雨线 ----
                    // 状态字段与 OPPO 侧共用（融合设备中心只认这一份），
                    // 所以水月雨的档位要先翻译成 HyperOS 那套 1/2/3/4 语义。
                    HyperPodsAction.PODS_CONNECTED -> {
                        currentBrand = PodBrand.MOONDROP
                        currentAddress = intent.getStringExtra(HyperPodsAction.EXTRA_MAC) ?: currentAddress
                        currentName = intent.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME) ?: currentName
                    }
                    HyperPodsAction.PODS_DISCONNECTED -> {
                        currentAddress = intent.getStringExtra(HyperPodsAction.EXTRA_MAC) ?: currentAddress
                        currentProductId = null
                    }
                    HyperPodsAction.BATTERY_CHANGED -> {
                        currentBrand = PodBrand.MOONDROP
                        currentAddress = intent.getStringExtra(HyperPodsAction.EXTRA_MAC) ?: currentAddress
                        currentBattery = intent.batteryStatusCompat() ?: currentBattery
                        saveState(context)
                    }
                    HyperPodsAction.ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra(HyperPodsAction.EXTRA_MAC) ?: currentAddress
                        currentBrand = PodBrand.MOONDROP
                        intent.getStringArrayListExtra(HyperPodsAction.EXTRA_ANC_IDS)?.let { currentAncIds = it }
                        currentAncIndex = intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, currentAncIndex)
                        currentAnc = oppoAncOfMoondrop(currentAncIds.getOrNull(currentAncIndex))
                        saveState(context)
                        notifyHeadsetPropertyChanged(lastAncBatteryController, currentBluetoothDevice(), 10)
                    }

                    HyperPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        currentBrand = PodBrand.OPPO
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentSpatialAudioMode = intent.getIntExtra("mode", currentSpatialAudioMode)
                            .coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
                        currentAddress?.let { knownAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                }
            }
        }, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        context?.sendBroadcast(Intent(HyperPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    internal fun isOppoPod(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isOppoAddress(address)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        val result = name.contains("oppo", ignoreCase = true)
        if (result && address != null) {
            knownAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
        }
        return result
    }

    internal fun isOppoAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownAddresses
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { method ->
            val address = runCatching { callMethod(info, method) as? String }.getOrNull()
            if (address != null && isManagedAddress(address)) return true
        }
        return false
    }

    private fun miLinkAncState(): Int {
        loadState()
        // 水月雨按自己的家族口径回答。共用字段那套 1/2/3/4 编码里没有「抗风噪」，
        // 直接套会把抗风噪显示成通透、把自适应显示成关闭 —— 小窗上就是「降噪不见了」。
        if (currentBrand == PodBrand.MOONDROP) {
            return miLinkAncOfMoondrop(currentAncIds.getOrNull(currentAncIndex))
        }
        return when (currentAnc) {
            2, 5, 6, 7, 8 -> 1
            3 -> 2
            else -> 0
        }
    }

    /** 水月雨档位 id → 共用字段那套编码（1 关 / 2 降噪 / 3 通透 / 4 自适应）。 */
    private fun oppoAncOfMoondrop(id: String?): Int = when (id) {
        AncMode.NOISE_CANCELLATION.id, AncMode.ANTI_WIND.id -> 2
        AncMode.TRANSPARENCY.id, AncMode.LIVE.id -> 3
        AncMode.ADAPTIVE.id -> 4
        else -> 1
    }

    /** 水月雨档位 id → 融合设备中心那套三档语义（0 关 / 1 降噪 / 2 通透）。 */
    private fun miLinkAncOfMoondrop(id: String?): Int = when (id) {
        AncMode.NOISE_CANCELLATION.id, AncMode.ANTI_WIND.id, AncMode.ADAPTIVE.id -> 1
        AncMode.TRANSPARENCY.id, AncMode.LIVE.id -> 2
        else -> 0
    }

    private fun oppoAncFromMiLink(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun miLinkBatteryLevels(): List<Int> {
        loadState()
        val left = batteryValue(currentBattery.left)
        val right = batteryValue(currentBattery.right)
        val box = batteryValue(currentBattery.case)
        return listOf(
            box,
            left,
            right,
            chargingValue(currentBattery.case),
            chargingValue(currentBattery.left),
            chargingValue(currentBattery.right)
        )
    }

    private fun batteryPercentForMiLink(): Int {
        loadState()
        val values = listOfNotNull(currentBattery.left, currentBattery.right)
            .filter { it.isConnected }
            .map { it.battery.coerceIn(0, 100) }
        return values.minOrNull() ?: 0
    }

    private fun batteryValue(params: com.chenyc.hyperpods.utils.miuiStrongToast.data.PodParams?): Int {
        if (params?.isConnected != true) return -1
        return params.battery.coerceIn(0, 100)
    }

    private fun chargingValue(params: com.chenyc.hyperpods.utils.miuiStrongToast.data.PodParams?): Int {
        return if (params?.isConnected == true && params.isCharging) 1 else 0
    }

    /**
     * 这台设备归本模块管吗 —— 融合设备中心侧的判据。
     *
     * 不能只判 OPPO：水月雨设备名里没有 oppo，而内存里的 currentAddress 在 milink
     * 进程刚起来时还是空的（偏好只在结果回调里才读），于是第一台水月雨会被判成
     * 「不认识」→ 系统的 checkIsMiTWS 回落到真实值 0 → 控制中心的小窗里电量/降噪全空。
     * 因此判据是「已知地址 ∪ 品牌判定」，且判定规则集中在 pods/。
     */
    internal fun isManagedPod(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isManagedAddress(address)) return true
        val brand = resolveBrand(device) ?: return false
        if (address != null) {
            knownAddresses.add(address.uppercase())
            if (currentAddress.isNullOrBlank()) {
                currentAddress = address
                currentName = runCatching { device.name ?: device.alias }.getOrNull()
                currentBrand = brand
            }
        }
        return true
    }

    /** 是否为已接管地址（会先把偏好里的状态读回内存，冷启动也认得出）。 */
    internal fun isManagedAddress(address: String): Boolean {
        ensureStateLoaded()
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownAddresses
    }

    /** 厂牌判定：当前设备用广播记下的品牌，认不出时交给 `pods/PodCatalog`。 */
    private fun brandOf(device: BluetoothDevice): PodBrand? {
        val address = runCatching { device.address }.getOrNull()
        if (address != null && address.equals(currentAddress, ignoreCase = true)) {
            currentBrand?.let { return it }
        }
        return resolveBrand(device)
    }

    private fun resolveBrand(device: BluetoothDevice): PodBrand? {
        val address = runCatching { device.address }.getOrNull()
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        context?.let { ctx ->
            runCatching { PodCatalog.brandOf(ctx, name, address) }.getOrNull()?.let { return it }
        }
        return when {
            PodCatalog.isMoondropName(name) -> PodBrand.MOONDROP
            PodCatalog.isOppoName(name) -> PodBrand.OPPO
            else -> null
        }
    }

    private fun ensureStateLoaded() {
        if (stateLoaded) return
        loadState()
        stateLoaded = true
    }

    /** 融合设备中心只有「关 / 降噪 / 通透」三档，水月雨那边的家族还要更细。 */
    private enum class AncFamily { OFF, NOISE_CANCELLATION, TRANSPARENCY }

    /** 共用字段那套编码（1 关 / 2 降噪 / 3 通透）→ 三档家族。 */
    private fun familyOfOppoAnc(oppoAnc: Int): AncFamily = when (oppoAnc) {
        2 -> AncFamily.NOISE_CANCELLATION
        3 -> AncFamily.TRANSPARENCY
        else -> AncFamily.OFF
    }

    /**
     * 把「关/降噪/通透」翻成水月雨的具体档位下标，回传给蓝牙进程里的 MoondropController。
     *
     * 与模块耳机页走同一条 `ANC_SELECT` 通道（线上传下标，不是 OPPO 的 1/2/3/4 语义）。
     * 家族归属以 `pods/moondrop` 的 [AncMode] 为准：抗风噪/自适应属降噪系，通透系含 live。
     */
    private fun selectMoondropAnc(family: AncFamily) {
        val ids = currentAncIds
        if (ids.isEmpty()) {
            Log.w(TAG, "selectMoondropAnc skipped: 档位表还没到（等 ANC_CHANGED）family=$family")
            return
        }
        val candidates = when (family) {
            AncFamily.NOISE_CANCELLATION ->
                listOf(AncMode.NOISE_CANCELLATION.id, AncMode.ANTI_WIND.id, AncMode.ADAPTIVE.id)
            AncFamily.TRANSPARENCY -> listOf(AncMode.TRANSPARENCY.id, AncMode.LIVE.id)
            AncFamily.OFF -> listOf(AncMode.OFF.id)
        }
        val index = candidates.firstNotNullOfOrNull { id -> ids.indexOf(id).takeIf { it >= 0 } }
        if (index == null) {
            Log.w(TAG, "selectMoondropAnc skipped: 档位表里没有 $family（ids=$ids）")
            return
        }
        val ctx = context ?: run {
            Log.w(TAG, "selectMoondropAnc skipped: context is null")
            return
        }
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.ANC_SELECT).apply {
                setPackage("com.android.bluetooth")
                putExtra(HyperPodsAction.EXTRA_STATUS, index)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { Log.w(TAG, "selectMoondropAnc broadcast failed", it) }
    }

    private fun sendOppoAnc(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoAnc skipped: context is null mode=$mode")
            return
        }
        Intent(HyperPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", mode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun sendAncChanged(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        // 不含 com.android.settings：设置进程不在作用域里（设置侧 hook 已整体删除），
        // 发过去没有接收方。
        listOf(BuildConfig.APPLICATION_ID, "com.milink.service").forEach { targetPackage ->
            ctx.sendBroadcast(Intent(HyperPodsAction.ACTION_PODS_ANC_CHANGED).apply {
                putExtra("status", mode)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    private fun sendOppoGameMode(enabled: Boolean, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        ctx.sendBroadcast(Intent(HyperPodsAction.ACTION_GAME_MODE_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    internal fun sendOppoSpatialAudio(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoSpatialAudio skipped: context is null mode=$mode")
            return
        }
        Intent(HyperPodsAction.ACTION_SPATIAL_AUDIO_SET).apply {
            putExtra("mode", mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING))
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    internal fun sendSpatialChanged(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        val normalizedMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        // 不含 com.android.settings，理由同 [sendAncChanged]。
        listOf(BuildConfig.APPLICATION_ID, "com.milink.service").forEach { targetPackage ->
            ctx.sendBroadcast(Intent(HyperPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED).apply {
                currentAddress?.let { putExtra("address", it) }
                putExtra("mode", normalizedMode)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    internal fun miLinkSpatialMode(): Int {
        loadState()
        if (!spatialAudioPanelEnabled()) return -1
        return when (currentAudioEffectState()) {
            ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING -> if (miLinkDeviceSpatialType() == 1) 11 else 9
            ConfigManager.SPATIAL_AUDIO_FIXED -> 1
            else -> 0
        }
    }

    internal fun oppoSpatialFromMiLink(mode: Int): Int {
        return when (mode) {
            9, 11 -> ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING
            1 -> ConfigManager.SPATIAL_AUDIO_FIXED
            else -> ConfigManager.SPATIAL_AUDIO_OFF
        }
    }

    internal fun miLinkSpatialModeFromMode(mode: Int): Int {
        return when (mode) {
            ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING -> if (miLinkDeviceSpatialType() == 1) 11 else 9
            ConfigManager.SPATIAL_AUDIO_FIXED -> 1
            ConfigManager.SPATIAL_AUDIO_OFF -> 0
            else -> -1
        }
    }

    internal fun miLinkAudioEffectState(): Int {
        loadState()
        return currentAudioEffectState()
    }

    internal fun miLinkDeviceSpatialType(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    internal fun miLinkSwitchState(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    internal fun updateSpatialAudioMode(mode: Int) {
        currentSpatialAudioMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        saveState(context)
    }

    private fun currentAudioEffectState(): Int {
        return if (spatialAudioPanelEnabled()) {
            currentSpatialAudioMode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        } else {
            -1
        }
    }

    internal fun spatialAudioPanelEnabled(): Boolean {
        runCatching { refreshConfig() }
        return ConfigManager.MILINK_CARD_SPATIAL_AUDIO in ConfigManager.milinkCardFeatures() &&
            panelCapabilities().spatialAudioSupported
    }

    private fun gameModePanelEnabled(): Boolean {
        runCatching { refreshConfig() }
        return ConfigManager.MILINK_CARD_GAME_MODE in ConfigManager.milinkCardFeatures() &&
            panelCapabilities().gameModeSupported
    }

    private fun miLinkGameModeState(): Int {
        loadState()
        return if (!gameModePanelEnabled()) -1 else if (currentGameMode) 103 else 0
    }

    private fun hookGameModeCard() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setFindRing", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device) || !gameModePanelEnabled()) return@hookBefore
                lastAncBatteryController = instance
                captureRuntimeContext(instance)
                currentGameMode = (args[1] as? Int ?: 0) != 0
                sendOppoGameMode(currentGameMode)
                saveState(context)
                notifyHeadsetPropertyChanged(instance, device, 10)
                this.result = 100
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setFindRing game mode skipped", it) }

        val synergyViewClass = listOf(
            "com.miui.circulate.world.sticker.ui.SynergyView",
            "com.miui.circulate.world.sticker.p067ui.SynergyView",
        ).firstNotNullOfOrNull { runCatching { findClass(it) }.getOrNull() } ?: return
        runCatching {
            hookBefore(synergyViewClass.getDeclaredMethod("setTitle", Int::class.javaPrimitiveType!!).apply { isAccessible = true }) {
                val view = instance as? View ?: return@hookBefore
                val resId = args[0] as? Int ?: return@hookBefore
                if (viewResourceName(view, view.id) != "mi_audio_ringing_view" || !gameModePanelEnabled()) return@hookBefore
                val sourceName = viewResourceName(view, resId)
                if (sourceName != "circulate_headset_control_audio_find_earphone" &&
                    sourceName != "circulate_headset_control_audio_stop_find_earphone") return@hookBefore
                setSynergyTitle(view, "游戏模式")
                setSynergySubtitle(view, if (currentGameMode) "已开启" else "已关闭")
                setSynergyIcon(view, loadGameModeIcon(view))
                this.result = null
            }
        }.onFailure { Log.w(TAG, "hook SynergyView.setTitle game mode skipped", it) }
    }

    private fun currentBluetoothDevice(): BluetoothDevice? {
        val address = currentAddress ?: return null
        return runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
    }

    private fun viewResourceName(view: View, id: Int): String? {
        if (id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
    }

    private fun setSynergyTitle(view: View, title: CharSequence) {
        val method = view.javaClass.methods.firstOrNull {
            it.name == "setTitle" && it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java))
        }
        if (method != null) {
            runCatching { method.invoke(view, title) }
            return
        }
        findChildByName(view, "item_title")?.let { (it as? TextView)?.text = title }
    }

    private fun setSynergySubtitle(view: View, text: CharSequence) {
        (findChildByName(view, "item_subtitle") as? TextView)?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun setSynergyIcon(view: View, drawable: Drawable?) {
        drawable ?: return
        (findChildByName(view, "item_icon") as? ImageView)?.setImageDrawable(drawable)
    }

    private fun findChildByName(view: View, name: String): View? {
        val packageName = runCatching { view.resources.getResourcePackageName(view.id) }
            .getOrDefault(view.context.packageName)
        val id = view.resources.getIdentifier(name, "id", packageName)
        return id.takeIf { it != 0 }?.let(view::findViewById)
    }

    private fun loadGameModeIcon(view: View): Drawable? {
        gameModeIcon?.let { return it }
        return runCatching {
            view.context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
                .getDrawable(R.drawable.ic_game_mode)
                ?.also { gameModeIcon = it }
        }.getOrNull()
    }

    private fun panelCapabilities() = detectDeviceCapabilities(
        context = context,
        deviceName = backendDeviceName() ?: currentName.orEmpty(),
        productId = runCatching { RfcommController.currentStatusSnapshot().productId }.getOrNull()
            ?: currentProductId,
    )

    private fun backendDeviceName(): String? {
        return runCatching { RfcommController.currentStatusSnapshot().deviceName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    internal fun isTargetAncBatteryModel(model: Any?): Boolean {
        val device = runCatching { callMethod(model, "getBluetoothDevice") as? BluetoothDevice }.getOrNull()
        return device?.let { isOppoPod(it) } == true
    }

    internal fun cacheRuntimeOwner(className: String, owner: Any?) {
        when (className) {
            "com.miui.headset.runtime.AncBatteryController" -> lastAncBatteryController = owner
            "com.miui.headset.runtime.ProfileContext" -> lastProfileContext = owner
        }
    }

    internal fun captureRuntimeContext(owner: Any?) {
        val ownerContext = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastProfileContext, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastAncBatteryController, "context") as? Context }.getOrNull()
            ?: return
        context = ownerContext.applicationContext ?: ownerContext
    }

    internal fun notifySpatialUiChanged(owner: Any?, device: BluetoothDevice, mode: Int) {
        val spatialMode = miLinkSpatialModeFromMode(mode)
        val audioEffectState = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        syncSpatialModel(owner, device, spatialMode)
        syncSpatialModel(lastAncBatteryController, device, spatialMode)
        listOf(owner, lastAncBatteryController, lastProfileContext).distinctBy { it?.javaClass?.name }.forEach { target ->
            notifyHeadsetPropertyChanged(target, device, 9)
            notifyHeadsetPropertyChanged(target, device, 4)
            notifyProfileAudioEffectListeners(target, audioEffectState)
        }
    }

    private fun syncSpatialModel(owner: Any?, device: BluetoothDevice, spatialMode: Int) {
        val model = runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull() ?: return
        if (!isTargetAncBatteryModel(model)) return
        runCatching { setObjectField(model, "spatialState", spatialMode) }
            .onFailure { }
        runCatching { setObjectField(model, "deviceSpatialType", miLinkDeviceSpatialType()) }
            .onFailure { }
    }

    private fun notifyProfileAudioEffectListeners(owner: Any?, audioEffectState: Int) {
        runCatching {
            val listener = getObjectField(owner, "audioEffectListener")
            callMethod(listener, "invoke", audioEffectState)
        }.onFailure { }
    }

    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice?, updateType: Int) {
        if (controller == null || device == null) return
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }.getOrNull() ?: return
        runCatching {
            callMethod(listener, "invoke", device, updateType)
        }.onFailure { }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableStatus(): BatteryParams? {
        return runCatching { getParcelableExtra("status", BatteryParams::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BatteryParams>("status") }.getOrNull()
    }

    private fun Intent.batteryStatusFromExtras(): BatteryParams? {
        if (!hasExtra("left_connected") && !hasExtra("right_connected") && !hasExtra("case_connected")) return null
        return BatteryParams(
            left = PodParams(
                getIntExtra("left_battery", 0),
                getBooleanExtra("left_charging", false),
                getBooleanExtra("left_connected", false),
                0
            ),
            right = PodParams(
                getIntExtra("right_battery", 0),
                getBooleanExtra("right_charging", false),
                getBooleanExtra("right_connected", false),
                0
            ),
            case = PodParams(
                getIntExtra("case_battery", 0),
                getBooleanExtra("case_charging", false),
                getBooleanExtra("case_connected", false),
                0
            )
        )
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putBoolean("game_mode", currentGameMode)
            .putInt("spatial_audio_mode", currentSpatialAudioMode)
            .putString("brand", currentBrand?.name)
            .putString("anc_ids", currentAncIds.joinToString(","))
            .putInt("anc_index", currentAncIndex)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        currentAnc = prefs.getInt("anc", currentAnc)
        currentGameMode = prefs.getBoolean("game_mode", currentGameMode)
        currentSpatialAudioMode = prefs.getInt("spatial_audio_mode", currentSpatialAudioMode)
            .coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        prefs.getString("brand", null)?.let { name ->
            currentBrand = runCatching { PodBrand.valueOf(name) }.getOrNull()
        }
        prefs.getString("anc_ids", null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { currentAncIds = it }
        currentAncIndex = prefs.getInt("anc_index", currentAncIndex)
        currentAddress?.let { knownAddresses.add(it.uppercase()) }
        currentBattery = BatteryParams(
            left = PodParams(
                prefs.getInt("left_battery", currentBattery.left?.battery ?: 0),
                prefs.getBoolean("left_charging", currentBattery.left?.isCharging == true),
                prefs.getBoolean("left_connected", currentBattery.left?.isConnected == true),
                0
            ),
            right = PodParams(
                prefs.getInt("right_battery", currentBattery.right?.battery ?: 0),
                prefs.getBoolean("right_charging", currentBattery.right?.isCharging == true),
                prefs.getBoolean("right_connected", currentBattery.right?.isConnected == true),
                0
            ),
            case = PodParams(
                prefs.getInt("case_battery", currentBattery.case?.battery ?: 0),
                prefs.getBoolean("case_charging", currentBattery.case?.isCharging == true),
                prefs.getBoolean("case_connected", currentBattery.case?.isConnected == true),
                0
            )
        )
    }
}
