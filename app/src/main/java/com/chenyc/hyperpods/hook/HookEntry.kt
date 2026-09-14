package com.chenyc.hyperpods.hook

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.chenyc.hyperpods.config.ConfigManager
import com.chenyc.hyperpods.hook.milink.MiLinkServiceHook

class HookEntry : XposedModule() {
    private val TAG = "HyperPods-HookEntry"
    private val configListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        when (param.packageName) {
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            // 原生设置页伪装（SettingsHeadsetHook）跑在 com.android.settings 进程。
            // 这一行自 b061de2「以 1812z/OppoPods v2.1.0 为基线重建」起被注释掉，导致
            // 整个设置面（设备页伪装、电量注入、原生 ANC 控件替换）成了死代码，
            // 而 scope.list / README 早就按「有这一路」写着 —— 两边对不上。
            // 不再 hook 系统设置：settings 已从 scope.list 移除，而且用户明确要求「保留系统设置原样」。
            // 需要时把这一行和 scope.list 里的 com.android.settings 一起恢复即可。
            // "com.android.settings" -> loadHook(SettingsHeadsetHook, param.defaultClassLoader, param.packageName)
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)
            "com.xiaomi.bluetooth" -> {
                loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        hook.prefs = getRemotePreferences("hyperpods_settings")
        Log.d(TAG, "loadHook package=$packageName hook=${hook.javaClass.simpleName}")
        ConfigManager.init(hook.prefs)
        val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == ConfigManager.PREF_KEY_CONFIG_JSON) {
                ConfigManager.refreshFromPrefs(sharedPreferences)
            }
        }
        configListeners.add(configListener)
        hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
        hook.onHook()
    }
}
