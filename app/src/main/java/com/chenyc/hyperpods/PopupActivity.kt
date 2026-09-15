package com.chenyc.hyperpods

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.chenyc.hyperpods.pods.NoiseControlMode
import com.chenyc.hyperpods.pods.moondrop.MoondropModelRegistry
import com.chenyc.hyperpods.ui.components.MoondropAncSwitch
import com.chenyc.hyperpods.utils.miuiStrongToast.data.batteryStatusCompat
import com.chenyc.hyperpods.pods.detectDeviceCapabilities
import com.chenyc.hyperpods.config.ConfigManager
import com.chenyc.hyperpods.ui.AppLocale
import com.chenyc.hyperpods.ui.AppTheme
import com.chenyc.hyperpods.ui.MoondropControls
import com.chenyc.hyperpods.ui.components.AncSwitch
import com.chenyc.hyperpods.ui.components.ConfirmSwitchRow
import com.chenyc.hyperpods.ui.components.PromptVolumeSlider
import com.chenyc.hyperpods.ui.components.QuickControlsHeader
import com.chenyc.hyperpods.ui.components.PodStatus
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 正文总高的上界 = 屏幕高度的这个比例（见竖屏正文里的说明）。
 * 0.6 屏高 + 标题 / 窗口内边距 / 系统栏后仍在屏内，同时给滚动区留出足够高度。
 */
private const val POPUP_BODY_MAX_HEIGHT_FRACTION = 0.6f

class PopupActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val appConfig = ConfigManager.refreshFromPrefs(prefs)
        val bluetoothDevice = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
        // 点通知打开弹窗时，hook 已经把设备名塞进 Intent（putExtra("device_name", alias)）。
        // 拿它当标题的初值，别等广播 —— 否则点开的那一瞬间标题会退化成应用名
        // （原来 deviceName 只由 PODS_CONNECTED 广播填，通知点进来时还是空的）。
        val initialDeviceName = intent.getStringExtra("device_name")?.takeIf { it.isNotBlank() }
            ?: deviceAlias(bluetoothDevice)
        if (appConfig.notificationClickAction != ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
            openNotificationTarget(appConfig.notificationClickAction, bluetoothDevice)
            finish()
            return
        }

        setContent {
            val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = prefs.getInt("accent_mode", 0)) {
                PopupContent(
                    initialDeviceName = initialDeviceName,
                    onMore = {
                        val latestConfig = ConfigManager.refreshFromPrefs(prefs)
                        openMoreTarget(latestConfig.moreClickAction, bluetoothDevice)
                        finish()
                    },
                    onDone = { finish() }
                )
            }
        }
    }

    private fun openNotificationTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            ConfigManager.NOTIFICATION_CLICK_HEYTAP -> openHeyTapOrModule()
            else -> openModule()
        }
    }

    private fun openMoreTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.MORE_CLICK_HEYTAP -> openHeyTapOrModule()
            ConfigManager.MORE_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openModule() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openHeyTapOrModule() {
        val intent = packageManager.getLaunchIntentForPackage("com.heytap.headset")
        if (intent != null) {
            startActivity(intent)
        } else {
            openModule()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSystemSettings(bluetoothDevice: BluetoothDevice?) {
        if (bluetoothDevice == null) {
            openModule()
            return
        }
        // 与「设置 → 蓝牙 → 点设备」落到同一页：用 Settings 内部那张设备详情页的 action。
        // 旧实现是硬编码 HyperOS 的高级耳机 Activity 并塞入模块伪造的设备 id，
        // 和从系统蓝牙列表点进去的不是同一张页面 —— 与 ui/MainUI.kt 的
        // openSystemHeadsetSettings() 保持同一套写法。
        val device = runCatching {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(bluetoothDevice.address)
            } else {
                null
            }
        }.getOrNull()
        val opened = runCatching {
            startActivity(Intent(ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (device != null) putExtra(EXTRA_BLUETOOTH_DEVICE, device)
                putExtra(EXTRA_BLUETOOTH_ADDRESS, bluetoothDevice.address)
            })
        }.isSuccess
        if (opened) return
        // 兜底一：普通蓝牙设置列表页；都打不开才回模块页（不让用户卡住）
        val fallback = runCatching {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.isSuccess
        if (!fallback) openModule()
    }

    private companion object {
        /** 与 ui/MainUI.kt 的 openSystemHeadsetSettings() 用同一套 Settings 内部 action / extra。 */
        const val ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS =
            "android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
        const val EXTRA_BLUETOOTH_DEVICE = "android.bluetooth.device.extra.DEVICE"
        const val EXTRA_BLUETOOTH_ADDRESS = "bluetoothaddress"
    }

    /** 设备名兜底：Intent 里没带就直接问系统要别名（需要 BLUETOOTH_CONNECT；拿不到就返回空串）。 */
    @SuppressLint("MissingPermission")
    private fun deviceAlias(device: BluetoothDevice?): String {
        if (device == null) return ""
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ""
        }
        return runCatching { device.alias ?: device.name }.getOrNull().orEmpty()
    }

    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }
}

@Composable
private fun PopupContent(
    initialDeviceName: String,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val showDialog = remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val themeMode = remember { prefs.getInt("theme_mode", 0) }
    val systemDark = isSystemInDarkTheme()
    val isDarkMode = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val deviceName = remember { mutableStateOf(initialDeviceName) }
    val productId = remember { mutableStateOf<String?>(null) }

    // ── 快捷控制（MiuixMoondrop 应用侧那套折叠菜单）────────────────────────────
    // 能力位与当前值都由蓝牙进程广播过来（CAPABILITIES_CHANGED / *_CHANGED），
    // 打开弹窗时上面那段 UI_INIT 请求会让控制器回推一次，所以这里不缓存、只接收。
    val moondropCaps = remember { mutableStateOf<Bundle?>(null) }
    val promptToneOn = remember { mutableStateOf(false) }
    val promptVolumeRaw = remember { mutableStateOf(0) }
    val lhdcOn = remember { mutableStateOf(false) }
    val dualConnectionOn = remember { mutableStateOf(false) }
    // 「快捷控制」默认折叠（与 MiuixMoondrop 一致）；状态提到这里，重组不会丢用户展开的那一次
    val quickControlsExpanded = remember { mutableStateOf(false) }

    // ── 水月雨（MOONDROP）线 ──────────────────────────────────────────────────
    // 弹窗原来是照 OPPO 那条线写的：档位是 NoiseControlMode 的 1..8 数字档、
    // 只订阅 ACTION_PODS_*。水月雨走的是另一套 action（…moondrop.anc_changed +
    // EXTRA_ANC_IDS），档位是**设备探测出来的字符串 id 列表**，两边对不上，
    // 所以插着水月雨时这一块以前是空的。这里另开一组状态，两条线互不干扰。
    val mdAncIds = remember { mutableStateOf<List<String>>(emptyList()) }
    val mdAncIndex = remember { mutableStateOf(0) }
    // 「游戏模式」= 低延迟音频，但它走的是 **OPPO/欢律私有协议**的一条**设备侧**命令
    // （pods/Packets.kt：AA 09 ... 06 01，GameModeFeature.LOW_LATENCY=0x06），能力位来自
    // OPPO 的 assets/device_models.json。水月雨线上没有对应实现（pods/moondrop 里没有任何
    // gameMode 代码；低延迟开关这个模块不做，系统侧低延迟走系统蓝牙设置页），
    // 所以插水月雨时把这张卡藏掉 —— 否则按下去只会发一条没有接收方的 ACTION_GAME_MODE_SET。
    // 它**不是** HyperOS 蓝牙设置里的「低延迟模式」：那个是系统侧 A2DP 配置，与厂商协议无关。
    val isMoondrop = mdAncIds.value.isNotEmpty() ||
        MoondropModelRegistry.match(deviceName.value) != null
    remember { ConfigManager.refreshFromPrefs(prefs) }
    val capabilities = detectDeviceCapabilities(
        context = context,
        deviceName = deviceName.value,
        productId = productId.value,
    )

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
                            5 -> NoiseControlMode.NOISE_CANCELLATION_SMART
                            6 -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
                            7 -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
                            8 -> NoiseControlMode.NOISE_CANCELLATION_DEEP
                            else -> NoiseControlMode.OFF
                        }
                    }
                    HyperPodsAction.ANC_CHANGED -> {
                        mdAncIds.value =
                            p1.getStringArrayListExtra(HyperPodsAction.EXTRA_ANC_IDS).orEmpty()
                        mdAncIndex.value = p1.getIntExtra(HyperPodsAction.EXTRA_STATUS, 0)
                        if (!showDialog.value) showDialog.value = true
                    }
                    HyperPodsAction.BATTERY_CHANGED -> {
                        p1.batteryStatusCompat()?.let { batteryParams.value = it }
                    }
                    HyperPodsAction.PODS_CONNECTED -> {
                        p1.getStringExtra("device_name")?.takeIf { it.isNotEmpty() }
                            ?.let { deviceName.value = it }
                        if (!showDialog.value) showDialog.value = true
                    }
                    HyperPodsAction.PODS_DISCONNECTED -> {
                        mdAncIds.value = emptyList()
                        mdAncIndex.value = 0
                        // 断开就清掉能力位：快捷控制那几行跟着消失（与 ui/MainUI.kt 同口径）
                        moondropCaps.value = null
                    }
                    HyperPodsAction.CAPABILITIES_CHANGED -> {
                        moondropCaps.value = p1.getBundleExtra(HyperPodsAction.EXTRA_CAPS_BUNDLE)
                        // 型号名也在这条广播里；拿不到设备名时用它兜底当标题
                        p1.getStringExtra(HyperPodsAction.EXTRA_MODEL_NAME)
                            ?.takeIf { it.isNotBlank() && deviceName.value.isBlank() }
                            ?.let { deviceName.value = it }
                    }
                    HyperPodsAction.PROMPT_TONE_CHANGED ->
                        promptToneOn.value = p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)
                    HyperPodsAction.PROMPT_VOLUME_CHANGED ->
                        promptVolumeRaw.value =
                            p1.getIntExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, 0).coerceIn(0, 100)
                    HyperPodsAction.LHDC_CHANGED ->
                        lhdcOn.value = p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)
                    HyperPodsAction.DUAL_CONNECTION_CHANGED ->
                        dualConnectionOn.value =
                            p1.getBooleanExtra(HyperPodsAction.EXTRA_ENABLED, false)
                    HyperPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        batteryParams.value =
                            p1.getParcelableExtra("status", BatteryParams::class.java)!!
                    }
                    HyperPodsAction.ACTION_PODS_CONNECTED -> {
                        deviceName.value = p1.getStringExtra("device_name") ?: ""
                        productId.value = p1.getStringExtra("product_id") ?: productId.value
                        if (!showDialog.value) showDialog.value = true
                    }
                    HyperPodsAction.ACTION_PODS_DISCONNECTED -> {
                        productId.value = null
                        showDialog.value = false
                    }
                    HyperPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }
                    HyperPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED -> {
                        transparencyVocalEnhancement.value = p1.getBooleanExtra("enabled", false)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_CONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED)
            // 水月雨线（与上面那组 OPPO action 并存，两边互不影响）
            addAction(HyperPodsAction.ANC_CHANGED)
            addAction(HyperPodsAction.BATTERY_CHANGED)
            addAction(HyperPodsAction.PODS_CONNECTED)
            addAction(HyperPodsAction.PODS_DISCONNECTED)
            // 快捷控制（提示音含音量 / LHDC / 双设备连接）的能力位与当前值
            addAction(HyperPodsAction.CAPABILITIES_CHANGED)
            addAction(HyperPodsAction.PROMPT_TONE_CHANGED)
            addAction(HyperPodsAction.PROMPT_VOLUME_CHANGED)
            addAction(HyperPodsAction.LHDC_CHANGED)
            addAction(HyperPodsAction.DUAL_CONNECTION_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(HyperPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        context.sendBroadcast(Intent(HyperPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        // 水月雨同样是「先问一次」：控制器收到 UI_INIT 会 refreshAll()，然后把
        // ANC / 电量按 CONSUMERS 推回来 —— 应用进程就在 CONSUMERS 里。
        context.sendBroadcast(Intent(HyperPodsAction.UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })

        onDispose {
            try { context.unregisterReceiver(broadcastReceiver) } catch (_: Exception) {}
        }
    }

    // Timeout fallback: show dialog even if no response within 500ms
    // Periodic refresh: poll earbuds every 15s while popup is open
    LaunchedEffect(Unit) {
        delay(500)
        if (!showDialog.value) showDialog.value = true

        while (true) {
            delay(15_000)
            context.sendBroadcast(Intent(HyperPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    /** 水月雨：把选中的**档位下标**发回蓝牙进程（控制器按型号档案换算成设备码）。 */
    fun setMoondropAnc(index: Int) {
        mdAncIndex.value = index
        context.sendBroadcast(Intent(HyperPodsAction.ANC_SELECT).apply {
            putExtra(HyperPodsAction.EXTRA_STATUS, index)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun setAncMode(mode: NoiseControlMode) {
        ancMode.value = mode
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
            NoiseControlMode.NOISE_CANCELLATION_SMART -> 5
            NoiseControlMode.NOISE_CANCELLATION_LIGHT -> 6
            NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> 7
            NoiseControlMode.NOISE_CANCELLATION_DEEP -> 8
        }
        Intent(HyperPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", status)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        gameMode.value = enabled
        Intent(HyperPodsAction.ACTION_GAME_MODE_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        transparencyVocalEnhancement.value = enabled
        Intent(HyperPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 水月雨命令统一发回协议栈所在进程（com.android.bluetooth），与 ui/MainUI.kt 同一套写法。 */
    fun moondropSend(action: String, configure: (Intent) -> Unit = {}) {
        context.sendBroadcast(Intent(action).apply {
            setPackage("com.android.bluetooth")
            configure(this)
        })
    }

    /**
     * 快捷控制的载体：可见性由能力位门控、当前值与命令回调一起打包成 [MoondropControls]
     * 透传（与 ui/MainUI.kt 耳机页同一个载体，避免在正文函数上再加一长串参数）。
     *
     * connected 固定 true：caps 只在设备连着时才有值 —— 断开那条广播已经把它清成 null，
     * 所以「有没有值」本身就是连接态。
     */
    val moondropControls = MoondropControls(
        connected = true,
        caps = moondropCaps.value,
        promptToneOn = promptToneOn.value,
        onPromptToneChange = { on ->
            moondropSend(HyperPodsAction.PROMPT_TONE_SELECT) {
                it.putExtra(HyperPodsAction.EXTRA_ENABLED, on)
            }
        },
        promptVolumePercent = promptVolumeRaw.value.coerceIn(0, 100),
        onPromptVolumeChange = { percent ->
            moondropSend(HyperPodsAction.PROMPT_VOLUME_SELECT) {
                it.putExtra(HyperPodsAction.EXTRA_PROMPT_VOLUME_RAW, percent.coerceIn(0, 100))
            }
        },
        lhdcOn = lhdcOn.value,
        onLhdcChange = { on ->
            moondropSend(HyperPodsAction.LHDC_SELECT) { it.putExtra(HyperPodsAction.EXTRA_ENABLED, on) }
        },
        dualConnectionOn = dualConnectionOn.value,
        onDualConnectionChange = { on ->
            moondropSend(HyperPodsAction.DUAL_CONNECTION_SELECT) {
                it.putExtra(HyperPodsAction.EXTRA_ENABLED, on)
            }
        },
    )

    Scaffold(containerColor = Color.Transparent) { _ ->
        OverlayDialog(
            title = deviceName.value.ifEmpty { stringResource(R.string.app_name) },
            show = showDialog.value,
            backgroundColor = dialogBgColor,
            onDismissRequest = {
                showDialog.value = false
            },
            onDismissFinished = {
                onDone()
            }
        ) {
            if (isLandscape) {
                LandscapePopupBody(
                    moondrop = moondropControls,
                    quickControlsExpanded = quickControlsExpanded.value,
                    onToggleQuickControls = { quickControlsExpanded.value = !quickControlsExpanded.value },
                    moondropAncIds = mdAncIds.value,
                    moondropAncIndex = mdAncIndex.value,
                    onMoondropAncSelect = ::setMoondropAnc,
                    batteryParams = batteryParams.value,
                    ancMode = ancMode.value,
                    gameMode = gameMode.value,
                    transparencyVocalEnhancement = transparencyVocalEnhancement.value,
                    onAncModeChange = ::setAncMode,
                    onGameModeChange = ::setGameMode,
                    onTransparencyVocalEnhancementChange = ::setTransparencyVocalEnhancement,
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                    adaptiveModeEnabled = capabilities.adaptiveSupported,
                    showGameMode = !isMoondrop,
                )
            } else {
                PortraitPopupBody(
                    moondrop = moondropControls,
                    quickControlsExpanded = quickControlsExpanded.value,
                    onToggleQuickControls = { quickControlsExpanded.value = !quickControlsExpanded.value },
                    moondropAncIds = mdAncIds.value,
                    moondropAncIndex = mdAncIndex.value,
                    onMoondropAncSelect = ::setMoondropAnc,
                    batteryParams = batteryParams.value,
                    ancMode = ancMode.value,
                    gameMode = gameMode.value,
                    transparencyVocalEnhancement = transparencyVocalEnhancement.value,
                    onAncModeChange = ::setAncMode,
                    onGameModeChange = ::setGameMode,
                    onTransparencyVocalEnhancementChange = ::setTransparencyVocalEnhancement,
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                    adaptiveModeEnabled = capabilities.adaptiveSupported,
                    showGameMode = !isMoondrop,
                )
            }
        }
    }
}

@Composable
private fun PortraitPopupBody(
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    adaptiveModeEnabled: Boolean = true,
    /** 水月雨快捷控制（提示音含音量 / LHDC / 双设备连接）的载体，见 ui/MoondropControls.kt。 */
    moondrop: MoondropControls = MoondropControls(),
    quickControlsExpanded: Boolean = false,
    onToggleQuickControls: () -> Unit = {},
    /** 水月雨探测出来的档位 id 列表（空 = 当前不是水月雨，走 OPPO 那套固定档）。 */
    moondropAncIds: List<String> = emptyList(),
    moondropAncIndex: Int = 0,
    onMoondropAncSelect: (Int) -> Unit = {},
    /** 游戏模式只在 OPPO/欢律线上有意义；水月雨线没有对应实现，传 false 直接不显示这张卡。 */
    showGameMode: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 正文总高上界 + 可滚中段 + 固定底栏：照搬 MiuixMoondrop 应用侧同一处修复。
            // Miuix 手机档位不给弹窗内容高度上界（DialogContentLayout 只在大屏档位加），而正文
            // 又是不可滚的 Column —— 快捷控制展开后行数变多，排在末尾的「更多 / 完成」会被压成
            // 0 高度而消失。给正文一个屏高比例的上界，中段滚动；底部按钮行是非权重子项，Column
            // 先测它，所以按钮既不会被压没、也滚不走。
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * POPUP_BODY_MAX_HEIGHT_FRACTION),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // weight(1f, fill = false)：内容短时按内容高度收（折叠态不多占高度），
                // 内容长时吃掉剩余空间并垂直滚动。
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                PodStatus(
                    batteryParams,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                if (moondropAncIds.isNotEmpty()) {
                    // 水月雨：档位由设备能力探测得出（关 / 基本 / 通透 / 抗风噪 / 自适应 …），
                    // 用 MoondropAncSwitch，而不是 OPPO 那套固定八档的 AncSwitch。
                    MoondropAncSwitch(
                        ancIds = moondropAncIds,
                        selectedIndex = moondropAncIndex,
                        onSelect = onMoondropAncSelect,
                    )
                } else {
                    AncSwitch(
                        ancStatus = ancMode,
                        onAncModeChange = onAncModeChange,
                        adaptiveModeEnabled = adaptiveModeEnabled,
                        transparencyVocalEnhancement = transparencyVocalEnhancement,
                        onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
                    )
                }
            }
            // 快捷控制（默认折叠）：只有设备确实提供了其中某一项时才出现
            if (hasQuickControls(moondrop)) {
                Spacer(modifier = Modifier.height(12.dp))
                QuickControlsSection(
                    moondrop = moondrop,
                    expanded = quickControlsExpanded,
                    onToggle = onToggleQuickControls,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (showGameMode) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(R.string.game_mode),
                        summary = stringResource(R.string.game_mode_summary),
                        checked = gameMode,
                        onCheckedChange = onGameModeChange
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LandscapePopupBody(
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    adaptiveModeEnabled: Boolean = true,
    /** 水月雨快捷控制（提示音含音量 / LHDC / 双设备连接）的载体，见 ui/MoondropControls.kt。 */
    moondrop: MoondropControls = MoondropControls(),
    quickControlsExpanded: Boolean = false,
    onToggleQuickControls: () -> Unit = {},
    /** 水月雨探测出来的档位 id 列表（空 = 当前不是水月雨，走 OPPO 那套固定档）。 */
    moondropAncIds: List<String> = emptyList(),
    moondropAncIndex: Int = 0,
    onMoondropAncSelect: (Int) -> Unit = {},
    /** 游戏模式只在 OPPO/欢律线上有意义；水月雨线没有对应实现，传 false 直接不显示这张卡。 */
    showGameMode: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 560.dp)
            .height(240.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                PodStatus(
                    batteryParams,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    compact = true
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                if (moondropAncIds.isNotEmpty()) {
                    MoondropAncSwitch(
                        ancIds = moondropAncIds,
                        selectedIndex = moondropAncIndex,
                        onSelect = onMoondropAncSelect,
                        compact = true,
                    )
                } else {
                    AncSwitch(
                        ancMode,
                        onAncModeChange = onAncModeChange,
                        compact = true,
                        adaptiveModeEnabled = adaptiveModeEnabled,
                        transparencyVocalEnhancement = transparencyVocalEnhancement,
                        onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
                    )
                }
            }
        }
        Column(
            // 横屏只有 240dp：多了折叠组之后内容会超，所以有快捷控制时这一列改成可滚动
            // （没有快捷控制的那条线保持原样：不滚动、居中）。
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight()
                .then(
                    if (hasQuickControls(moondrop)) Modifier.verticalScroll(rememberScrollState())
                    else Modifier
                ),
            verticalArrangement = if (hasQuickControls(moondrop)) Arrangement.Top else Arrangement.Center
        ) {
            if (hasQuickControls(moondrop)) {
                QuickControlsSection(
                    moondrop = moondrop,
                    expanded = quickControlsExpanded,
                    onToggle = onToggleQuickControls,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        if (showGameMode) {
                val gameModeCardColor = if (gameMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
                val gameModeTextColor = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = gameModeCardColor,
                        contentColor = gameModeTextColor
                    ),
                    pressFeedbackType = PressFeedbackType.Sink,
                    showIndication = true,
                    onClick = { onGameModeChange(!gameMode) },
                    onLongPress = {}
                ) {
                    Text(
                        text = stringResource(R.string.game_mode),
                        color = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
        }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 这一次连接里有没有任何一项快捷控制可用（能力位来自 CAPABILITIES_CHANGED）。 */
private fun hasQuickControls(moondrop: MoondropControls): Boolean =
    moondrop.promptToneVisible || moondrop.promptVolumeVisible ||
        moondrop.lhdcVisible || moondrop.dualConnectionVisible

/**
 * 「快捷控制」可折叠分组：分组头 + 展开后的开关卡（提示音含音量 / LHDC / 双设备连接）。
 *
 * 版式与行为照搬 MiuixMoondrop（应用侧）的 ui/PopupActivity.kt：收起时整块不渲染、不占高度，
 * 展开 / 收起走 expandVertically / shrinkVertically；每一行都由能力位门控，缺能力就不出现。
 * LHDC 与双设备连接在设备侧互斥，走 [ConfirmSwitchRow] 先弹窗讲清后果再下发。
 */
@Composable
private fun QuickControlsSection(
    moondrop: MoondropControls,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    QuickControlsHeader(expanded = expanded, onToggle = onToggle)
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            if (moondrop.promptToneVisible) {
                SwitchPreference(
                    title = stringResource(R.string.moondrop_prompt_tone),
                    checked = moondrop.promptToneOn,
                    onCheckedChange = moondrop.onPromptToneChange,
                )
            }
            if (moondrop.promptVolumeVisible) {
                PromptVolumeSlider(
                    percent = moondrop.promptVolumePercent,
                    onPercentChange = moondrop.onPromptVolumeChange,
                )
            }
            if (moondrop.lhdcVisible) {
                ConfirmSwitchRow(
                    title = stringResource(R.string.moondrop_lhdc),
                    summary = stringResource(R.string.moondrop_lhdc_summary),
                    dialogSummary = stringResource(R.string.moondrop_lhdc_dialog_summary),
                    checked = moondrop.lhdcOn,
                    onCheckedChange = moondrop.onLhdcChange,
                )
            }
            if (moondrop.dualConnectionVisible) {
                ConfirmSwitchRow(
                    title = stringResource(R.string.dual_device_connection),
                    summary = stringResource(R.string.moondrop_dual_summary),
                    dialogSummary = stringResource(R.string.moondrop_dual_dialog_summary),
                    checked = moondrop.dualConnectionOn,
                    onCheckedChange = moondrop.onDualConnectionChange,
                )
            }
        }
    }
}
