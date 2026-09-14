package com.chenyc.hyperpods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.moondrop.MoondropGaia
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 手势控制（水月雨 TOUCHV2）。
 *
 * 为什么只有它单独一页：5 个手势槽位 × 2 只耳 = 10 组选择，直接铺在耳机页上
 * 会把电量、降噪、增益那些挤到看不见；其余功能都直接摆在耳机页，不上二级页。
 *
 * 协议侧每个字节装两只耳（高 4 位左、低 4 位右），所以取某一耳的动作要按半字节取；
 * 写回也必须是**全量 5 字节**，因此这里只上报「改了哪个槽位的哪只耳」，由协议侧拼齐全量。
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
    val slots = MoondropGaia.GestureSlot.entries
    val ears = MoondropGaia.Ear.entries
    val options = MoondropGaia.TouchActions.ALL.map { it.labelZh }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item {
            Card {
                if (conf == null) {
                    BasicComponent(title = stringResource(R.string.moondrop_gesture_not_synced))
                } else {
                    ears.forEachIndexed { earIndex, ear ->
                        BasicComponent(title = ear.labelZh)
                        slots.forEachIndexed { slotIndex, slot ->
                            // 高 4 位 = 左耳，低 4 位 = 右耳
                            val packed = conf.getOrNull(slotIndex) ?: 0
                            val current = if (earIndex == 0) {
                                (packed shr 4) and 0x0F
                            } else {
                                packed and 0x0F
                            }
                            val selected = MoondropGaia.TouchActions.ALL.indexOfFirst { it.id == current }
                            OverlayDropdownPreference(
                                title = slot.labelZh,
                                items = options,
                                selectedIndex = selected.coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    MoondropGaia.TouchActions.ALL.getOrNull(index)?.let {
                                        onSelect(slotIndex, earIndex, it.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
