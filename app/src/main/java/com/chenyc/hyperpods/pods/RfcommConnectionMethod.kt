package com.chenyc.hyperpods.pods

enum class RfcommConnectionMethod(val preferenceValue: String) {
    UUID("uuid"),
    CHANNEL("channel");

    companion object {
        const val PREF_KEY = "rfcomm_connection_method"

        fun fromPreference(value: String?): RfcommConnectionMethod {
            return values().firstOrNull { it.preferenceValue == value } ?: UUID
        }

        fun fromSelectedIndex(index: Int): RfcommConnectionMethod {
            return values().getOrNull(index) ?: UUID
        }

        fun selectedIndexOf(method: RfcommConnectionMethod): Int {
            return values().indexOf(method).takeIf { it >= 0 } ?: 0
        }
    }
}
