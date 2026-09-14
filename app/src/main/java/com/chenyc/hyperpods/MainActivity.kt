package com.chenyc.hyperpods

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.chenyc.hyperpods.config.ConfigManager
import com.chenyc.hyperpods.ui.App
import com.chenyc.hyperpods.ui.AppLocale
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction

class MainActivity : ComponentActivity() {
    /**
     * 「直接落到耳机页」的一次性请求计数。
     *
     * 放在 Activity 字段而不是 composition 里，是因为外部进程重定向过来的 intent 走的是
     * onNewIntent，那里够不到 composition；界面侧只按计数变化消费（与 RfcommDebugPage 的
     * clearRequest 同一套做法）。
     */
    private val showEarphonesRequest = mutableStateOf(0)

    /** 请求带入的设备地址，供界面消费时对齐既有状态（见 MainUI 的消费端）。 */
    private val requestedDeviceAddress = mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeShowUiIntent(intent)

        setContent {
            val prefs = remember { getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
            val themeMode = remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            val accentMode = remember { mutableStateOf(prefs.getInt("accent_mode", 0)) }
            val floatingBottomBar = remember { mutableStateOf(prefs.getBoolean("floating_bottom_bar", false)) }
            val blurBottomBar = remember { mutableStateOf(prefs.getBoolean("blur_bottom_bar", false)) }
            val appLanguage = remember { mutableStateOf(prefs.getInt("app_language", AppLocale.SYSTEM)) }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    prefs.edit().putInt("theme_mode", it).apply()
                },
                accentMode = accentMode,
                onAccentModeChange = {
                    accentMode.value = it
                    prefs.edit().putInt("accent_mode", it).apply()
                },
                floatingBottomBar = floatingBottomBar,
                onFloatingBottomBarChange = {
                    floatingBottomBar.value = it
                    prefs.edit().putBoolean("floating_bottom_bar", it).apply()
                },
                blurBottomBar = blurBottomBar,
                onBlurBottomBarChange = {
                    blurBottomBar.value = it
                    prefs.edit().putBoolean("blur_bottom_bar", it).apply()
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    prefs.edit().putInt("app_language", it).apply()
                },
                showEarphonesRequest = showEarphonesRequest,
                requestedDeviceAddress = requestedDeviceAddress,
            )
        }
    }

    /**
     * 已有实例被复用（singleTask + FLAG_ACTIVITY_CLEAR_TOP）时走这里。
     *
     * 不在这里再消费一次，新 intent 就进不到界面状态，用户第二次点设备只会把旧页面提到
     * 前台 —— 看上去还是「点了没反应」，所以冷启动与这条热路径必须都处理。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeShowUiIntent(intent)
    }

    /**
     * 消费 HeadsetPageRedirectHook 的显式组件启动（action = SHOW_UI）。
     *
     * 只认 SHOW_UI：本 Activity 同时是模块设置入口，别的启动方式一律按默认页处理。
     * 消费后立刻清掉 action 与 extras —— Activity 被系统重建（进程回收后恢复等）时会拿
     * 同一个 intent 再走一遍 onCreate，不清就等于把用户点的那一次重复播放一次。
     */
    private fun consumeShowUiIntent(intent: Intent?) {
        if (intent?.action != HyperPodsAction.SHOW_UI) return
        requestedDeviceAddress.value = intent.getStringExtra(HyperPodsAction.EXTRA_MAC)
        showEarphonesRequest.value++
        intent.action = null
        intent.removeExtra(HyperPodsAction.EXTRA_DEVICE)
        intent.removeExtra(HyperPodsAction.EXTRA_MAC)
        intent.removeExtra(HyperPodsAction.EXTRA_DEVICE_NAME)
    }
}
