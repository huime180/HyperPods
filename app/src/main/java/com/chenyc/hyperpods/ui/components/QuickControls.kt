/*
 * HyperPods — 「快捷控制」可折叠分组（版式照搬 MiuixMoondrop 应用侧的 ui/PopupActivity.kt）
 *
 * 为什么抽到 components：蓝牙通知弹窗（根包 PopupActivity）和耳机页（ui/pages/PodDetailPage）
 *   都要用同一套「提示音含音量 + LHDC + 双设备连接」的快捷开关，两份实现迟早会漂。
 *
 * 三块内容：
 *   · [QuickControlsHeader]：整行可点的分组头（小标题样式 + 右侧展开/收起箭头）；
 *   · [PromptVolumeSlider]：提示音音量（设备侧是 0..100 连续值，停下 250ms 再下发）；
 *   · [ConfirmSwitchRow]：切换前先弹窗确认的开关（LHDC / 双设备连接在设备侧互斥）。
 */
package com.chenyc.hyperpods.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chenyc.hyperpods.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「快捷控制」分组头：整行可点，右侧箭头表示展开 / 收起。
 *
 * 版式沿用 miuix SmallTitle 的缩进与行高（0.9.2 的 SmallTitle 默认内边距 28dp/8dp、
 * 文字样式取 textStyles.subtitle），只是换成可点 Row 以便挂箭头指示器。
 */
@Composable
fun QuickControlsHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quick_controls),
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Icon(
            imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.quick_controls_collapse else R.string.quick_controls_expand
            ),
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
        )
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
fun PromptVolumeSlider(percent: Int, onPercentChange: (Int) -> Unit) {
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
fun ConfirmSwitchRow(
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
