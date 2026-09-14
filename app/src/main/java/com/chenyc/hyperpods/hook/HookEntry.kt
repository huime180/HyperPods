package com.chenyc.hyperpods.hook

import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsPrefsKey
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    private var activeHook: HookContext? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        loadHookForPackage(param.packageName, param.defaultClassLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        activeHook?.onHotReloading()
        detach()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val oldHooks = param.oldHookHandles
        val classLoader = oldHooks.firstOrNull()?.executable?.declaringClass?.classLoader
        if (classLoader == null) {
            Log.w(TAG, "Hot reload skipped: no target class loader is available")
            return
        }
        // HotReloadedParam exposes the process name (for example com.milink.service:ui),
        // whereas hook selection is keyed by the owning package. Without this normalization a
        // module update silently drops every hook in secondary processes.
        val packageName = param.processName.substringBefore(':')
        Log.d(TAG, "Hot reload package=$packageName process=${param.processName}")
        loadHookForPackage(packageName, classLoader)
        val activeIds = activeHook?.hookIds().orEmpty()
        oldHooks.filter { it.id !in activeIds }.forEach(HookHandle::unhook)
    }

    private fun loadHookForPackage(packageName: String, classLoader: ClassLoader) {
        val hook = when (packageName) {
            "com.android.bluetooth" -> HeadsetStateDispatcher
            "com.milink.service" -> MiLinkServiceHook
            "com.xiaomi.bluetooth" -> MiBluetoothToastHook
            "com.android.settings" -> SettingsHeadsetHook
            else -> return
        }
        loadHook(hook, classLoader)
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader) {
        // 装载本身绝不能把被注入进程带崩：某个进程上框架内部类改名/缺失都只该记日志。
        runCatching {
            hook.module = this
            hook.appClassLoader = classLoader
            // 框架不支持远程首选项时 attachPrefs(null) 会回落到内存空实现（等同全部默认值），
            // 因此这里不需要 lateinit，也不会因为一个开关引入崩溃点。
            hook.attachPrefs(runCatching { getRemotePreferences(PREFS_GROUP) }.getOrNull())
            if (!hook.prefsAvailable) {
                Log.w(TAG, "remote prefs unavailable in ${hook.javaClass.simpleName}; using defaults")
            }
            // 模块总开关：关掉后不接管任何设备（读取方全程容错，读不到视为开启）。
            if (!hook.isEnabled()) {
                Log.i(TAG, "${hook.javaClass.simpleName} disabled by ${HyperPodsPrefsKey.ENABLE}")
                return
            }
            hook.onHook()
            activeHook = hook
        }.onFailure {
            Log.w(TAG, "hook ${hook.javaClass.simpleName} skipped", it)
        }
    }

    private companion object {
        const val TAG = "HyperPods-HookEntry"

        /** 远程设置组名，与 LSPosed 远程首选项、应用侧 getSharedPreferences 同名。 */
        const val PREFS_GROUP = "hyperpods_settings"
    }
}
