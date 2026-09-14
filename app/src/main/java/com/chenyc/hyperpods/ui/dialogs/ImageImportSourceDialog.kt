/*
 * HyperPods — 「导入图片」来源选择
 *
 * 耳机页右上角那个 Import 图标原来直接打开欢律导入（OPPO/一加 专用）。现在水月雨也有了
 * 一条来源（官方产品目录，见 MoondropOfficialImageImportDialog），两条路的**前置条件完全不同**：
 *   · 欢律：要 ROOT，而且必须先用欢律连接过一次耳机（图在它私有目录里）；
 *   · 水月雨官方：不要 ROOT，也不要装官方 App，直接按官方目录取图。
 * 与其按厂牌猜用户要哪一条，不如让用户自己选一次 —— 两条来源各自把前置条件写在摘要里。
 */
package com.chenyc.hyperpods.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chenyc.hyperpods.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ImageImportSourceDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onOpenMelodyImport: () -> Unit,
    onOpenMoondropImport: () -> Unit,
) {
    OverlayDialog(
        title = stringResource(R.string.import_source_title),
        summary = stringResource(R.string.import_source_summary),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ImportSourceRow(
                title = stringResource(R.string.import_source_melody_title),
                summary = stringResource(R.string.import_source_melody_summary),
                onClick = onOpenMelodyImport,
            )
            ImportSourceRow(
                title = stringResource(R.string.import_source_official_title),
                summary = stringResource(R.string.import_source_official_summary),
                onClick = onOpenMoondropImport,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ImportSourceRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.headline1,
        )
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
