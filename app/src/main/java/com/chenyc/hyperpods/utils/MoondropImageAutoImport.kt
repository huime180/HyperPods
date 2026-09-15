/*
 * HyperPods — 水月雨耳机的官方图自动导入（应用侧）
 *
 * 为什么需要它：图片有两条手动来源（欢律 / 水月雨官方目录），但两条都要用户自己找到右上角
 * 那个图标、翻到自己的机型、再确认一次。多数人连上耳机只是想看到图 —— 于是应用侧一旦看到
 * 「已连接的水月雨设备 + 这个地址还没有机型图」，就按型号从官方目录替他导一次
 * （取名规则见 MoondropOfficialImages.bestMatch）。
 *
 * 三条例外必须守住，这也是本文件存在的理由：
 *   1. **只碰水月雨设备**：品牌判定走 pods/ 的 PodCatalog（本模块双厂牌，UI 层不得靠名字宽匹配）；
 *   2. **不覆盖已有图**：机型图（BOX）已经存在（用户自己选的或之前导过的）就整支不动；
 *      少了某个槽位才补那个槽位 —— 官方目录里很多款根本没有左右耳图，不能因为缺图就整体放弃；
 *   3. **一次运行只试一次**：同一地址在本次进程里最多发起一次，失败只写日志，绝不循环重试。
 *
 * 为什么放在 utils/ 而不是 pods/：这里判的是「界面要不要替用户点一次导入」，不是协议或设备能力；
 * 判断标准仍然只有 pods/ 的品牌入口与官方目录的名字匹配，本文件不自带任何型号表。
 *
 * 线程：入口是 suspend，内部整体切到 Dispatchers.IO（拉目录、下图片、写文件都不在主线程）。
 * 这是应用进程的能力：被注入的蓝牙进程里既没有 Context 也没有图片库，在那里跑没有意义。
 */
package com.chenyc.hyperpods.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chenyc.hyperpods.config.EarphonePref
import com.chenyc.hyperpods.config.PodImagePrefs
import com.chenyc.hyperpods.config.PodImageResource
import com.chenyc.hyperpods.pods.PodBrand
import com.chenyc.hyperpods.pods.PodCatalog
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MoondropImageAutoImport {
    private const val TAG = "HyperPods-MoondropAutoImg"

    /**
     * 本次应用运行里已经试过的蓝牙地址（大写）。
     *
     * 用 @Volatile 的不可变集合而不是可变 MutableSet：每次改都整体替换引用，读到的要么是旧集合
     * 要么是新集合，不会读到「改了一半」的集合，@Volatile 顺带保证这个引用在别的线程可见。
     * 判重与写入仍要走 synchronized ——「先查再写」必须是一个原子动作，
     * 否则两个地址前后脚触发时可能都算成第一次。
     */
    @Volatile
    private var attemptedAddresses: Set<String> = emptySet()

    /** 标记「这个地址试过了」。已经试过返回 false，调用方据此立刻返回、不再发起第二次。 */
    private fun beginAttempt(address: String): Boolean {
        val key = address.uppercase()
        return synchronized(this) {
            if (key in attemptedAddresses) {
                false
            } else {
                attemptedAddresses = attemptedAddresses + key
                true
            }
        }
    }

    /**
     * 回滚「试过」标记。
     *
     * 协程被取消（界面重建 / 进程收尾）时这一次导入其实没做完，不回滚的话
     * 用户旋转一次屏幕就会白白吃掉这台设备本次运行的唯一一次机会。
     */
    private fun cancelAttempt(address: String) {
        val key = address.uppercase()
        synchronized(this) {
            attemptedAddresses = attemptedAddresses - key
        }
    }

    /**
     * 按需导入一次；真的写了图时返回更新后的耳机列表，其余情况返回 null。
     *
     * 返回值必须由调用方回灌到界面状态（存进 Compose 状态），否则图导进来了界面还是旧的。
     */
    suspend fun importIfNeeded(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        deviceName: String,
    ): List<EarphonePref>? {
        // 地址或名字还没到就先不动：连接广播分几条到达时，等齐了会再次触发本函数
        if (address.isBlank() || deviceName.isBlank()) return null
        if (!beginAttempt(address)) return null

        return try {
            withContext(Dispatchers.IO) {
                importBlocking(context, prefs, service, address, deviceName)
            }
        } catch (e: CancellationException) {
            cancelAttempt(address)
            throw e
        } catch (t: Throwable) {
            // 失败只记日志：图片是锦上添花，不能让用户看到弹窗，更不能进入重试循环
            Log.w(TAG, "auto import failed for $address", t)
            null
        }
    }

    /** 阻塞体：必须在 Dispatchers.IO 里跑（品牌兜底判定要读 assets，目录与图片要走网络）。 */
    private fun importBlocking(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        deviceName: String,
    ): List<EarphonePref>? {
        // 品牌判定只此一条路：水月雨型号档案是白名单匹配，OPPO 才是名字兜底；
        // 认不出来（null）就不当水月雨处理 —— 宁可没有图，也不给 OPPO 设备导水月雨的图
        val brand = runCatching { PodCatalog.brandOf(context, deviceName, address) }
            .onFailure { Log.w(TAG, "brandOf failed for '$deviceName'", it) }
            .getOrNull()
        if (brand != PodBrand.MOONDROP) return null

        // 机型图已经存在 = 用户自己选过或之前导过了，整支不动（这里只查机型图：左右耳图缺了
        // 不影响「有没有图」，下面按槽位补）
        val existing = PodImagePrefs.find(prefs, address)
        if (!existing?.boxImagePath.isNullOrBlank()) return null

        val products = MoondropOfficialImages.fetchProducts()
        if (products.isEmpty()) {
            Log.w(TAG, "official catalogue unavailable, skip auto import for $address")
            return null
        }
        val product = MoondropOfficialImages.bestMatch(products, deviceName)
        if (product == null) {
            // 匹配不上就不猜：保持没有图，让用户自己去官方目录里挑
            Log.i(TAG, "no official model matches '$deviceName', skip auto import")
            return null
        }

        val images = mutableMapOf<PodImageResource, ByteArray>()
        // 逐个槽位取：官方目录里很多款没有左右耳图，缺的那张就是没有，不影响其它槽位；
        // 已经有图的槽位跳过，用户手动指定过的图不覆盖
        collectImage(images, PodImageResource.BOX, product.boxPath(), existing?.boxImagePath)
        collectImage(images, PodImageResource.LEFT, product.leftPath(), existing?.leftImagePath)
        collectImage(images, PodImageResource.RIGHT, product.rightPath(), existing?.rightImagePath)
        if (images.isEmpty()) {
            Log.w(TAG, "official images all unavailable for ${product.displayName} ($address)")
            return null
        }

        Log.i(TAG, "auto import ${product.displayName} for $address: ${images.keys}")
        return PodImagePrefs.saveImageBytes(context, prefs, service, address, deviceName, images)
    }

    /** 取一个槽位的图：已经有图、官方没有这张、或下载失败（CDN 返回 JSON 时 downloadImage 已是 null）都跳过。 */
    private fun collectImage(
        target: MutableMap<PodImageResource, ByteArray>,
        resource: PodImageResource,
        path: String?,
        currentPath: String?,
    ) {
        if (!currentPath.isNullOrBlank() || path.isNullOrBlank()) return
        MoondropOfficialImages.downloadImage(path)
            ?.takeIf { it.isNotEmpty() }
            ?.let { target[resource] = it }
    }
}
