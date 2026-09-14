package com.chenyc.hyperpods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import com.chenyc.hyperpods.MainActivity
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.AppRfcommController
import com.chenyc.hyperpods.pods.BtLogStore
import com.chenyc.hyperpods.pods.CustomButtonFunction
import com.chenyc.hyperpods.pods.CustomButtonPosition
import com.chenyc.hyperpods.pods.DeviceProfile
import com.chenyc.hyperpods.pods.DeviceProfileStore
import com.chenyc.hyperpods.pods.EqDevicePreset
import com.chenyc.hyperpods.pods.EqPreset
import com.chenyc.hyperpods.pods.PodImageSlot
import com.chenyc.hyperpods.pods.PodImageStore
import com.chenyc.hyperpods.pods.NoiseControlMode
import com.chenyc.hyperpods.pods.RfcommConnectionMethod
import com.chenyc.hyperpods.pods.SpatialAudioMode
import com.chenyc.hyperpods.utils.SaveOnHideEffect
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import com.chenyc.hyperpods.utils.miuiStrongToast.data.NotificationSettings
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsPrefsKey
import com.chenyc.hyperpods.utils.miuiStrongToast.data.batteryStatusCompat
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

sealed interface Screen : NavKey {
    data object Home : Screen
    data object Settings : Screen
    data object AdvancedSettings : Screen
    data object Profiles : Screen
    data object About : Screen
    data object MoreSettings : Screen
    data object Equalizer : Screen
    data object Debug : Screen
    data object DebugLog : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {}
) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val context = LocalContext.current

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val hookConnected = remember { mutableStateOf(false) }
    // 水月雨线：协议栈跑在被 hook 的蓝牙进程里，应用侧只收广播做镜像
    // （与上面 hookConnected 那一路同一形态，只是 action 与状态项不同）。
    val moondropConnected = remember { mutableStateOf(false) }
    val moondropBattery = remember { mutableStateOf(BatteryParams()) }
    val moondropAncIndex = remember { mutableStateOf(-1) }
    val moondropAncIds = remember { mutableStateOf<List<String>>(emptyList()) }
    val moondropModelName = remember { mutableStateOf("") }
    val moondropHasAdaptive = remember { mutableStateOf(false) }
    val moondropGainIndex = remember { mutableStateOf(0) }
    val moondropGainLabels = remember { mutableStateOf<List<String>>(emptyList()) }
    val moondropLedOn = remember { mutableStateOf(false) }
    val moondropPromptToneOn = remember { mutableStateOf(false) }
    val moondropPromptVolumeRaw = remember { mutableStateOf(0) }
    val moondropLhdcOn = remember { mutableStateOf(false) }
    val moondropDualConnectionOn = remember { mutableStateOf(false) }
    val moondropLowLatencyOn = remember { mutableStateOf(false) }
    // 能力包：决定「更多设置」里显示哪些水月雨项（显示项随耳机切换）
    val moondropCaps = remember { mutableStateOf<Bundle?>(null) }
    val moondropPromptVolumeLabels = remember { (0..10).map { "${it * 10}%" } }
    val gameMode = remember { mutableStateOf(false) }
    val hookEqPresetId = remember { mutableStateOf(-1) }
    val hookDeviceEqPresets = remember { mutableStateOf<List<EqDevicePreset>>(emptyList()) }
    val spatialAudioMode = remember { mutableStateOf(SpatialAudioMode.OFF) }
    val spatialSound = remember { mutableStateOf(false) }
    val noiseLevel = remember { mutableStateOf(com.chenyc.hyperpods.pods.NoiseLevel.DEEP) }
    val smartAncLevel = remember { mutableStateOf(-1) }
    val autoPlayPause = remember { mutableStateOf(false) }
    val dualDevice = remember { mutableStateOf(false) }
    val hookConnectedDevices = remember { mutableStateOf<List<com.chenyc.hyperpods.pods.ConnectedDevice>>(emptyList()) }
    val hookConnectedDevicesReceived = remember { mutableStateOf(false) }

    val prefs = remember {
        context.getSharedPreferences("hyperpods_settings", Context.MODE_PRIVATE)
    }
    val openHeyTap = remember { mutableStateOf(prefs.getBoolean("open_heytap", false)) }
    val milinkSpatialAudioOptionEnabled = remember {
        mutableStateOf(
            prefs.getBoolean(
                HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                HyperPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )
        )
    }
    val rfcommConnectionMethod = remember {
        mutableStateOf(
            RfcommConnectionMethod.fromPreference(
                prefs.getString(RfcommConnectionMethod.PREF_KEY, null)
            )
        )
    }
    val customButtonFunction = remember {
        mutableStateOf(
            CustomButtonFunction.fromPreference(
                prefs.getString(CustomButtonFunction.PREF_KEY, null)
            )
        )
    }
    val customButtonPosition = remember {
        mutableStateOf(
            CustomButtonPosition.fromPreference(
                prefs.getString(CustomButtonPosition.PREF_KEY, null)
            )
        )
    }
    val showConnectionBatteryIsland = remember {
        mutableStateOf(
            prefs.getBoolean(
                HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND
            )
        )
    }
    val temporaryBatteryIslandDurationSeconds = remember {
        mutableStateOf(
            prefs.getInt(
                HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
                HyperPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
            ).takeIf {
                it in HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECOND_OPTIONS
            } ?: HyperPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
        )
    }
    val showConnectionPopup = remember {
        mutableStateOf(
            prefs.getBoolean(
                HyperPodsPrefsKey.SHOW_CONNECTION_POPUP,
                HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP
            )
        )
    }
    val connectionPopupDismissSeconds = remember {
        mutableStateOf(
            prefs.getInt(
                HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                HyperPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
            ).takeIf { it in HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECOND_OPTIONS }
                ?: HyperPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
        )
    }
    val showConnectionNotification = remember {
        mutableStateOf(
            prefs.getBoolean(
                HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                HyperPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION
            )
        )
    }
    val notificationIslandStyle = remember {
        mutableStateOf(
            prefs.getBoolean(
                HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                HyperPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
            )
        )
    }

    // Hook 连接路径不会经过 onDeviceSelected；先按当前配置模式解析一次，连接广播
    // 到达后再按实际蓝牙名称刷新，避免旧的持久化种子配置残留可见性开关。
    val activeProfile = remember {
        mutableStateOf(DeviceProfileStore.resolveProfile(context, prefs))
    }
    val debugMode = remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
    val loggingEnabled = remember { mutableStateOf(prefs.getBoolean("bt_logging_enabled", false)) }
    BtLogStore.isEnabled = loggingEnabled.value
    val appController = remember { AppRfcommController() }
    // 收到 0x8103 后按 productId 在内嵌白名单里精确命中并重建配置（仅自动模式生效）。
    SideEffect {
        appController.productIdResolver = { productId ->
            DeviceProfileStore.profileForProductId(context, prefs, productId)
                ?.also { activeProfile.value = it }
        }
    }
    val appConnState by appController.connectionState.collectAsState()
    val appBattery by appController.batteryParams.collectAsState()
    val appAnc by appController.ancMode.collectAsState()
    val appDeviceName by appController.deviceName.collectAsState()
    val appGameMode by appController.gameMode.collectAsState()
    val appEqPresets by appController.eqPresets.collectAsState()
    val appEqDevicePresets by appController.eqDevicePresets.collectAsState()
    val appEqPresetId by appController.eqPresetId.collectAsState()
    val appSpatialAudioMode by appController.spatialAudioMode.collectAsState()
    val appSpatialSound by appController.spatialSound.collectAsState()
    val appNoiseLevel by appController.noiseLevel.collectAsState()
    val appSmartAncLevel by appController.smartAncLevel.collectAsState()
    val appAutoPlayPause by appController.autoPlayPause.collectAsState()
    val appDualDevice by appController.dualDevice.collectAsState()
    val appConnectedDevices by appController.connectedDevices.collectAsState()
    val appConnectedDevicesReceived by appController.connectedDevicesReceived.collectAsState()

    val isStandaloneConnected = appConnState == AppRfcommController.ConnectionState.CONNECTED
    val isConnecting = appConnState == AppRfcommController.ConnectionState.CONNECTING
    val isError = appConnState == AppRfcommController.ConnectionState.ERROR
    val canShowDetailPage = hookConnected.value || isStandaloneConnected || moondropConnected.value

    /** 读能力包里的一个开关（没探测到就是关闭 → 对应控件不显示）。 */
    fun moondropCap(key: String): Boolean = moondropCaps.value?.getBoolean(key) ?: false

    /** 水月雨档位标识 → 界面上的降噪模式（抗风噪/人声增强按通透处理，语义最接近）。 */
    fun moondropUiModeOf(ancId: String?): NoiseControlMode = when (ancId) {
        "anc" -> NoiseControlMode.NOISE_CANCELLATION
        "transparent", "live", "anti_wind" -> NoiseControlMode.TRANSPARENCY
        "adaptive" -> NoiseControlMode.ADAPTIVE
        else -> NoiseControlMode.OFF
    }

    /** 反向：界面模式 → 档位下标（找不到返回 -1，调用方不发命令）。 */
    fun moondropAncIndexOf(mode: NoiseControlMode): Int {
        val wanted = moondropAncIds.value
        return wanted.indexOfFirst { moondropUiModeOf(it) == mode }
    }

    /**
     * 由能力包构造水月雨的功能档。
     *
     * OppoPods 的功能页显隐本来就由 DeviceProfile 的可见位驱动，所以这里把
     * 「水月雨这台耳机有什么」翻译成同一份配置档，首页/详情页就会随之切换，
     * 不需要给界面加一套并行的 if 分支。
     */
    fun moondropProfileOf(caps: Bundle?): DeviceProfile = DeviceProfile(
        id = "moondrop",
        name = moondropModelName.value,
        // GAIA 侧没有 OPPO 的「降噪深度」「游戏模式」「自动播放暂停」「自定义 EQ」这些开关
        adaptiveVisible = caps?.getBoolean("hasAdaptive") == true,
        gameModeVisible = false,
        noiseLevelVisible = false,
        autoPlayPauseVisible = false,
        dualDeviceVisible = caps?.getBoolean("hasDualConnection") == true,
        connectedDevicesVisible = false,
        // 空间音频：型号可能支持，但界面这一轮还没接（接了再放开，避免点不动）
        spatialAudioVisible = false,
        spatialSoundVisible = false,
        eqPresets = emptyList(),
        customEqVisible = false,
        modelId = "moondrop",
    )

    val displayBattery = when {
        moondropConnected.value -> moondropBattery.value
        isStandaloneConnected -> appBattery
        else -> batteryParams.value
    }
    val displayAnc = when {
        moondropConnected.value -> moondropUiModeOf(moondropAncIds.value.getOrNull(moondropAncIndex.value))
        isStandaloneConnected -> appAnc
        else -> ancMode.value
    }
    val displayGameMode = if (isStandaloneConnected) appGameMode else gameMode.value
    val displayEqPresets = if (isStandaloneConnected) {
        appEqPresets
    } else {
        buildList {
            val byId = LinkedHashMap<Int, EqPreset>()
            activeProfile.value.eqPresets.forEach { byId[it.id] = it }
            hookDeviceEqPresets.value.forEach { entry ->
                if (entry.name.isNotBlank()) byId[entry.id] = EqPreset(entry.id, entry.name)
            }
            addAll(byId.values.sortedBy { it.id })
        }
    }
    val displayEqDevicePresets = if (isStandaloneConnected) {
        appEqDevicePresets
    } else {
        hookDeviceEqPresets.value
    }
    val displayEqPresetId = if (isStandaloneConnected) appEqPresetId else hookEqPresetId.value
    val displayEqCurrentName = displayEqPresets.firstOrNull { it.id == displayEqPresetId }?.name.orEmpty()
    val displaySpatialAudioMode = if (isStandaloneConnected) appSpatialAudioMode else spatialAudioMode.value
    val displaySpatialSound = if (isStandaloneConnected) appSpatialSound else spatialSound.value
    val displayNoiseLevel = if (isStandaloneConnected) appNoiseLevel else noiseLevel.value
    val displaySmartAncLevel = if (isStandaloneConnected) appSmartAncLevel else smartAncLevel.value
    val displayAutoPlayPause = if (isStandaloneConnected) appAutoPlayPause else autoPlayPause.value
    val displayDualDevice = if (isStandaloneConnected) appDualDevice else dualDevice.value
    val displayConnectedDevices = if (isStandaloneConnected) appConnectedDevices else hookConnectedDevices.value
    val displayConnectedDevicesReceived = if (isStandaloneConnected) appConnectedDevicesReceived else hookConnectedDevicesReceived.value
    val displayTitle = when {
        moondropConnected.value -> mainTitle.value
        hookConnected.value -> mainTitle.value
        isStandaloneConnected -> appDeviceName
        isConnecting -> stringResource(R.string.connecting)
        else -> ""
    }

    LaunchedEffect(displayTitle) {
        if (displayTitle.isNotEmpty()) {
            mainTitle.value = displayTitle
        }
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    HyperPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        val status = p1.getIntExtra("status", 1)
                        ancMode.value = when (status) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            else -> NoiseControlMode.OFF
                        }
                    }

                    HyperPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        p1.batteryStatusCompat()?.let {
                            batteryParams.value = it
                        }
                    }

                    HyperPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }

                    HyperPodsAction.ACTION_PODS_EQ_PRESET_CHANGED -> {
                        hookEqPresetId.value = p1.getIntExtra("id", -1)
                        val entriesJson = p1.getStringExtra(HyperPodsAction.EXTRA_EQ_ENTRIES_JSON)
                        hookDeviceEqPresets.value = if (entriesJson != null) {
                            DeviceProfileStore.parseEqEntries(entriesJson)
                        } else {
                            val ids = p1.getIntegerArrayListExtra("preset_ids") ?: arrayListOf()
                            val names = p1.getStringArrayListExtra("preset_names") ?: arrayListOf()
                            ids.mapIndexedNotNull { index, id ->
                                names.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                                    EqDevicePreset(id = id, name = it)
                                }
                            }
                        }
                    }

                    HyperPodsAction.ACTION_PODS_PROFILE_CHANGED -> {
                        p1.getStringExtra(HyperPodsAction.EXTRA_PROFILE_JSON)?.let { json ->
                            runCatching { DeviceProfileStore.parse(json) }
                                .onSuccess { activeProfile.value = it }
                        }
                    }

                    HyperPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        spatialAudioMode.value = p1.getIntExtra("mode", SpatialAudioMode.OFF)
                            .coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
                    }

                    HyperPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED -> {
                        spatialSound.value = p1.getBooleanExtra("enabled", false)
                    }

                    HyperPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED -> {
                        noiseLevel.value = p1.getIntExtra("level", com.chenyc.hyperpods.pods.NoiseLevel.DEEP)
                    }

                    HyperPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED -> {
                        smartAncLevel.value = p1.getIntExtra("level", -1)
                    }

                    HyperPodsAction.ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED -> {
                        autoPlayPause.value = p1.getBooleanExtra("enabled", false)
                    }

                    HyperPodsAction.ACTION_PODS_DUAL_DEVICE_CHANGED -> {
                        dualDevice.value = p1.getBooleanExtra("enabled", false)
                        hookConnectedDevicesReceived.value = p1.getBooleanExtra("devices_received", false)
                    }

                    HyperPodsAction.ACTION_PODS_CONNECTED_DEVICES_CHANGED -> {
                        p1.extras?.classLoader = com.chenyc.hyperpods.pods.ConnectedDevice::class.java.classLoader
                        val devices = p1.getParcelableArrayListExtra("devices", com.chenyc.hyperpods.pods.ConnectedDevice::class.java)
                        hookConnectedDevices.value = devices ?: emptyList()
                        hookConnectedDevicesReceived.value = p1.getBooleanExtra("devices_received", true)
                    }

                    HyperPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceName = p1.getStringExtra("device_name")
                        mainTitle.value = deviceName ?: ""
                        hookEqPresetId.value = -1
                        hookDeviceEqPresets.value = emptyList()
                        if (!deviceName.isNullOrBlank()) {
                            runCatching {
                                DeviceProfileStore.resolveProfile(context, prefs, deviceName)
                            }.onSuccess { activeProfile.value = it }
                        }
                        hookConnected.value = true
                        Log.i("HyperPods", "pod connected via hook: $deviceName")
                    }

                    HyperPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        hookConnected.value = false
                        hookEqPresetId.value = -1
                        hookDeviceEqPresets.value = emptyList()
                        if (p0 is MainActivity) {
                            p0.finish()
                        }
                    }

                    // ── 水月雨线（协议栈在被 hook 的蓝牙进程里，应用侧只做镜像）──

                    HyperPodsAction.PODS_CONNECTED -> {
                        moondropConnected.value = true
                        val name = p1.getStringExtra(HyperPodsAction.EXTRA_DEVICE_NAME)
                        mainTitle.value = name ?: ""
                        Log.i("HyperPods", "moondrop pod connected: $name")
                    }

                    HyperPodsAction.PODS_DISCONNECTED -> {
                        moondropConnected.value = false
                        moondropBattery.value = BatteryParams()
                        moondropAncIndex.value = -1
                        moondropAncIds.value = emptyList()
                        mainTitle.value = ""
                        // 换回本机档，避免把水月雨的能力档留给下一台 OPPO 设备
                        activeProfile.value = DeviceProfileStore.resolveProfile(context, prefs)
                    }

                    HyperPodsAction.BATTERY_CHANGED -> {
                        p1.batteryStatusCompat()?.let { moondropBattery.value = it }
                    }

                    HyperPodsAction.ANC_CHANGED -> {
                        moondropAncIndex.value = p1.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1)
                        p1.getStringArrayListExtra(HyperPodsAction.EXTRA_ANC_IDS)?.let {
                            moondropAncIds.value = it
                        }
                    }

                    // 能力到位就换档：首页与详情页的功能区随这台耳机实际具备的能力收窄/展开
                    HyperPodsAction.GAIN_CHANGED -> {
                        moondropGainIndex.value =
                            p1.getIntExtra(HyperPodsAction.EXTRA_STATUS, 0).coerceAtLeast(0)
                    }

                    HyperPodsAction.LED_CHANGED ->
                        moondropLedOn.value = p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)

                    HyperPodsAction.PROMPT_TONE_CHANGED ->
                        moondropPromptToneOn.value =
                            p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)

                    HyperPodsAction.PROMPT_VOLUME_CHANGED ->
                        moondropPromptVolumeRaw.value =
                            p1.getIntExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, 0).coerceAtLeast(0)

                    HyperPodsAction.LHDC_CHANGED ->
                        moondropLhdcOn.value = p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)

                    HyperPodsAction.DUAL_CONNECTION_CHANGED ->
                        moondropDualConnectionOn.value =
                            p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)

                    HyperPodsAction.LOW_LATENCY_CHANGED ->
                        moondropLowLatencyOn.value =
                            p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)

                    HyperPodsAction.CAPABILITIES_CHANGED -> {
                        moondropModelName.value =
                            p1.getStringExtra(HyperPodsAction.EXTRA_MODEL_NAME).orEmpty()
                        val caps = p1.getBundleExtra(HyperPodsAction.EXTRA_CAPS_BUNDLE)
                        moondropCaps.value = caps
                        moondropHasAdaptive.value = caps?.getBoolean("hasAdaptive") == true
                        moondropGainLabels.value = caps?.getStringArrayList("gainLabels") ?: emptyList()
                        activeProfile.value = moondropProfileOf(caps)
                    }

                    HyperPodsAction.ACTION_BT_LOG_ENTRY -> {
                        val isSend = p1.getBooleanExtra(HyperPodsAction.EXTRA_BT_LOG_IS_SEND, false)
                        val hex = p1.getStringExtra(HyperPodsAction.EXTRA_BT_LOG_HEX) ?: return
                        val label = p1.getStringExtra(HyperPodsAction.EXTRA_BT_LOG_LABEL)
                        BtLogStore.addFromBroadcast(isSend, hex, label)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_EQ_PRESET_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_PROFILE_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_DUAL_DEVICE_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_CONNECTED_DEVICES_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_CONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(HyperPodsAction.ACTION_BT_LOG_ENTRY)
            // 水月雨线
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            addAction(HyperPodsAction.BATTERY_CHANGED)
            addAction(HyperPodsAction.ANC_CHANGED)
            addAction(HyperPodsAction.CAPABILITIES_CHANGED)
            addAction(HyperPodsAction.GAIN_CHANGED)
            addAction(HyperPodsAction.LED_CHANGED)
            addAction(HyperPodsAction.PROMPT_TONE_CHANGED)
            addAction(HyperPodsAction.PROMPT_VOLUME_CHANGED)
            addAction(HyperPodsAction.LHDC_CHANGED)
            addAction(HyperPodsAction.DUAL_CONNECTION_CHANGED)
            addAction(HyperPodsAction.LOW_LATENCY_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(HyperPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
        })
        context.sendBroadcast(Intent(HyperPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            putExtra(HyperPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, true)
        })
        // 水月雨的请求走同一进程、另一个 action：让它重放全量状态（能力/电量/降噪）
        context.sendBroadcast(Intent(HyperPodsAction.UI_INIT).apply {
            setPackage("com.android.bluetooth")
        })

        onDispose {
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            appController.disconnect()
        }
    }

    /** 水月雨的命令统一「广播回协议栈所在进程」（com.android.bluetooth）。 */
    fun moondropSend(action: String, configure: (Intent) -> Unit = {}) {
        context.sendBroadcast(Intent(action).apply {
            setPackage("com.android.bluetooth")
            configure(this)
        })
    }

    fun setMoondropGain(index: Int) =
        moondropSend(HyperPodsAction.GAIN_SELECT) { it.putExtra(HyperPodsAction.EXTRA_STATUS, index) }

    fun setMoondropLed(on: Boolean) =
        moondropSend(HyperPodsAction.LED_SELECT) { it.putExtra(HyperPodsAction.EXTRA_ENABLED, on) }

    fun setMoondropPromptTone(on: Boolean) =
        moondropSend(HyperPodsAction.PROMPT_TONE_SELECT) { it.putExtra(HyperPodsAction.EXTRA_ENABLED, on) }

    fun setMoondropPromptVolumeStep(stepIndex: Int) {
        // 界面按 10% 一档；协议侧收的是 0..100 原始百分比
        moondropSend(HyperPodsAction.PROMPT_VOLUME_SELECT) {
            it.putExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, (stepIndex * 10).coerceIn(0, 100))
        }
    }

    fun setMoondropLhdc(on: Boolean) =
        moondropSend(HyperPodsAction.LHDC_SELECT) { it.putExtra(HyperPodsAction.EXTRA_ENABLED, on) }

    fun setMoondropDualConnection(on: Boolean) =
        moondropSend(HyperPodsAction.DUAL_CONNECTION_SELECT) { it.putExtra(HyperPodsAction.EXTRA_ENABLED, on) }

    fun setMoondropLowLatency(on: Boolean) =
        moondropSend(HyperPodsAction.LOW_LATENCY_SELECT) { it.putExtra(HyperPodsAction.EXTRA_ENABLED, on) }

    fun setAncMode(mode: NoiseControlMode) {
        if (moondropConnected.value) {
            // 水月雨侧线上传的是「档位下标」，不是 OPPO 那套 1/2/3/4 语义，必须反查
            val index = moondropAncIndexOf(mode)
            if (index < 0) {
                Log.w("HyperPods", "moondrop anc $mode unsupported by this model, ignored")
                return
            }
            context.sendBroadcast(Intent(HyperPodsAction.ANC_SELECT).apply {
                setPackage("com.android.bluetooth")
                putExtra(HyperPodsAction.EXTRA_STATUS, index)
            })
            moondropAncIndex.value = index
            return
        }
        if (isStandaloneConnected) {
            appController.setANCMode(mode)
            return
        }
        ancMode.value = mode
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
        }
        Intent(HyperPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setGameMode(enabled)
            return
        }
        gameMode.value = enabled
        Intent(HyperPodsAction.ACTION_GAME_MODE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setEqPreset(id: Int) {
        if (id < 0) return
        if (isStandaloneConnected) {
            appController.setEqPreset(id)
            return
        }
        hookEqPresetId.value = id
        context.sendBroadcast(Intent(HyperPodsAction.ACTION_EQ_PRESET_SET).apply {
            putExtra("id", id)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun saveEqPreset(
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        minValue: Int,
        maxValue: Int,
    ) {
        if (isStandaloneConnected) {
            appController.saveEqPreset(id, name, frequencies, gains, minValue, maxValue)
            return
        }
        context.sendBroadcast(Intent(HyperPodsAction.ACTION_EQ_PRESET_SAVE).apply {
            putExtra("id", id)
            putExtra("name", name)
            putIntegerArrayListExtra("frequencies", ArrayList(frequencies))
            putIntegerArrayListExtra("gains", ArrayList(gains))
            putExtra("min_value", minValue)
            putExtra("max_value", maxValue)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun deleteEqPreset(entry: EqDevicePreset) {
        if (isStandaloneConnected) {
            appController.deleteEqPreset(entry)
            return
        }
        context.sendBroadcast(Intent(HyperPodsAction.ACTION_EQ_PRESET_DELETE).apply {
            putExtra(
                HyperPodsAction.EXTRA_EQ_ENTRIES_JSON,
                DeviceProfileStore.exportEqEntries(listOf(entry)),
            )
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        if (isStandaloneConnected) {
            appController.setSpatialAudioMode(normalizedMode)
            return
        }
        spatialAudioMode.value = normalizedMode
        Intent(HyperPodsAction.ACTION_SPATIAL_AUDIO_SET).apply {
            this.putExtra("mode", normalizedMode)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setSpatialSound(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setSpatialSound(enabled)
            return
        }
        spatialSound.value = enabled
        Intent(HyperPodsAction.ACTION_SPATIAL_SOUND_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setNoiseLevel(level: Int) {
        if (isStandaloneConnected) {
            appController.setNoiseLevel(level)
            return
        }
        noiseLevel.value = level
        Intent(HyperPodsAction.ACTION_NOISE_LEVEL_SET).apply {
            this.putExtra("level", level)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setAutoPlayPause(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setAutoPlayPause(enabled)
            return
        }
        autoPlayPause.value = enabled
        Intent(HyperPodsAction.ACTION_AUTO_PLAY_PAUSE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setDualDevice(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setDualDevice(enabled)
            return
        }
        dualDevice.value = enabled
        Intent(HyperPodsAction.ACTION_DUAL_DEVICE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun broadcastActiveProfile(profile: DeviceProfile) {
        Intent(HyperPodsAction.ACTION_ACTIVE_PROFILE_CHANGED).apply {
            setPackage("com.android.bluetooth")
            putExtra(HyperPodsAction.EXTRA_PROFILE_JSON, DeviceProfileStore.exportJson(profile))
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        // 按当前模式预解析（自动模式先用蓝牙名预判），连上后 0x8103 再精确校正。
        val deviceName = if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            device.name
        } else {
            null
        }
        val resolved = runCatching {
            DeviceProfileStore.resolveProfile(context, prefs, deviceName)
        }.getOrElse { activeProfile.value }
        activeProfile.value = resolved
        appController.connect(
            device = device,
            connectionMethod = rfcommConnectionMethod.value,
            profile = resolved
        )
    }

    fun refreshStatus() {
        if (isStandaloneConnected) {
            appController.refreshStatus()
        } else if (hookConnected.value) {
            context.sendBroadcast(Intent(HyperPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                putExtra(HyperPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, true)
            })
        } else if (moondropConnected.value) {
            context.sendBroadcast(Intent(HyperPodsAction.UI_INIT).apply {
                setPackage("com.android.bluetooth")
            })
        }
    }

    fun broadcastNotificationSettings(
        showConnectionBatteryIslandEnabled: Boolean,
        temporaryBatteryIslandDurationSecondsValue: Int,
        showConnectionPopupEnabled: Boolean,
        connectionPopupDismissSecondsValue: Int,
        showConnectionNotificationEnabled: Boolean,
        notificationIslandStyleEnabled: Boolean
    ) {
        val settings = NotificationSettings(
            showConnectionBatteryIsland = showConnectionBatteryIslandEnabled,
            temporaryBatteryIslandDurationSeconds = temporaryBatteryIslandDurationSecondsValue,
            showConnectionPopup = showConnectionPopupEnabled,
            connectionPopupDismissSeconds = connectionPopupDismissSecondsValue,
            showConnectionNotification = showConnectionNotificationEnabled,
            notificationIslandStyle = notificationIslandStyleEnabled,
            updatedAt = System.currentTimeMillis()
        )
        settings.writeToPrefs(prefs, commit = true)
        listOf("com.android.bluetooth", "com.xiaomi.bluetooth").forEach { targetPackage ->
            Intent(HyperPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED).apply {
                setPackage(targetPackage)
                settings.putExtras(this)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    fun broadcastMilinkSpatialAudioOption(enabled: Boolean) {
        listOf("com.milink.service", "com.android.settings").forEach { targetPackage ->
            Intent(HyperPodsAction.ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED).apply {
                setPackage(targetPackage)
                putExtra(HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    // 配置切换时：隐藏的 UI 自动关闭，重新显示时恢复原值
    SaveOnHideEffect(
        visible = activeProfile.value.spatialAudioVisible,
        currentValue = milinkSpatialAudioOptionEnabled.value,
        hiddenValue = false,
        onValueChange = { enabled ->
            milinkSpatialAudioOptionEnabled.value = enabled
            prefs.edit()
                .putBoolean(HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, enabled)
                .commit()
            broadcastMilinkSpatialAudioOption(enabled)
        }
    )
    SaveOnHideEffect(
        visible = activeProfile.value.spatialAudioVisible,
        currentValue = displaySpatialAudioMode,
        hiddenValue = SpatialAudioMode.OFF,
        onValueChange = { setSpatialAudioMode(it) }
    )
    SaveOnHideEffect(
        visible = activeProfile.value.spatialSoundVisible,
        currentValue = displaySpatialSound,
        hiddenValue = false,
        onValueChange = { setSpatialSound(it) }
    )

    fun broadcastCustomButtonFunction(value: String) {
        Intent(HyperPodsAction.ACTION_CUSTOM_BUTTON_FUNCTION_CHANGED).apply {
            setPackage("com.milink.service")
            putExtra(CustomButtonFunction.PREF_KEY, value)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun broadcastCustomButtonPosition(value: String) {
        Intent(HyperPodsAction.ACTION_CUSTOM_BUTTON_POSITION_CHANGED).apply {
            setPackage("com.milink.service")
            putExtra(CustomButtonPosition.PREF_KEY, value)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    // Each entry has its own Scaffold+TopAppBar so the full page transitions together
    val entryProvider = entryProvider<Screen> {
        entry<Screen.Home> {
            val homeTitle = mainTitle.value.ifEmpty { stringResource(R.string.app_name) }
            val topAppBarScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = homeTitle,
                        largeTitle = homeTitle,
                        scrollBehavior = topAppBarScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { (context as? Activity)?.finish() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        },
                        actions = {
                            if (canShowDetailPage) {
                                IconButton(onClick = { refreshStatus() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Refresh,
                                        contentDescription = "Refresh"
                                    )
                                }
                            }
                            IconButton(
                                onClick = { backStack.add(Screen.Settings) },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Settings,
                                    contentDescription = "Settings"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                AnimatedContent(
                    targetState = when {
                        canShowDetailPage -> "detail"
                        isConnecting -> "connecting"
                        isError -> "error"
                        else -> "picker"
                    },
                    label = "MainPageAnim"
                ) { state ->
                    when (state) {
                        "detail" -> PodDetailPage(
                            modifier = Modifier
                                .overScrollVertical()
                                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                            contentPadding = padding,
                            batteryParams = displayBattery,
                            ancMode = displayAnc,
                            onAncModeChange = { setAncMode(it) },
                            gameMode = displayGameMode,
                            onGameModeChange = { setGameMode(it) },
                            eqVisible = activeProfile.value.eqPresets.isNotEmpty() ||
                                    activeProfile.value.customEqVisible,
                            eqCurrentName = displayEqCurrentName,
                            onOpenEqualizer = { backStack.add(Screen.Equalizer) },
                            spatialAudioMode = displaySpatialAudioMode,
                            onSpatialAudioModeChange = { setSpatialAudioMode(it) },
                            spatialAudioVisible = activeProfile.value.spatialAudioVisible,
                            spatialSound = displaySpatialSound,
                            onSpatialSoundChange = { setSpatialSound(it) },
                            spatialSoundVisible = activeProfile.value.spatialSoundVisible,
                            adaptiveModeEnabled = if (moondropConnected.value) {
                                moondropHasAdaptive.value
                            } else {
                                activeProfile.value.adaptiveVisible
                            },
                            gameModeVisible = activeProfile.value.gameModeVisible,
                            noiseLevelVisible = activeProfile.value.noiseLevelVisible,
                            noiseLevel = displayNoiseLevel,
                            smartAncLevel = displaySmartAncLevel,
                            onNoiseLevelChange = { setNoiseLevel(it) },
                            homeImageFile = PodImageStore.customFile(context, PodImageSlot.HOME_IMAGE),
                            onOpenMoreSettings = { backStack.add(Screen.MoreSettings) }
                        )
                        "connecting" -> Box(Modifier.padding(padding).fillMaxSize()) { ConnectingPage() }
                        "error" -> Box(Modifier.padding(padding).fillMaxSize()) { ErrorPage(onRetry = { appController.disconnect() }) }
                        else -> Box(Modifier.padding(padding).fillMaxSize()) { DevicePickerPage(onDeviceSelected = { onDeviceSelected(it) }) }
                    }
                }
            }
        }
        entry<Screen.Equalizer> {
            val equalizerScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            var isEqEditing by remember { mutableStateOf(false) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.sound_effects),
                        largeTitle = stringResource(R.string.sound_effects),
                        scrollBehavior = equalizerScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (isEqEditing) isEqEditing = false
                                    else backStack.removeLast()
                                },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        },
                        actions = {
                            if (activeProfile.value.customEqVisible) {
                                if (isEqEditing) {
                                    TextButton(
                                        text = stringResource(R.string.done),
                                        onClick = { isEqEditing = false },
                                    )
                                } else {
                                    val editEntry = DropdownEntry(
                                        items = listOf(
                                            DropdownItem(
                                                text = stringResource(R.string.eq_edit),
                                                onClick = { isEqEditing = true },
                                            )
                                        )
                                    )
                                    OverlayIconDropdownMenu(entry = editEntry) {
                                        Icon(
                                            imageVector = MiuixIcons.More,
                                            contentDescription = stringResource(R.string.eq_edit)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                EqualizerPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(equalizerScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    builtInPresets = activeProfile.value.eqPresets,
                    devicePresets = displayEqDevicePresets,
                    selectedId = displayEqPresetId,
                    customEqVisible = activeProfile.value.customEqVisible,
                    customEqFrequencies = activeProfile.value.customEqFrequencies,
                    customEqMaxPresets = activeProfile.value.customEqMaxPresets,
                    isEditing = isEqEditing,
                    onSelectPreset = { setEqPreset(it) },
                    onOpenCustomEq = { preset -> setEqPreset(preset.id) },
                    onSavePreset = { id, name, frequencies, gains, minValue, maxValue ->
                        saveEqPreset(id, name, frequencies, gains, minValue, maxValue)
                    },
                    onDeletePreset = { deleteEqPreset(it) },
                )
            }
        }
        entry<Screen.Settings> {
            val settingsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.settings),
                        largeTitle = stringResource(R.string.settings),
                        scrollBehavior = settingsScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                SettingsPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(settingsScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    showConnectionBatteryIsland = showConnectionBatteryIsland,
                    onShowConnectionBatteryIslandChange = {
                        showConnectionBatteryIsland.value = it
                        prefs.edit()
                            .putBoolean(HyperPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, it)
                            .commit()
                        broadcastNotificationSettings(
                            it,
                            temporaryBatteryIslandDurationSeconds.value,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    temporaryBatteryIslandDurationSeconds = temporaryBatteryIslandDurationSeconds,
                    onTemporaryBatteryIslandDurationSecondsChange = {
                        temporaryBatteryIslandDurationSeconds.value = it
                        prefs.edit()
                            .putInt(HyperPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            it,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    showConnectionNotification = showConnectionNotification,
                    onShowConnectionNotificationChange = {
                        showConnectionNotification.value = it
                        prefs.edit()
                            .putBoolean(HyperPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            temporaryBatteryIslandDurationSeconds.value,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            it,
                            notificationIslandStyle.value
                        )
                    },
                    notificationIslandStyle = notificationIslandStyle,
                    onNotificationIslandStyleChange = {
                        notificationIslandStyle.value = it
                        prefs.edit()
                            .putBoolean(HyperPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            temporaryBatteryIslandDurationSeconds.value,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            it
                        )
                    },
                    onOpenAdvancedSettings = { backStack.add(Screen.AdvancedSettings) },
                    onOpenAbout = { backStack.add(Screen.About) },
                    onOpenProfiles = { backStack.add(Screen.Profiles) },
                    debugMode = debugMode.value,
                    onOpenDebug = { backStack.add(Screen.Debug) }
                )
            }
        }
        entry<Screen.Profiles> {
            val profilesScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.device_profiles),
                        largeTitle = stringResource(R.string.device_profiles),
                        scrollBehavior = profilesScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                ProfilesPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(profilesScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    prefs = prefs,
                    activeProfile = activeProfile.value,
                    onActiveProfileChanged = { p ->
                        activeProfile.value = p
                        appController.setProfile(p)
                        broadcastActiveProfile(p)
                    }
                )
            }
        }
        entry<Screen.AdvancedSettings> {
            val advancedScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.advanced_settings),
                        largeTitle = stringResource(R.string.advanced_settings),
                        scrollBehavior = advancedScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                AdvancedSettingsPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(advancedScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    openHeyTap = openHeyTap,
                    onOpenHeyTapChange = {
                        openHeyTap.value = it
                        prefs.edit().putBoolean("open_heytap", it).apply()
                    },
                    rfcommConnectionMethod = rfcommConnectionMethod,
                    onRfcommConnectionMethodChange = {
                        rfcommConnectionMethod.value = it
                        prefs.edit()
                            .putString(RfcommConnectionMethod.PREF_KEY, it.preferenceValue)
                            .apply()
                    },
                    adaptiveVisible = activeProfile.value.adaptiveVisible,
                    spatialAudioVisible = activeProfile.value.spatialAudioVisible,
                    spatialSoundVisible = activeProfile.value.spatialSoundVisible,
                    showConnectionPopup = showConnectionPopup,
                    onShowConnectionPopupChange = {
                        showConnectionPopup.value = it
                        prefs.edit()
                            .putBoolean(HyperPodsPrefsKey.SHOW_CONNECTION_POPUP, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            temporaryBatteryIslandDurationSeconds.value,
                            it,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    connectionPopupDismissSeconds = connectionPopupDismissSeconds,
                    onConnectionPopupDismissSecondsChange = {
                        connectionPopupDismissSeconds.value = it
                        prefs.edit()
                            .putInt(HyperPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            temporaryBatteryIslandDurationSeconds.value,
                            showConnectionPopup.value,
                            it,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    milinkSpatialAudioOptionEnabled = milinkSpatialAudioOptionEnabled,
                    onMilinkSpatialAudioOptionEnabledChange = {
                        milinkSpatialAudioOptionEnabled.value = it
                        prefs.edit()
                            .putBoolean(HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, it)
                            .commit()
                        broadcastMilinkSpatialAudioOption(it)
                    },
                    customButtonFunction = customButtonFunction,
                    onCustomButtonFunctionChange = {
                        customButtonFunction.value = it
                        prefs.edit()
                            .putString(CustomButtonFunction.PREF_KEY, it.preferenceValue)
                            .commit()
                        broadcastCustomButtonFunction(it.preferenceValue)
                    },
                    customButtonPosition = customButtonPosition,
                    onCustomButtonPositionChange = {
                        customButtonPosition.value = it
                        prefs.edit()
                            .putString(CustomButtonPosition.PREF_KEY, it.preferenceValue)
                            .commit()
                        broadcastCustomButtonPosition(it.preferenceValue)
                    }
                )
            }
        }
        entry<Screen.About> {
            AboutPage(
                onBack = { backStack.removeLast() },
                debugMode = debugMode.value,
                onDebugModeChanged = { enabled ->
                    debugMode.value = enabled
                    prefs.edit().putBoolean("debug_mode", enabled).commit()
                }
            )
        }
        entry<Screen.MoreSettings> {
            val moreSettingsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.more_settings),
                        largeTitle = stringResource(R.string.more_settings),
                        scrollBehavior = moreSettingsScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                MoreSettingsPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(moreSettingsScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    autoPlayPauseVisible = activeProfile.value.autoPlayPauseVisible,
                    autoPlayPause = displayAutoPlayPause,
                    onAutoPlayPauseChange = { setAutoPlayPause(it) },
                    // 水月雨接上时，双设备连接走它自己的命令与状态；OPPO 侧保持原样
                    dualDeviceVisible = if (moondropConnected.value) {
                        moondropCap("hasDualConnection")
                    } else {
                        activeProfile.value.dualDeviceVisible
                    },
                    dualDevice = if (moondropConnected.value) {
                        moondropDualConnectionOn.value
                    } else {
                        displayDualDevice
                    },
                    onDualDeviceChange = { on ->
                        if (moondropConnected.value) setMoondropDualConnection(on) else setDualDevice(on)
                    },
                    connectedDevicesVisible = activeProfile.value.connectedDevicesVisible,
                    connectedDevices = displayConnectedDevices,
                    connectedDevicesReceived = displayConnectedDevicesReceived,
                    // 以下 6 项只在识别到水月雨且探测出对应能力时出现
                    gainVisible = moondropConnected.value && moondropCap("hasGain"),
                    gainLabels = moondropGainLabels.value,
                    gainIndex = moondropGainIndex.value,
                    onGainChange = { setMoondropGain(it) },
                    ledVisible = moondropConnected.value && moondropCap("hasLed"),
                    ledOn = moondropLedOn.value,
                    onLedChange = { setMoondropLed(it) },
                    promptToneVisible = moondropConnected.value && moondropCap("hasPromptTone"),
                    promptToneOn = moondropPromptToneOn.value,
                    onPromptToneChange = { setMoondropPromptTone(it) },
                    promptVolumeVisible = moondropConnected.value && moondropCap("hasPromptVolume"),
                    promptVolumeLabels = moondropPromptVolumeLabels,
                    promptVolumeIndex = (moondropPromptVolumeRaw.value / 10).coerceIn(0, 10),
                    onPromptVolumeChange = { setMoondropPromptVolumeStep(it) },
                    lhdcVisible = moondropConnected.value && moondropCap("hasLhdc"),
                    lhdcOn = moondropLhdcOn.value,
                    onLhdcChange = { setMoondropLhdc(it) },
                    lowLatencyVisible = moondropConnected.value && moondropCap("hasLowLatency"),
                    lowLatencyOn = moondropLowLatencyOn.value,
                    onLowLatencyChange = { setMoondropLowLatency(it) }
                )
            }
        }
        entry<Screen.Debug> {
            val debugScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.debug_mode),
                        largeTitle = stringResource(R.string.debug_mode),
                        scrollBehavior = debugScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                DebugPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(debugScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    loggingEnabled = loggingEnabled.value,
                    onLoggingEnabledChange = {
                        loggingEnabled.value = it
                        BtLogStore.isEnabled = it
                        prefs.edit().putBoolean("bt_logging_enabled", it).commit()
                        if (it) {
                            BtLogStore.addRecv(byteArrayOf(), "日志已启用，等待蓝牙数据...")
                        }
                    },
                    onOpenLog = { backStack.add(Screen.DebugLog) }
                )
            }
        }
        entry<Screen.DebugLog> {
            val logScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.debug_view_log),
                        largeTitle = stringResource(R.string.debug_view_log),
                        scrollBehavior = logScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                DebugLogPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(logScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    onClear = { BtLogStore.clear() }
                )
            }
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider
    )

    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        }
    )
}

@Composable
fun ConnectingPage() {
    val primaryColor = MiuixTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(48.dp)) {
                drawArc(
                    color = primaryColor,
                    startAngle = angle,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Text(
                stringResource(R.string.connecting),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun ErrorPage(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.connect_failed),
                color = Color(0xFFFF3B30)
            )
            TextButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
