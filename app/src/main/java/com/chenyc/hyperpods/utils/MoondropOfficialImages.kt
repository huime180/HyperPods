/*
 * HyperPods — 水月雨（MOONDROP）官方产品图来源
 *
 * 为什么需要它：耳机页右上角的「导入图片」原来只有欢律（OPPO）一条来源 ——
 * 那条路必须先装欢律、并在欢律里连过一次耳机，再由模块用 ROOT 去读它私有目录里
 * 下载好的机型图（`melody-model-download/control_<id>/res/image/img_<name>.png`）。
 *
 * 水月雨这边不必这么绕：官方 App（com.moondroplab.moondrop.moondrop_app）自己就是从公开图床
 * 取产品图的 —— 产品目录在
 *   https://cdn-service.moondroplab.tech/api/v1/products/all
 * 每款产品带若干图片字段，图片实体在
 *   https://cdn.moondroplab.tech/<字段值>       （字段值形如 product-banner-img/xxxx.png）
 * 两个地址都是公开的（实测 200，不需要 UA / Referer），所以本模块直接按官方目录取图：
 * 不需要 ROOT、不需要先装官方 App、也不需要它连过一次，而且目录里 105 款都能选。
 *
 * 字段与模块三个槽位的对应（拿布丁的官方图逐张验过像素）：
 *   · BOX   ← `bannerImgT`   1125×597，**85% 像素透明**的官方产品渲染（透明版 banner）；
 *             兜底 `bannerImgV2`（1388×640，0% 透明 —— 那是带底 banner）→ 方形图 → sellpic；
 *   · LEFT  ← `bannerImgLT`  1125×597，88% 透明，透明左耳；深色版 `bannerImgNightLT` 兜底；
 *   · RIGHT ← `bannerImgRT`  同上，右耳。
 *   ⚠ 左右耳按官方字段字面映射（LT→LEFT / RT→RIGHT）。欢律那条路是**反着**映射的
 *     （MelodyImageImportDialog 里 LEFT 取的是 rightPath），说明两家的命名视角不一定一致；
 *     这里没有真机可比对，若实机发现左右反了，改这两个取路径的函数即可。
 *
 * 线程：本文件全是阻塞 IO，调用方必须在 Dispatchers.IO 里调。
 */
package com.chenyc.hyperpods.utils

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 官方产品目录里的一条（只声明用得到的字段，其余由 `ignoreUnknownKeys` 忽略）。
 *
 * 每款有十几个图片字段，空字符串表示该款没有这张图（例如布丁没有 sellpic / squareBannerImg，
 * 但有 bannerImgT / LT / RT），所以取图一律走 [boxPath] 这类函数做兜底选择。
 */
@Serializable
data class MoondropOfficialProduct(
    val uuid: String = "",
    val model: String = "",
    val name: String = "",
    val type: String = "",
    val bannerImg: String = "",
    val bannerImgNight: String = "",
    val bannerImgT: String = "",
    val bannerImgNightT: String = "",
    val bannerImgLT: String = "",
    val bannerImgRT: String = "",
    val bannerImgNightLT: String = "",
    val bannerImgNightRT: String = "",
    val bannerImgV2: String = "",
    val squareBannerImg: String = "",
    val squareBannerImgNight: String = "",
    val sellpic: String = "",
) {
    /** 展示名：优先 `model`，为空时用 `name`。 */
    val displayName: String get() = model.ifBlank { name }

    /**
     * 机型图（BOX）路径。
     *
     * 首选 [bannerImgT]：官方图里它 85% 像素透明（1125×597 的透明产品渲染），而 [bannerImgV2]
     * 0% 透明（带底 banner）—— 卡片与通知都要把图叠在别的底色上，只有透明图能用，顺序不能反。
     */
    fun boxPath(): String? = listOf(
        bannerImgT, bannerImgNightT, bannerImgV2, squareBannerImg, sellpic, bannerImg, bannerImgNight,
    ).firstOrNull { it.isNotBlank() }

    /** 左耳图：官方 [bannerImgLT]（透明），深色版兜底。 */
    fun leftPath(): String? = listOf(bannerImgLT, bannerImgNightLT).firstOrNull { it.isNotBlank() }

    /** 右耳图：官方 [bannerImgRT]（透明），深色版兜底。 */
    fun rightPath(): String? = listOf(bannerImgRT, bannerImgNightRT).firstOrNull { it.isNotBlank() }
}

/** 目录接口的外层结构：`{"code":0,"desc":"success","data":[…]}`。 */
@Serializable
private data class MoondropProductsResponse(
    val code: Int = -1,
    val desc: String = "",
    val data: List<MoondropOfficialProduct> = emptyList(),
)

object MoondropOfficialImages {
    private const val TAG = "HyperPods-MoondropImg"
    private const val PRODUCTS_URL = "https://cdn-service.moondroplab.tech/api/v1/products/all"
    private const val FILE_BASE = "https://cdn.moondroplab.tech/"
    private const val TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉官方产品目录（105 款、约 120 KB）。
     *
     * 只保留蓝牙耳机（`type = "BT"`）且拿得到机型图的条目，按名称排序。
     * 任何失败都返回**空列表**：调用方据此显示「拉取失败 / 没有可导入的机型」，
     * 不要把异常带进 Compose（与模块 hook 侧的约定一致）。
     */
    fun fetchProducts(): List<MoondropOfficialProduct> = runCatching {
        val body = httpGet(PRODUCTS_URL) ?: return@runCatching emptyList()
        json.decodeFromString(MoondropProductsResponse.serializer(), body.decodeToString())
            .data
            .filter { it.type.equals("BT", ignoreCase = true) && it.boxPath() != null }
            .sortedBy { it.displayName.lowercase() }
    }.onFailure { Log.w(TAG, "fetchProducts failed", it) }.getOrDefault(emptyList())

    /**
     * 下载一张官方图。
     *
     * 校验 PNG 魔数：CDN 出错时返回的是 JSON 文本（如 `{"error":"Document not found"}`），
     * 那种响应绝不能当图片写进用户的图片库。
     */
    fun downloadImage(path: String): ByteArray? = runCatching {
        val bytes = httpGet(FILE_BASE + path) ?: return@runCatching null
        val isPng = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        if (isPng) {
            bytes
        } else {
            Log.w(TAG, "downloadImage: not a PNG ($path)")
            null
        }
    }.onFailure { Log.w(TAG, "downloadImage failed: $path", it) }.getOrNull()

    /** 极简 GET：失败 / 非 2xx 一律返回 null（调用方只看有没有数据）。 */
    private fun httpGet(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json,image/*")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.use { it.readBytes() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET failed: $url", t)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
