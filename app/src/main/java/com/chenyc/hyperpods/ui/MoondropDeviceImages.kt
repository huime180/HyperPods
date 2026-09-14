package com.chenyc.hyperpods.ui

import androidx.annotation.DrawableRes
import com.chenyc.hyperpods.pods.moondrop.MoondropModelRegistry

/** 设备图的三份；与 `utils/PodImageLoader` 的 PodImageResource 同一套语义。 */
enum class PodImagePart { BOX, LEFT, RIGHT }

/**
 * 按机型覆盖默认设备图的扩展点（返回 null = 用 `drawable-nodpi` 里的默认图）。
 *
 * 现在全部返回 null，因为**默认设备图本身就是水月雨那套**：
 * 来自 `roxyyn0304/MOONDROP-Pods`（本地克隆 `_refs/moondrop-pods`，tag 26.9.1 / HEAD f0691a2）
 * 的 `app/src/main/res/drawable-nodpi/img_box.png`(640x640)、`img_left/right.png`(597x597)，
 * 已覆盖本仓库的 `img_box/img_left/img_right`。所有水月雨型号共用这一套，没有哪个型号要单独换。
 *
 * ⚠ 需要知道的前提：那个仓库里**只有这一套**设备图，不是每机型一套 —— 它自身只面向 Pudding
 *   （其 `pods/DeviceCapabilities.kt:6`：「MOONDROP Pudding 实测支持自适应/抗风噪」）；
 *   全库 47 个 PNG 除这套外只有充电动画帧（charge_1..10 / common_1..10）与 docs 截图。
 *   官方 App（`_refs/_device/apks/moondrop_app.apk`）里 130 张 flutter 图只有图标与提示插图，
 *   同样没有机型渲染图。这两处都实地核对过（见 docs/UPSTREAM.md 同类记录的写法）。
 *
 * 将来某个型号要专属图：放三个 drawable（如 moondrop_pudding_box/left/right.png），
 * 在下面 when 里加一行即可，调用方（设备页英雄图）不用改。
 * 注意 release 开了资源压缩，静态 R 引用才会被保留 —— 所以这里走 when + R.drawable，
 * 不要改成按名字 getIdentifier 动态查。
 */
@DrawableRes
fun moondropDeviceImage(deviceName: String?, part: PodImagePart): Int? {
    val model = MoondropModelRegistry.match(deviceName) ?: return null
    return when (model.id) {
        // 目前所有型号都用水月雨默认设备图（见 KDoc），故无专属条目。
        else -> null
    }
}
