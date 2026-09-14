package com.chenyc.hyperpods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.config.ConfigManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * OPPO / 一加（欢律私有 RFCOMM）专属设置。
 *
 * 为什么单独一页：游戏模式（低延迟）是 [com.chenyc.hyperpods.pods.Packets] 里
 * GameModeFeature 那一族的设备侧命令，水月雨 GAIA 侧既没有对应能力位也没有命令
 * （MoondropController.handleUIEvent 不认这几个 action），摆在通用设置里会让人
 * 以为水月雨也能用。收进来的判定依据是「有没有控制器消费」：
 * auto_game_mode 只有 RfcommController 读；互联卡片的两项也只有
 * MiLinkServiceHook 在面板能力位（OPPO 型号表）成立且设备是 OPPO 时才使能。
 *
 * 界面骨架与主页设置页一致（Miuix Card + Preference），只换位置不改语义：
 * 同样的 preference key、同样的读写回调，参数仍由 MainUI 统一透传。
 */
@Composable
fun OppoOnlySettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    autoGameMode: MutableState<Boolean> = mutableStateOf(false),
    onAutoGameModeChange: (Boolean) -> Unit = {},
    milinkCardFeatures: MutableState<Set<Int>> = mutableStateOf(ConfigManager.DEFAULT_MILINK_CARD_FEATURES),
    onMilinkCardFeaturesChange: (Set<Int>) -> Unit = {},
) {
    val milinkCardFeatureOptions = listOf(
        ConfigManager.MILINK_CARD_GAME_MODE to stringResource(R.string.game_mode),
        ConfigManager.MILINK_CARD_SPATIAL_AUDIO to stringResource(R.string.spatial_audio),
    )
    val milinkCardFeatureEntries = remember(milinkCardFeatures.value, milinkCardFeatureOptions) {
        listOf(
            DropdownEntry(
                items = milinkCardFeatureOptions.map { (value, text) ->
                    DropdownItem(
                        text = text,
                        selected = value in milinkCardFeatures.value,
                        onClick = {
                            val selected = milinkCardFeatures.value
                            onMilinkCardFeaturesChange(if (value in selected) selected - value else selected + value)
                        },
                    )
                }
            )
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
    ) {
        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.auto_game_mode),
                    checked = autoGameMode.value,
                    onCheckedChange = { onAutoGameModeChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.milink_card_features),
                    summary = stringResource(R.string.milink_card_features_summary),
                    entries = milinkCardFeatureEntries,
                    collapseOnSelection = false,
                )
            }
        }
    }
}
