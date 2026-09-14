package com.chenyc.hyperpods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

@Serializable
data class TextParams(
    var text: String? = null,
    var textColor: Int = 0,
    var viewFlags: Int? = null,
    var turnAnim: Boolean? = null,
)
