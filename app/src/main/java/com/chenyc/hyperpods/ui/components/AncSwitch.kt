package com.chenyc.hyperpods.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.NoiseControlMode
import com.chenyc.hyperpods.pods.isNoiseCancellation
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

private const val ANIM_DURATION = 300

@Composable
fun AncSwitch(
    ancStatus: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    compact: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: ((Boolean) -> Unit)? = null
) {
    val verticalPadding = if (compact) 8.dp else 16.dp
    val tabMinWidth = 0.dp
    val tabMaxWidth = if (compact) 72.dp else 98.dp
    val tabHeight = if (compact) 38.dp else 45.dp
    val tabSpacing = if (compact) 4.dp else 9.dp
    val tabOuterPadding = if (compact) 8.dp else 12.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AncButton(
                offIconRes = R.drawable.ic_openanc_off,
                onIconRes = R.drawable.ic_openanc_on,
                label = stringResource(R.string.noise_cancellation_title),
                isSelected = ancStatus.isNoiseCancellation(),
                onClick = { onAncModeChange(NoiseControlMode.NOISE_CANCELLATION) },
                modifier = Modifier.weight(1f),
                compact = compact
            )
            // Adaptive模式按钮：仅当设置中启用Adaptive模式时显示
            if (adaptiveModeEnabled) {
                AncButton(
                    offIconRes = R.drawable.ic_adaptive_off,
                    onIconRes = R.drawable.ic_adaptive_on,
                    label = stringResource(R.string.adaptive_title),
                    isSelected = ancStatus == NoiseControlMode.ADAPTIVE,
                    onClick = { onAncModeChange(NoiseControlMode.ADAPTIVE) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }
            AncButton(
                offIconRes = R.drawable.ic_transparent_off,
                onIconRes = R.drawable.ic_transparent_on,
                label = stringResource(R.string.transparency_title),
                isSelected = ancStatus == NoiseControlMode.TRANSPARENCY,
                onClick = { onAncModeChange(NoiseControlMode.TRANSPARENCY) },
                modifier = Modifier.weight(1f),
                compact = compact
            )
            AncButton(
                offIconRes = R.drawable.ic_closeanc_off,
                onIconRes = R.drawable.ic_closeanc_on,
                label = stringResource(R.string.off),
                isSelected = ancStatus == NoiseControlMode.OFF,
                onClick = { onAncModeChange(NoiseControlMode.OFF) },
                modifier = Modifier.weight(1f),
                compact = compact
            )
        }

        if (ancStatus.isNoiseCancellation()) {
            val modes = listOf(
                NoiseControlMode.NOISE_CANCELLATION_SMART,
                NoiseControlMode.NOISE_CANCELLATION_LIGHT,
                NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
                NoiseControlMode.NOISE_CANCELLATION_DEEP
            )
            val isSmart = ancStatus == NoiseControlMode.NOISE_CANCELLATION_SMART
            val smartLevelIndex = when (smartAncLevel) {
                NoiseControlMode.NOISE_CANCELLATION_LIGHT -> 1
                NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> 2
                NoiseControlMode.NOISE_CANCELLATION_DEEP -> 3
                else -> null
            }
            val smartName = stringResource(R.string.noise_cancellation_smart)
            val tabs = listOf(
                smartName,
                stringResource(R.string.noise_cancellation_light),
                stringResource(R.string.noise_cancellation_medium),
                stringResource(R.string.noise_cancellation_deep)
            )

            AncStrengthTabRow(
                tabs = tabs,
                selectedTabIndex = modes.indexOf(ancStatus).takeIf { it >= 0 } ?: 0,
                assistHighlightedIndex = if (isSmart) smartLevelIndex else null,
                onTabSelected = { onAncModeChange(modes[it]) },
                compact = compact,
                minWidth = tabMinWidth,
                tabMaxWidth = tabMaxWidth,
                height = tabHeight,
                itemSpacing = tabSpacing,
                outerPadding = tabOuterPadding,
                topPadding = if (compact) 8.dp else 16.dp
            )
        }

        if (ancStatus == NoiseControlMode.TRANSPARENCY && onTransparencyVocalEnhancementChange != null) {
            val tabs = listOf(
                stringResource(R.string.transparency_title),
                stringResource(R.string.transparency_vocal_enhancement)
            )
            ResponsiveAncTabRow(
                tabs = tabs,
                selectedTabIndex = if (transparencyVocalEnhancement) 1 else 0,
                onTabSelected = { onTransparencyVocalEnhancementChange(it == 1) },
                compact = compact,
                minWidth = tabMinWidth,
                tabMaxWidth = tabMaxWidth,
                height = tabHeight,
                itemSpacing = tabSpacing,
                outerPadding = tabOuterPadding
            )
        }
    }
}

@Composable
private fun AncStrengthTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    assistHighlightedIndex: Int?,
    onTabSelected: (Int) -> Unit,
    compact: Boolean,
    minWidth: Dp,
    tabMaxWidth: Dp,
    height: Dp,
    itemSpacing: Dp,
    outerPadding: Dp,
    topPadding: Dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center
    ) {
        val rowModifier = Modifier
            .padding(horizontal = outerPadding)
            .fillMaxWidth(if (maxWidth >= 480.dp) 0.8f else 1f)

        TabRowWithContour(
            tabs = tabs.map { "" },
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = rowModifier,
            minWidth = minWidth,
            maxWidth = tabMaxWidth,
            height = height,
            itemSpacing = itemSpacing
        )

        Row(
            modifier = rowModifier.height(height),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedTabIndex
                val isAssistHighlighted = index == assistHighlightedIndex && !isSelected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(height),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        fontSize = if (compact) 13.sp else 14.sp,
                        fontWeight = if (isSelected || isAssistHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            isSelected -> MiuixTheme.colorScheme.primary
                            isAssistHighlighted -> MiuixTheme.colorScheme.primary.copy(alpha = 0.72f)
                            else -> MiuixTheme.colorScheme.onBackground
                        }
                    )
                }

                if (index != tabs.lastIndex) {
                    Spacer(modifier = Modifier.size(itemSpacing))
                }
            }
        }
    }
}

@Composable
private fun ResponsiveAncTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    compact: Boolean,
    minWidth: Dp,
    tabMaxWidth: Dp,
    height: Dp,
    itemSpacing: Dp,
    outerPadding: Dp,
    topPadding: Dp = if (compact) 8.dp else 16.dp
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center
    ) {
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = Modifier
                .padding(horizontal = outerPadding)
                .fillMaxWidth(if (maxWidth >= 480.dp) 0.8f else 1f),
            minWidth = minWidth,
            maxWidth = tabMaxWidth,
            height = height,
            itemSpacing = itemSpacing
        )
    }
}

@Composable
private fun AncButton(
    offIconRes: Int,
    onIconRes: Int,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val boxSize = if (compact) 40.dp else 60.dp
    val iconSize = if (compact) (if (isSelected) 40.dp else 32.dp) else (if (isSelected) 60.dp else 48.dp)

    val textColor by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        animationSpec = tween(ANIM_DURATION),
        label = "anc_text_color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(boxSize),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = isSelected,
                animationSpec = tween(ANIM_DURATION),
                label = "anc_icon"
            ) { selected ->
                Box(
                    modifier = Modifier.size(boxSize),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = themedPainterResource(if (selected) onIconRes else offIconRes),
                        contentDescription = label,
                        modifier = Modifier.size(if (selected) boxSize else (if (compact) 32.dp else 48.dp))
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

/**
 * Loads a drawable resource respecting the app's theme override.
 * Handles both bitmap and vector drawables by rendering via Canvas when needed.
 */
@Composable
private fun themedPainterResource(@androidx.annotation.DrawableRes id: Int): Painter {
    val context = LocalContext.current
    val themeConfig = LocalConfiguration.current
    val sysNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val themeNightMode = themeConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

    return if (sysNightMode == themeNightMode) {
        painterResource(id)
    } else {
        val themedResources = remember(context, themeNightMode) {
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or themeNightMode
                }
            ).resources
        }
        remember(id, themeNightMode) {
            val drawable = themedResources.getDrawable(id, null)
            if (drawable is BitmapDrawable) {
                BitmapPainter(drawable.bitmap.asImageBitmap())
            } else {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                BitmapPainter(bitmap.asImageBitmap())
            }
        }
    }
}

/** 降噪族的档位 id：主排点「降噪」时，这些档位都算命中。 */
private val ANC_NC_FAMILY = listOf("anc", "anti_wind", "adaptive")

/** 降噪子档的**展示顺序**（与参照实现一致：自适应 / 抗风 / 普通）。 */
private val ANC_SUB_ORDER = listOf("adaptive", "anti_wind", "anc")

/** 通透族的档位 id（人声通透也属于通透）。 */
private val ANC_TRANSPARENCY_FAMILY = listOf("transparent", "live")

/** 档位 id → 文案。表里没有的 id 回落到 id 本身，绝不静默吞掉。 */
@Composable
private fun ancIdLabel(id: String): String = when (id) {
    "off" -> stringResource(R.string.off)
    "anc" -> stringResource(R.string.anc_normal_title)
    "anti_wind" -> stringResource(R.string.anc_anti_wind_title)
    "adaptive" -> stringResource(R.string.adaptive_title)
    "transparent" -> stringResource(R.string.transparency_title)
    "live" -> stringResource(R.string.anc_live_title)
    else -> id
}

/**
 * 水月雨（GAIA）的降噪选择器。
 *
 * 为什么不复用 [AncSwitch]：它的选项是 OPPO 那套固定的四档
 * （降噪 / 自适应 / 通透 / 关闭），而水月雨的档位是**型号相关**的 ——
 * 例如布丁是「关闭 / 基本降噪 / 抗风噪 / 自适应 / 通透」五档，
 * 用四档枚举表达必然丢档（抗风噪一度被错误地并进通透）。
 *
 * 因此这里完全按探测到的档位表渲染：蓝牙进程把 MoondropGaia.AncMode 的 id 列表广播过来，
 * 有几个档就画几个，界面不做任何型号猜测。降噪族按 OPPO 那套分层呈现 ——
 * 主排选「降噪」，再在子排里选具体是哪一种。
 */
@Composable
fun MoondropAncSwitch(
    ancIds: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    compact: Boolean = false
) {
    val current = ancIds.getOrNull(selectedIndex)
    val ncId = ANC_NC_FAMILY.firstOrNull { it in ancIds }
    val transparencyId = ANC_TRANSPARENCY_FAMILY.firstOrNull { it in ancIds }

    // 记住上次选中的降噪子档：点「降噪」回到它，而不是每次都掉回普通
    // （与参照实现同行为，默认普通）
    var lastSubMode by remember(ancIds) {
        mutableStateOf(ANC_SUB_ORDER.firstOrNull { it in ancIds } ?: "anc")
    }
    LaunchedEffect(current) {
        val mode = current
        if (mode != null && mode in ANC_SUB_ORDER) lastSubMode = mode
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 8.dp else 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ncId != null) {
                AncButton(
                    offIconRes = R.drawable.ic_openanc_off,
                    onIconRes = R.drawable.ic_openanc_on,
                    label = stringResource(R.string.noise_cancellation_title),
                    isSelected = current in ANC_NC_FAMILY,
                    onClick = { onSelect(ancIds.indexOf(lastSubMode)) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }
            if (transparencyId != null) {
                AncButton(
                    offIconRes = R.drawable.ic_transparent_off,
                    onIconRes = R.drawable.ic_transparent_on,
                    label = stringResource(R.string.transparency_title),
                    isSelected = current in ANC_TRANSPARENCY_FAMILY,
                    onClick = { onSelect(ancIds.indexOf(transparencyId)) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }
            if ("off" in ancIds) {
                AncButton(
                    offIconRes = R.drawable.ic_closeanc_off,
                    onIconRes = R.drawable.ic_closeanc_on,
                    label = stringResource(R.string.off),
                    isSelected = current == "off",
                    onClick = { onSelect(ancIds.indexOf("off")) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }
        }

        // 降噪族子档：只在当前处于降噪族、且设备确实提供多于一种降噪时出现
        val ncVariants = ANC_SUB_ORDER.filter { it in ancIds }
        if (current in ANC_NC_FAMILY && ncVariants.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ncVariants.forEach { id ->
                    AncButton(
                        offIconRes = if (id == "adaptive") R.drawable.ic_adaptive_off else R.drawable.ic_openanc_off,
                        onIconRes = if (id == "adaptive") R.drawable.ic_adaptive_on else R.drawable.ic_openanc_on,
                        label = ancIdLabel(id),
                        isSelected = current == id,
                        onClick = { onSelect(ancIds.indexOf(id)) },
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                }
            }
        }
    }
}
