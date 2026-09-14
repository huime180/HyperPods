/*
 * HyperPods — 「从水月雨官方 App 导入图片」对话框
 *
 * 与欢律那条（MelodyImageImportDialog）并列的第二条图片导入来源：那条要 ROOT + 先用欢律连一次，
 * 这条直接按 MOONDROP 官方产品目录取图（见 utils/MoondropOfficialImages.kt 的说明），
 * 不需要 ROOT、也不需要先装官方 App。
 *
 * 交互照 MelodyImageImportDialog 的版式：OverlayDialog + 列表 + 选中行高亮 + 取消/导入。
 * 两处刻意的不同：
 *   · 官方目录有 105 款，一次拉 105 张缩略图不现实，所以列表行用默认占位图，
 *     **只为当前选中项**下载一张预览图（约 160~230 KB）；
 *   · 官方图不一定三张齐全（很多机型没有左/右耳图），所以导入要求拿到 **BOX**，
 *     左/右耳图有就一起导入 —— 不像欢律那条要求三张齐全。
 */
package com.chenyc.hyperpods.ui.dialogs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.config.PodImageResource
import com.chenyc.hyperpods.utils.MoondropOfficialImages
import com.chenyc.hyperpods.utils.MoondropOfficialProduct
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MoondropOfficialImageImportDialog(
    show: Boolean,
    currentAddress: String,
    currentName: String,
    onDismissRequest: () -> Unit,
    onImport: (String, String, Map<PodImageResource, ByteArray>) -> Unit,
) {
    var products by remember(show) { mutableStateOf<List<MoondropOfficialProduct>>(emptyList()) }
    var selected by remember(show) { mutableStateOf<MoondropOfficialProduct?>(null) }
    var preview by remember(show) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(show) { mutableStateOf(false) }
    var importing by remember(show) { mutableStateOf(false) }
    // 失败后「重试」用：改一下就重新触发下面的 LaunchedEffect
    var reloadKey by remember(show) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(show, reloadKey) {
        if (!show) return@LaunchedEffect
        loading = true
        val list = withContext(Dispatchers.IO) { MoondropOfficialImages.fetchProducts() }
        products = list
        // 默认选中当前这副耳机（名字能对上就选它，对不上选第一款）—— 多数人就是要导当前这副
        selected = list.firstOrNull { currentName.isNotBlank() && it.displayName.contains(currentName, true) }
            ?: list.firstOrNull { currentName.isNotBlank() && currentName.contains(it.displayName, true) }
            ?: list.firstOrNull()
        loading = false
    }

    // 只为选中项下载一张预览图：105 款全下太重（每张 160~230 KB）
    LaunchedEffect(selected?.uuid) {
        preview = null
        val path = selected?.boxPath() ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            MoondropOfficialImages.downloadImage(path)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    OverlayDialog(
        title = stringResource(R.string.official_import_title),
        summary = stringResource(R.string.official_import_summary),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = stringResource(R.string.official_import_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfiniteProgressIndicator()
            }
        } else if (products.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.official_import_error),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                TextButton(
                    text = stringResource(R.string.official_import_retry),
                    onClick = { reloadKey += 1 },
                )
            }
        } else {
            preview?.let { bitmap ->
                Image(
                    painter = BitmapPainter(bitmap.asImageBitmap()),
                    contentDescription = selected?.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .padding(bottom = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(products, key = { it.uuid }) { product ->
                    MoondropOfficialProductRow(
                        product = product,
                        selected = product.uuid == selected?.uuid,
                        onClick = { selected = product },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                text = stringResource(R.string.official_import_action),
                onClick = {
                    val product = selected ?: return@TextButton
                    if (importing) return@TextButton
                    importing = true
                    scope.launch {
                        val images: Map<PodImageResource, ByteArray> = withContext(Dispatchers.IO) {
                            val result = mutableMapOf<PodImageResource, ByteArray>()
                            product.boxPath()
                                ?.let { MoondropOfficialImages.downloadImage(it) }
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { result[PodImageResource.BOX] = it }
                            product.leftPath()
                                ?.let { MoondropOfficialImages.downloadImage(it) }
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { result[PodImageResource.LEFT] = it }
                            product.rightPath()
                                ?.let { MoondropOfficialImages.downloadImage(it) }
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { result[PodImageResource.RIGHT] = it }
                            result
                        }
                        importing = false
                        // 至少要有机型图才回调；左/右耳图缺就是不导入那两槽（官方目录里很多款没有）
                        if (images.containsKey(PodImageResource.BOX)) {
                            onImport(currentAddress, currentName, images)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun MoondropOfficialProductRow(
    product: MoondropOfficialProduct,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.img_box),
            contentDescription = product.displayName,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.displayName,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = stringResource(
                    if (product.leftPath() != null && product.rightPath() != null) {
                        R.string.official_import_parts_full
                    } else {
                        R.string.official_import_parts_box_only
                    },
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
