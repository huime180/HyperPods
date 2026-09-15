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
 * 「这台设备对应目录里哪一款」由 [MoondropOfficialImages.bestMatch] 回答（设备名与官方展示名
 * 归一化后互相包含，最长命中；命不中就返回 null 交给用户手选）。它只认名字，**不认品牌** ——
 * 「是不是水月雨设备」必须走 pods/ 的 PodCatalog，UI 层不得自带宽匹配。
 *
 * 线程：`fetchProducts` / `downloadImage` 是阻塞 IO，调用方必须在 Dispatchers.IO 里调；
 * `bestMatch` / [MoondropOfficialProduct.thumbnailPath] 是纯计算，没有线程要求。
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

    /**
     * 列表行内缩略图路径：方形图优先。
     *
     * 行内缩略图只有 48dp，[boxPath] 是 1125×597 的横向渲染图，塞进方框会被裁掉两侧；
     * 官方方形图（[squareBannerImg] 系）比例最合适，其次才是 banner 系，最后兜底回机型图。
     */
    fun thumbnailPath(): String? = listOf(
        squareBannerImg,
        squareBannerImgNight,
        bannerImgV2,
        bannerImgNight,
        boxPath(),
    ).firstOrNull { !it.isNullOrBlank() }

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

    /**
     * 「设备名被官方名包含」这一档允许的最短设备名长度。
     *
     * 反方向包含本身很弱（官方名越长越容易把短设备名装进去），设备名归一化后只剩一两个
     * 字符时（例如型号里只剩个 "2"）会把大半个目录都命中，所以短名直接不认。
     */
    private const val MIN_REVERSE_MATCH_LENGTH = 3

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉官方产品目录（接口实测 105 条、约 120 KB）。
     *
     * 只保留蓝牙耳机（`type = "BT"`）且拿得到机型图的条目，按名称排序 ——
     * 实测这层过滤后剩 49 款（目录里还有大量有线耳机 / 联名配件，它们没有 boxPath）。
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
     *
     * 路径里的字面空格必须先转义：官方图床确实存在 `Robin's Earphones_white.png`、
     * `猫咖_通用底_双耳 透明底.png` 这类文件名，而请求行里的裸空格是非法的请求目标 ——
     * 实测 CDN 在裸空格上直接断连（http=000），换成 %20 后返回正常 PNG（魔数校验通过）。
     * 只转义空格、其余字符（含中文）原样保留：中文原样请求实测可用，整段百分号编码
     * 会动到本来正常的路径，没有必要。
     */
    fun downloadImage(path: String): ByteArray? = runCatching {
        val bytes = httpGet(FILE_BASE + path.replace(" ", "%20")) ?: return@runCatching null
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

    /**
     * 展示名归一化：小写、去掉厂牌通用词、只留字母 / 数字 / 汉字。
     *
     * 为什么必须归一化：蓝牙设备名与官方目录名几乎不会逐字相同 —— 设备名常带厂牌前缀
     * （`MOONDROP PUDDING`）或型号后缀（`MD-TWS-056`），而官方名可能只写其中一段。
     * 去掉 `moondrop` / `水月雨` 是为了避免「所有官方名都因为共用前缀而互相包含」；
     * 设备名与官方名走同一套归一化，比较才有意义（所以这里不是只归一化设备名一侧）。
     *
     * 保留汉字：官方目录里中文名（如「布丁」）也要能对上中文设备名。
     */
    private fun normalizeName(value: String): String =
        value.lowercase()
            .replace("moondrop", "")
            .replace("水月雨", "")
            .filter { it.isLetterOrDigit() }

    /**
     * 按设备名在官方目录里找最合适的一条；**找不到就返回 null，绝不猜**。
     *
     * 匹配是「归一化后互相包含」，但分三档，优先级从高到低：
     *   1. 完全相等（`MOONDROP EDGE` 对官方 `EDGE`）；
     *   2. 官方名被设备名包含（设备名更长、更啰嗦）；
     *   3. 设备名被官方名包含（设备名只写了官方名的一段）。
     *
     * 为什么非要分档而不能只「取最长命中」：`edge2` 包含 `edge`，若只按最长命中，
     * 一台真正的 EDGE 会被官方目录里的 EDGE2 抢走。第 2 档是更可靠的锚定方向
     * （官方名整段出现在设备名里），所以在第 3 档之前先取；档内才是「最长命中」——
     * 第 2 档取最长（官方名越长越具体），第 3 档反过来取最短（多出来的字越少越接近）。
     */
    fun bestMatch(
        products: List<MoondropOfficialProduct>,
        deviceName: String,
    ): MoondropOfficialProduct? {
        val key = normalizeName(deviceName)
        if (key.isEmpty()) return null

        // 归一化只算一次：105 条 × 三段比较，避免在比较里反复做同样的字符串处理
        val named = products
            .map { it to normalizeName(it.displayName) }
            .filter { it.second.isNotEmpty() }

        named.firstOrNull { it.second == key }?.let { return it.first }
        named.filter { key.contains(it.second) }
            .maxByOrNull { it.second.length }
            ?.let { return it.first }
        if (key.length < MIN_REVERSE_MATCH_LENGTH) return null
        return named.filter { it.second.contains(key) }
            .minByOrNull { it.second.length }
            ?.first
    }

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
