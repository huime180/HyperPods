package com.chenyc.hyperpods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.moondrop.MoondropGaia
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** 卡片间距（与 PodDetailPage 的卡片间距一致）。 */
private val CARD_GAP = 12.dp

/**
 * 手势控制（水月雨 TOUCHV2）。
 *
 * 为什么只有它单独一页：5 个手势槽位 × 2 只耳 = 10 组选择，直接铺在耳机页上
 * 会把电量、降噪、增益那些挤到看不见；其余功能都直接摆在耳机页，不上二级页。
 *
 * 协议侧每个字节装两只耳（高 4 位左、低 4 位右），所以取某一耳的动作要按半字节取；
 * 写回也必须是**全量 5 字节**，因此这里只上报「改了哪个槽位的哪只耳」，由协议侧拼齐全量。
 * 「同侧长按 1 秒 / 3 秒不能共存」的**自动置空**也在协议侧做（见 MoondropController.setGesture），
 * 这一页只负责把规则写出来，不自己造第二份互斥逻辑。
 *
 * @param conf 5 个字节的当前配置；null = 还没读到（界面显示「尚未同步」，不回一份假值）
 */
@Composable
fun GesturePage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    conf: IntArray?,
    onSelect: (slot: Int, ear: Int, actionId: Int) -> Unit = { _, _, _ -> }
) {
    val current = conf?.let { MoondropGaia.GestureConf(it) }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item {
            Card {
                if (current == null) {
                    BasicComponent(title = stringResource(R.string.moondrop_gesture_not_synced))
                } else {
                    // 顺序：手势优先，每种手势先左后右。每行标题都写成「单击 · 左耳」，
                    // 不做左右耳切换开关 —— 10 行同列，任何时刻都能看清自己在改哪一格。
                    MoondropGaia.GestureSlot.entries.forEach { slot ->
                        MoondropGaia.Ear.entries.forEach { ear ->
                            GestureRow(
                                slot = slot,
                                ear = ear,
                                currentId = current.action(slot, ear),
                                onSelect = { actionId -> onSelect(slot.index, ear.ordinal, actionId) },
                            )
                        }
                    }
                }
            }
        }

        // 唯一的说明：同侧长按两档不能共存。其余手势之间没有互斥关系，不写别的解释。
        item {
            Card(modifier = Modifier.padding(top = CARD_GAP)) {
                BasicComponent(
                    title = stringResource(R.string.gesture_note_title),
                    summary = stringResource(R.string.moondrop_gesture_longpress_note),
                )
            }
        }
    }
}

/**
 * 一行 = 一种手势 + 一只耳朵。
 *
 * 当前值不在动作表里时（固件给了我们还没映射的半字节，例如 `8..15`），列表首项插入一条
 * 未知占位并选中它 —— 这条占位项**不可被选中下发**（选中它没有意义，会被 getOrNull(-1)
 * 丢掉），因此不会把未知值悄悄改成别的动作，也不会显示成空白让人误以为没配置。
 */
@Composable
private fun GestureRow(
    slot: MoondropGaia.GestureSlot,
    ear: MoondropGaia.Ear,
    currentId: Int,
    onSelect: (Int) -> Unit,
) {
    val known = MoondropGaia.TouchActions.ALL
    val masked = currentId and MoondropGaia.TOUCH_ACTION_MASK
    val unmapped = MoondropGaia.TouchActions.byId(masked) == null
    val labels = known.map { actionLabel(it) }
    val items = if (unmapped) listOf(unknownLabel(masked)) + labels else labels
    val selectedIndex = if (unmapped) {
        0
    } else {
        known.indexOfFirst { it.id == masked }.coerceAtLeast(0)
    }

    OverlayDropdownPreference(
        title = stringResource(
            R.string.gesture_slot_ear,
            stringResource(slotTitleRes(slot)),
            stringResource(earRes(ear)),
        ),
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            val offset = if (unmapped) 1 else 0
            known.getOrNull(index - offset)?.let { action -> onSelect(action.id) }
        },
    )
}

/**
 * 动作 → 本地化文案。
 *
 * 绑定走 `TouchAction.i18nKey`（数据层是唯一来源，id 与键都在 MoondropGaia.TouchActions 里），
 * 键 → 资源由下面的 ACTION_LABEL_RES 给出：**静态 R 引用**，release 开了资源压缩也不会被裁掉，
 * 所以不要改成按名字 getIdentifier 动态查。取不到条目才回落到数据层自带的中文标签。
 *
 * 推断项必须如实标注（MoondropGaia 的约定），否则用户会以为它和已观测项同等可靠。
 */
@Composable
private fun actionLabel(action: MoondropGaia.TouchAction): String {
    val base = action.i18nKey?.let { ACTION_LABEL_RES[it] }?.let { stringResource(it) } ?: action.labelZh
    return if (action.inferred) "$base · ${stringResource(R.string.gesture_inferred)}" else base
}

/** 未映射的取值：照实显示，不留空白。 */
@Composable
private fun unknownLabel(masked: Int): String =
    "${stringResource(R.string.gesture_action_unknown)} (0x" + "%X".format(masked) + ")"

/** `TouchAction.i18nKey` → 资源。新增动作时在这里补一行。 */
private val ACTION_LABEL_RES: Map<String, Int> = mapOf(
    "gesture_action_none" to R.string.gesture_action_none,
    "gesture_action_play_pause" to R.string.gesture_action_play_pause,
    "gesture_action_previous_track" to R.string.gesture_action_previous_track,
    "gesture_action_next_track" to R.string.gesture_action_next_track,
    "gesture_action_volume_up" to R.string.gesture_action_volume_up,
    "gesture_action_volume_down" to R.string.gesture_action_volume_down,
    "gesture_action_voice_assistant" to R.string.gesture_action_voice_assistant,
    "gesture_action_anc_switch" to R.string.gesture_action_anc_switch,
)

/** 手势种类 → 标题资源。 */
private fun slotTitleRes(slot: MoondropGaia.GestureSlot): Int = when (slot) {
    MoondropGaia.GestureSlot.SINGLE_TAP -> R.string.gesture_slot_single_tap
    MoondropGaia.GestureSlot.DOUBLE_TAP -> R.string.gesture_slot_double_tap
    MoondropGaia.GestureSlot.TRIPLE_TAP -> R.string.gesture_slot_triple_tap
    MoondropGaia.GestureSlot.LONG_PRESS_1S -> R.string.gesture_slot_long_press_1s
    MoondropGaia.GestureSlot.LONG_PRESS_3S -> R.string.gesture_slot_long_press_3s
}

/** 耳朵 → 标题资源。 */
private fun earRes(ear: MoondropGaia.Ear): Int = when (ear) {
    MoondropGaia.Ear.LEFT -> R.string.gesture_ear_left
    MoondropGaia.Ear.RIGHT -> R.string.gesture_ear_right
}
