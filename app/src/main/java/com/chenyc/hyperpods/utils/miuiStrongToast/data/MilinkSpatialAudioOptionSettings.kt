package com.chenyc.hyperpods.utils.miuiStrongToast.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object MilinkSpatialAudioOptionSettings {
    fun resolveAndCache(
        context: Context?,
        localPrefsName: String,
        remotePrefs: SharedPreferences,
        reloadRemotePrefs: () -> Unit,
        intent: Intent? = null
    ): Boolean {
        val localPrefs = context?.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val cached = localPrefs
            ?.takeIf { it.contains(HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED) }
            ?.getBoolean(
                HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                HyperPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )

        val resolved = if (
            intent?.hasExtra(HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED) == true
        ) {
            intent.getBooleanExtra(
                HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                cached ?: HyperPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )
        } else {
            reloadRemotePrefs()
            val defaultValue = cached ?: HyperPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            val remoteValue = runCatching {
                remotePrefs.getBoolean(
                    HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                    defaultValue
                )
            }.getOrDefault(defaultValue)
            if (
                cached == false &&
                remoteValue == HyperPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            ) {
                false
            } else {
                remoteValue
            }
        }

        localPrefs?.edit()
            ?.putBoolean(HyperPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, resolved)
            ?.apply()
        return resolved
    }
}
