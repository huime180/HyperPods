package com.chenyc.hyperpods.ui

import androidx.annotation.DrawableRes
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.moondrop.MoondropModelRegistry

/** 设备图的三份；与 `utils/PodImageLoader` 的 PodImageResource 同一套语义。 */
enum class PodImagePart { BOX, LEFT, RIGHT }

/**
 * 「对应机型」的设备图。
 *
 * ── 图是从哪来的 ────────────────────────────────────────────────────────────
 * `roxyyn0304/MOONDROP-Pods`（本地克隆 `_refs/moondrop-pods`，tag 26.9.1，
 * HEAD `f0691a2`）的 `app/src/main/res/drawable-nodpi/`：
 * `img_box.png` 640x640、`img_left.png` / `img_right.png` 597x597。
 * 本仓库把这套图按机型收纳为 `moondrop_pudding_box/left/right.png`。
 *
 * ⚠ 那个仓库里**只有这一套**设备图，不是「每机型一套」：它自身只面向 Pudding
 *   （其 `pods/DeviceCapabilities.kt:6` 写着「MOONDROP Pudding 实测支持自适应/抗风噪」）。
 *   所以这里做成**按机型挂图**的形式，而目前只有 Pudding 有专属图；
 *   其余型号返回 null，由调用方回落到通用 `img_box` / `img_left` / `img_right`。
 *   以后拿到别的机型图，只要往 when 里加一行 + 放三个 drawable 即可，调用方不用改。
 *
 * 为什么不直接覆盖通用图：`img_box/img_left/img_right` 同时被 OPPO 线使用，
 * 直接替换会把 OPPO 的设备图一起换掉。
 */
@DrawableRes
fun moondropDeviceImage(deviceName: String?, part: PodImagePart): Int? {
    val model = MoondropModelRegistry.match(deviceName) ?: return null
    return when (model.id) {
        "pudding" -> when (part) {
            PodImagePart.BOX -> R.drawable.moondrop_pudding_box
            PodImagePart.LEFT -> R.drawable.moondrop_pudding_left
            PodImagePart.RIGHT -> R.drawable.moondrop_pudding_right
        }
        else -> null
    }
}
