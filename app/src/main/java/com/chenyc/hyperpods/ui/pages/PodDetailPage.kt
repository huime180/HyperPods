package com.chenyc.hyperpods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.config.ConfigManager
import com.chenyc.hyperpods.pods.NoiseControlMode
import com.chenyc.hyperpods.pods.WearStatus
import com.chenyc.hyperpods.ui.PodImagePart
import com.chenyc.hyperpods.ui.moondropDeviceImage
import com.chenyc.hyperpods.ui.components.AncSwitch
import com.chenyc.hyperpods.ui.components.MoondropAncSwitch
import com.chenyc.hyperpods.ui.components.PodStatus
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.chenyc.hyperpods.ui.MoondropControls
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    gameMode: Boolean = false,
    gameModeSupported: Boolean = false,
    onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = ConfigManager.SPATIAL_AUDIO_OFF,
    onSpatialAudioModeChange: (Int) -> Unit = {},
    dualDeviceConnection: Boolean = false,
    onDualDeviceConnectionChange: (Boolean) -> Unit = {},
    spatialAudioSupported: Boolean = false,
    spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    moondrop: MoondropControls = MoondropControls(),
    equalizerVisible: Boolean = false,
    dualDeviceSupported: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    onOpenSystemHeadsetSettings: () -> Unit = {},
    boxImagePath: String? = null,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = rememberPodImagePainter(boxImagePath, podName),
                    contentDescription = "Earphones",
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 360.dp),
                    contentScale = ContentScale.FillWidth
                )
                Text(
                    text = podName,
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                podControlItems(
                    moondrop = moondrop,
                    batteryParams = batteryParams,
                    wearStatus = wearStatus,
                    ancMode = ancMode,
                    onAncModeChange = onAncModeChange,
                    smartAncLevel = smartAncLevel,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                    gameMode = gameMode,
                    gameModeSupported = gameModeSupported,
                    onGameModeChange = onGameModeChange,
                    spatialAudioMode = spatialAudioMode,
                    onSpatialAudioModeChange = onSpatialAudioModeChange,
                    dualDeviceConnection = dualDeviceConnection,
                    onDualDeviceConnectionChange = onDualDeviceConnectionChange,
                    spatialAudioSupported = spatialAudioSupported,
                    spatialSoundSupported = spatialSoundSupported,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    equalizerVisible = equalizerVisible,
                    dualDeviceSupported = dualDeviceSupported,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenSystemHeadsetSettings = onOpenSystemHeadsetSettings,
                    bottomContentPadding = bottomContentPadding,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Image(
                painter = rememberPodImagePainter(boxImagePath, podName),
                contentDescription = "Earphones",
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 16.dp),
                contentScale = ContentScale.FillWidth
            )
        }

        podControlItems(
            moondrop = moondrop,
            batteryParams = batteryParams,
            wearStatus = wearStatus,
            ancMode = ancMode,
            onAncModeChange = onAncModeChange,
            smartAncLevel = smartAncLevel,
            transparencyVocalEnhancement = transparencyVocalEnhancement,
            onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
            gameMode = gameMode,
            gameModeSupported = gameModeSupported,
            onGameModeChange = onGameModeChange,
            spatialAudioMode = spatialAudioMode,
            onSpatialAudioModeChange = onSpatialAudioModeChange,
            dualDeviceConnection = dualDeviceConnection,
            onDualDeviceConnectionChange = onDualDeviceConnectionChange,
            spatialAudioSupported = spatialAudioSupported,
            spatialSoundSupported = spatialSoundSupported,
            adaptiveModeEnabled = adaptiveModeEnabled,
            equalizerVisible = equalizerVisible,
            dualDeviceSupported = dualDeviceSupported,
            onOpenEqualizer = onOpenEqualizer,
            onOpenSystemHeadsetSettings = onOpenSystemHeadsetSettings,
            bottomContentPadding = bottomContentPadding,
        )
    }
}

@Composable
private fun rememberPodImagePainter(path: String?, deviceName: String?) = remember(path, deviceName) {
    path?.let {
        runCatching { BitmapFactory.decodeFile(it) }
            .getOrNull()
            ?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    }
    // 用户自定义图（相册导入）优先；没有就按机型取专属图；再没有才回落通用图
} ?: painterResource(moondropDeviceImage(deviceName, PodImagePart.BOX) ?: R.drawable.img_box)

private fun LazyListScope.podControlItems(
    moondrop: MoondropControls,
    batteryParams: BatteryParams,
    wearStatus: WearStatus,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode?,
    transparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    gameMode: Boolean,
    gameModeSupported: Boolean,
    onGameModeChange: (Boolean) -> Unit,
    spatialAudioMode: Int,
    onSpatialAudioModeChange: (Int) -> Unit,
    dualDeviceConnection: Boolean,
    onDualDeviceConnectionChange: (Boolean) -> Unit,
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    equalizerVisible: Boolean,
    dualDeviceSupported: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenSystemHeadsetSettings: () -> Unit,
    bottomContentPadding: Dp,
) {
    val spatialAudioValues = listOf(
        ConfigManager.SPATIAL_AUDIO_OFF,
        ConfigManager.SPATIAL_AUDIO_FIXED,
        ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING,
    )

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            PodStatus(
                batteryParams = batteryParams,
                wearStatus = wearStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // 水月雨的档位是型号相关的（布丁五档），装不进 OPPO 那套四档枚举，
            // 所以有档位表时改用它自己的选择器，否则沿用原来的。
            if (moondrop.ancVisible) {
                MoondropAncSwitch(
                    ancIds = moondrop.ancIds,
                    selectedIndex = moondrop.ancIndex,
                    onSelect = moondrop.onAncChange
                )
            } else {
                AncSwitch(
                    ancStatus = ancMode,
                    onAncModeChange = onAncModeChange,
                    smartAncLevel = smartAncLevel,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
                )
            }
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            if (gameModeSupported) {
                SwitchPreference(
                    title = stringResource(R.string.game_mode),
                    summary = stringResource(R.string.game_mode_summary),
                    checked = gameMode,
                    onCheckedChange = onGameModeChange
                )
            }
            if (spatialAudioSupported) {
                val spatialAudioOptions = listOf(
                    stringResource(R.string.off),
                    stringResource(R.string.spatial_audio_fixed),
                    stringResource(R.string.spatial_audio_head_tracking),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.spatial_audio),
                    summary = stringResource(R.string.spatial_audio_summary),
                    items = spatialAudioOptions,
                    selectedIndex = spatialAudioValues.indexOf(spatialAudioMode).coerceAtLeast(0),
                    onSelectedIndexChange = { onSpatialAudioModeChange(spatialAudioValues[it]) }
                )
            }
            if (spatialSoundSupported) {
                SwitchPreference(
                    title = stringResource(R.string.spatial_sound),
                    summary = stringResource(if (spatialAudioMode != ConfigManager.SPATIAL_AUDIO_OFF) R.string.enabled else R.string.off),
                    checked = spatialAudioMode != ConfigManager.SPATIAL_AUDIO_OFF,
                    onCheckedChange = {
                        onSpatialAudioModeChange(if (it) ConfigManager.SPATIAL_AUDIO_FIXED else ConfigManager.SPATIAL_AUDIO_OFF)
                    }
                )
            }
            if (equalizerVisible) {
                BasicComponent(
                    title = stringResource(R.string.eq_preset_title),
                    summary = stringResource(R.string.eq_preset_summary),
                    onClick = onOpenEqualizer,
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }
            if (dualDeviceSupported) {
                SwitchPreference(
                    title = stringResource(R.string.dual_device_connection),
                    summary = stringResource(if (dualDeviceConnection) R.string.enabled else R.string.off),
                    checked = dualDeviceConnection,
                    onCheckedChange = onDualDeviceConnectionChange
                )
            }

            // ---- 水月雨（GAIA）功能项：直接摆在耳机页上，不做二级页 ----
            if (moondrop.codecVisible) {
                BasicComponent(
                    title = stringResource(R.string.active_codec),
                    summary = moondrop.activeCodec
                )
            }
            // 每一项都由能力位决定是否出现；值由蓝牙进程广播而来，命令广播回去。
            if (moondrop.promptToneVisible) {
                SwitchPreference(
                    title = stringResource(R.string.moondrop_prompt_tone),
                    checked = moondrop.promptToneOn,
                    onCheckedChange = moondrop.onPromptToneChange
                )
            }
            if (moondrop.promptVolumeVisible) {
                PromptVolumeSlider(
                    percent = moondrop.promptVolumePercent,
                    onPercentChange = moondrop.onPromptVolumeChange,
                )
            }
            if (moondrop.gainVisible) {
                // 档位名来自型号档案（低/中/高），部分机型是反向映射，所以只按索引走
                OverlayDropdownPreference(
                    title = stringResource(R.string.moondrop_gain),
                    items = moondrop.gainLabels,
                    selectedIndex = moondrop.gainIndex.coerceIn(0, moondrop.gainLabels.size - 1),
                    onSelectedIndexChange = moondrop.onGainChange
                )
            }
            if (moondrop.ledVisible) {
                SwitchPreference(
                    title = stringResource(R.string.moondrop_led),
                    checked = moondrop.ledOn,
                    onCheckedChange = moondrop.onLedChange
                )
            }
            if (moondrop.lhdcVisible) {
                // LHDC 与双设备连接在芯片侧互斥：切换前弹窗讲清楚，确认后再下发
                ConfirmSwitchRow(
                    title = stringResource(R.string.moondrop_lhdc),
                    summary = stringResource(R.string.moondrop_lhdc_summary),
                    // 开关行只写「提供高品质音频体验」，互斥后果放在弹窗里讲
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
            // 空间音频 / 头部追踪：水月雨 feature 18 的两行开关，各自由能力位
            // （hasSpatial / hasHeadTracking）决定是否出现。**未读到时按「关」保守呈现**
            // （载体里的默认值就是 false，不猜成已开启）。
            // 注意：上面由 spatialAudioSupported 门控的那个下拉是 **OPPO 专有**的一条，
            // 走 milink 链路，与这里的水月雨两行互不影响，不要合并。
            if (moondrop.spatialVisible) {
                SwitchPreference(
                    title = stringResource(R.string.moondrop_spatial_audio),
                    summary = stringResource(R.string.moondrop_spatial_audio_summary),
                    checked = moondrop.spatialOn,
                    onCheckedChange = moondrop.onSpatialChange,
                )
            }
            if (moondrop.headTrackingVisible) {
                SwitchPreference(
                    title = stringResource(R.string.moondrop_head_tracking),
                    summary = stringResource(R.string.moondrop_head_tracking_summary),
                    checked = moondrop.headTrackingOn,
                    onCheckedChange = moondrop.onHeadTrackingChange,
                )
            }
            if (moondrop.gestureVisible) {
                BasicComponent(
                    title = stringResource(R.string.moondrop_gesture),
                    summary = stringResource(R.string.moondrop_gesture_summary),
                    onClick = moondrop.onOpenGesture,
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }

            // 系统蓝牙设置入口：直接复用顶栏图标那一个实现（MainUI.openSystemHeadsetSettings），
            // 设备地址由它自己从当前已连接设备取，本页不另存一份、也不硬编码。
            // 这里不再加可见性条件：本页只在已连接时才渲染（MainUI 的 showEarphoneDetail）。
            BasicComponent(
                title = stringResource(R.string.system_bluetooth_settings),
                summary = stringResource(R.string.system_bluetooth_settings_summary),
                onClick = onOpenSystemHeadsetSettings,
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                    )
                },
            )
        }
    }
    item {
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

/**
 * 提示音音量滑条。
 *
 * 设备侧这是一个 **0..100 的连续原始值**（协议里只有 raw，没有「档」），以前用 11 档下拉表达：
 * 既不是设备的真实粒度，也很难正好停在想要的数值上。这里改成滑条 + 右侧实时百分比。
 *
 * 拖动过程中**不**下发：一次拖动会产生几十次回调，RFCOMM 上就是几十次写，
 * 所以停下 250ms 再发一次（[LaunchedEffect] 的 key 是 local，每次变化都会重启协程，
 * 天然起到防抖作用）。下发后设备回读会刷新 percent，local 与 percent 一致时不再发。
 */
@Composable
private fun PromptVolumeSlider(percent: Int, onPercentChange: (Int) -> Unit) {
    var local by remember(percent) { mutableStateOf(percent.coerceIn(0, 100)) }
    LaunchedEffect(local) {
        if (local != percent) {
            delay(250)
            onPercentChange(local)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.moondrop_prompt_volume), fontSize = 16.sp)
            Text(text = "$local%", fontSize = 14.sp)
        }
        Slider(
            value = local / 100f,
            onValueChange = { local = (it.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * 「切换前先弹窗确认」的开关（LHDC / 双设备连接）。
 *
 * 这两个开关在设备侧互斥（开一个会关掉另一个）。以前只在副标题里写一行说明，
 * 用户拨完才发现另一个被关掉了；现在改成先弹窗把后果讲清楚、确认后才下发。
 * 取消时开关保持原状 —— 因为 checked 一直是外部状态，本地只暂存用户想改成的值。
 */
@Composable
private fun ConfirmSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    dialogSummary: String = summary,
) {
    var pending by remember { mutableStateOf<Boolean?>(null) }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { pending = it },
    )
    // 弹窗版式与 MiuixMoondrop（应用侧那份）一致：**满宽**两枚等宽 TextButton，
    // 确认键用 primary 色，标题讲清「互斥」这件事而不是重复开关名 ——
    // 原来右对齐挤两个小字按钮，点起来也别扭。
    OverlayDialog(
        title = stringResource(R.string.conflict_lhdc_dual_title),
        summary = dialogSummary,
        show = pending != null,
        onDismissRequest = { pending = null },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // 用 spacedBy(20.dp) 而不是 SpaceBetween + Spacer：本文件的 import 里没有
            // Spacer / Modifier.width，两枚等宽按钮 + 20dp 间隔的视觉效果完全一样，少引两个符号。
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { pending = null },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.conflict_switch),
                onClick = {
                    pending?.let(onCheckedChange)
                    pending = null
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
