/*
 * HyperPods — 「从水月雨官方 App 导入图片」对话框
 *
 * 与欢律那条（MelodyImageImportDialog）并列的第二条图片导入来源：那条要 ROOT + 先用欢律连一次，
 * 这条直接按 MOONDROP 官方产品目录取图（见 utils/MoondropOfficialImages.kt 的说明），
 * 不需要 ROOT、也不需要先装官方 App。
 *
 * 交互照 MelodyImageImportDialog 的版式：OverlayDialog + 列表 + 选中行高亮 + 取消/导入。
 * 几处刻意的不同：
 *   · 当前这副耳机在目录里的对应款**置顶**并标一行「当前机型」（名字匹配用
 *     MoondropOfficialImages.bestMatch，命不中就不置顶、不猜），其余保持目录原顺序；
 *   · 官方目录过滤后仍有 49 款（接口 105 条），一次拉 49 张图不现实，所以列表行只给
 *     **真正组合出来的行**懒加载官方小图（thumbnailPath，128px 采样 + LruCache），没下到时用占位图；
 *     上方那张「选中项大预览」仍只为当前选中项下载一张 [MoondropOfficialProduct.boxPath] 大图；
 *   · 官方图不一定三张齐全（很多机型没有左/右耳图），所以导入要求拿到 **BOX**，
 *     左/右耳图有就一起导入 —— 不像欢律那条要求三张齐全。
 */
package com.chenyc.hyperpods.ui.dialogs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
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
import java.util.Collections
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
    // 当前设备在目录里对应的那一款的 uuid（命不中就是 null，列表里不标、不置顶）
    var currentModelUuid by remember(show) { mutableStateOf<String?>(null) }
    var preview by remember(show) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(show) { mutableStateOf(false) }
    var importing by remember(show) { mutableStateOf(false) }
    // 失败后「重试」用：改一下就重新触发下面的 LaunchedEffect
    var reloadKey by remember(show) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(show, reloadKey) {
        if (!show) return@LaunchedEffect
        loading = true
        val (list, matched) = withContext(Dispatchers.IO) {
            val fetched = MoondropOfficialImages.fetchProducts()
            fetched to MoondropOfficialImages.bestMatch(fetched, currentName)
        }
        // 当前机型置顶，其余保持目录原顺序（fetchProducts 已按名称排序）：多数人就是要导当前这副，
        // 不该让他从几十行里翻。匹配用的是与自动导入同一个函数，命不中就不置顶。
        products = if (matched == null) list else listOf(matched) + list.filterNot { it.uuid == matched.uuid }
        currentModelUuid = matched?.uuid
        selected = matched ?: list.firstOrNull()
        loading = false
    }

    // 只为选中项下载一张预览图：几十款全下太重（每张 160~230 KB）
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
                        isCurrentModel = product.uuid == currentModelUuid,
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
    isCurrentModel: Boolean,
    onClick: () -> Unit,
) {
    val thumbnailPath = product.thumbnailPath()
    // 懒加载：只有真正被组合出来的行才走这条 LaunchedEffect（49 行里同时可见的就十几行），
    // 滚动离开再回来时靠 MoondropThumbnailCache 直接命中，不会重复下载。
    var thumbnail by remember(product.uuid) { mutableStateOf(MoondropThumbnailCache.cached(thumbnailPath)) }
    LaunchedEffect(product.uuid) {
        val path = thumbnailPath ?: return@LaunchedEffect
        if (thumbnail == null && !MoondropThumbnailCache.hadFailed(path)) {
            thumbnail = withContext(Dispatchers.IO) { MoondropThumbnailCache.load(path) }
        }
    }
    // 还没下到（下载中 / 官方没有这张 / 下载失败）就用占位图，与导入对话框原来的观感一致
    val thumbnailPainter = remember(thumbnail) {
        thumbnail?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    } ?: painterResource(R.drawable.img_box)

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
            painter = thumbnailPainter,
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
            if (isCurrentModel) {
                Text(
                    text = stringResource(R.string.official_import_current_model),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 行内官方小图的内存缓存。
 *
 * 为什么需要它：官方图是 100 KB ~ 1 MB 的 PNG，列表有 49 款 —— 既不能进列表就全下，
 * 也不能滚回来再下一次。这里按「图片路径」缓存解码后的位图，配合 `inSampleSize` 采样，
 * 每张只在 128px 档位上占几十 KB；按条数限制的 LruCache 天然把老条目挤出去。
 *
 * 不用可变的全局 Map 是因为行与行是并发的（各自在自己的协程里下载），LruCache 自己就同步了。
 */
private object MoondropThumbnailCache {
    private const val TAG = "HyperPods-MoondropDialog"

    /** 缩略图目标边长（px）：显示尺寸 48dp，留一倍余量，高密度屏也不发虚。 */
    private const val TARGET_PX = 128

    /** 49 款里同时可见的不过十几行，64 条足够覆盖整份列表来回滚动。 */
    private const val MAX_ENTRIES = 64

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    /** 下载 / 解码失败的路径：本次运行内不再重试，避免滚动时反复打同一个坏地址。 */
    private val failedPaths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** 同步取缓存（LruCache 内部已同步），命中就不必再进 IO。 */
    fun cached(path: String?): Bitmap? = path?.let { cache.get(it) }

    fun hadFailed(path: String): Boolean = path in failedPaths

    /** 阻塞 IO：调用方必须放在 Dispatchers.IO 里。 */
    fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        val bitmap = MoondropOfficialImages.downloadImage(path)?.let { decodeScaled(it) }
        if (bitmap != null) {
            cache.put(path, bitmap)
        } else {
            failedPaths.add(path)
            Log.w(TAG, "thumbnail unavailable: $path")
        }
        return bitmap
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        // 先只读尺寸（inJustDecodeBounds 不解码像素），算出 2 的幂采样率再真解码 ——
        // 直接 decodeByteArray 会把 1125×597 的整张图放进内存，几十行滚一遍就爆了
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    /** inSampleSize 只认 2 的幂：每边缩一半，直到再缩就小于目标边长。 */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= TARGET_PX && h / 2 >= TARGET_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
