package com.chenyc.hyperpods.hook

import android.content.Context
import android.content.SharedPreferences
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsPrefsKey
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader

    /**
     * 远程设置（LSPosed getRemotePreferences）。
     *
     * 框架不支持远程首选项时**不是 null，而是回落到内存空实现 [EmptyPrefs]**：
     * 语义上等价于「所有设置都取默认值」，但读取点不必各写一遍空判断——
     * 上游 hook 代码大量直接 `prefs.getString(...)` / `NotificationSettings.fromPrefs(prefs)`，
     * 把签名改成可空会波及上千行，换来的只是把「取到默认值」变成「整段不执行」。
     * 回落时用 [prefsAvailable] 可以查出来，避免框架问题被静默吞掉。
     */
    var prefs: SharedPreferences = EmptyPrefs
        private set
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()

    abstract fun onHook()

    /** Releases resources retained by target-process objects before API 102 hot reload. */
    open fun onHotReloading() = Unit

    /**
     * 换目标 ClassLoader 再挂一次。
     *
     * 只有 SystemUI 那条线需要：miui.systemui.plugin 的类在插件 ClassLoader 里，
     * 与本进程默认 ClassLoader 不同，同一个 HookContext 要能用另一个 Loader 解析目标类。
     */
    fun bind(module: XposedModule, classLoader: ClassLoader, prefs: SharedPreferences?) {
        this.module = module
        this.appClassLoader = classLoader
        this.prefs = prefs ?: EmptyPrefs
    }

    /** 装载入口（HookEntry）设置远程设置；传 null 表示框架没给，回落到内存空实现。 */
    fun attachPrefs(store: SharedPreferences?) {
        prefs = store ?: EmptyPrefs
    }

    /** 远程设置是否真的可用（设置「改了没反应」时用来判断是不是框架没给远程首选项）。 */
    val prefsAvailable: Boolean get() = prefs !== EmptyPrefs

    fun hookIds(): Set<String> {
        return if (module.getApiVersion() >= XposedInterface.API_102) {
            hookHandles.mapNotNullTo(linkedSetOf()) { it.id }
        } else {
            emptySet()
        }
    }

    /** 模块总开关（HyperPodsPrefsKey.ENABLE，默认开启）。 */
    fun isEnabled(): Boolean = prefBoolean(HyperPodsPrefsKey.ENABLE, true)

    /**
     * 本进程当前可用的 Context —— 供「重启作用域」接收器注册使用。
     *
     * 默认 null：调用方会退回 ActivityThread.currentApplication() 并延迟重试。
     * 只有手上正好有一个确定可用 Context 的 hook 才需要覆盖它；实现必须容错，拿不到就返回 null。
     */
    open fun processContextOrNull(): Context? = null

    /** 容错读取布尔型首选项：类型不符或框架异常都回落到 [def]。 */
    fun prefBoolean(key: String, def: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, def) }.getOrDefault(def)

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun findClassOrNull(name: String): Class<*>? = runCatching { findClass(name) }.getOrNull()

    /**
     * 候选类名里第一个能在本进程 ClassLoader 里加载成功的；都没有返回 null。
     *
     * 框架内部类在不同 ROM 代数上会改名或挪包，hook 不该自己硬编码单一代的类名，
     * 而应把候选表交给这里，按顺序挑第一个真实存在的。
     */
    fun firstPresentClass(candidates: List<String>): String? {
        for (name in candidates) {
            if (findClassOrNull(name) != null) return name
        }
        return null
    }

    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }

    fun findMethodOrNull(className: String, methodName: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { findMethod(className, methodName, *parameterTypes) }.getOrNull()

    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findMethodByParamCountOrNull(className: String, methodName: String, paramCount: Int): Method? =
        runCatching { findMethodByParamCount(className, methodName, paramCount) }.getOrNull()

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> =
        findClass(className).declaredConstructors.first { it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findConstructorByParamCountOrNull(className: String, paramCount: Int): Constructor<*>? =
        runCatching { findConstructorByParamCount(className, paramCount) }.getOrNull()

    /**
     * 沿继承链找方法（框架内部类常把实现留在父类里，declaredMethods 找不到）。
     * [paramCount] 为 null 时只按名字匹配，取第一个命中的。
     */
    fun findAnyMethod(className: String, methodName: String, paramCount: Int? = null): Method {
        var cls: Class<*>? = findClass(className)
        while (cls != null && cls != Any::class.java) {
            val found = cls.declaredMethods.firstOrNull {
                it.name == methodName && (paramCount == null || it.parameterTypes.size == paramCount)
            }
            if (found != null) {
                found.isAccessible = true
                return found
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException("$className#$methodName/$paramCount")
    }

    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        registerHook(method, "after") { chain ->
            val result = chain.proceed()
            HookParam(chain, result).apply(block).result
        }
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        registerHook(method, "before") { chain ->
            val param = HookParam(chain, null).apply(block)
            if (param.hasResult) param.result else chain.proceed()
        }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        registerHook(constructor, "constructor-after") { chain ->
            chain.proceed().also { HookParam(chain, it).apply(block) }
        }
    }

    private fun registerHook(
        executable: Executable,
        phase: String,
        hooker: XposedInterface.Hooker
    ) {
        val id = "$phase:${executable.toGenericString()}"
        val builder = module.hook(executable)
        if (module.getApiVersion() >= XposedInterface.API_102) {
            builder.setId(id)
        }
        hookHandles += builder.intercept(hooker)
    }

    fun reloadRemotePrefs() {
        runCatching {
            prefs.javaClass.methods.firstOrNull {
                it.name == "reload" && it.parameterTypes.isEmpty()
            }?.invoke(prefs)
        }
    }
}

/**
 * 内存空实现：框架拿不到远程首选项时的回落目标。
 *
 * 读取一律返回调用方给的默认值，写入丢弃——与被注入进程里「设置还没同步过来」的观感一致，
 * 也不会把默认值错误地持久化成用户设置。
 */
private object EmptyPrefs : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()

    override fun getString(key: String?, defValue: String?): String? = defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = false

    override fun edit(): SharedPreferences.Editor = EmptyEditor

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

private object EmptyEditor : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

    override fun remove(key: String?): SharedPreferences.Editor = this

    override fun clear(): SharedPreferences.Editor = this

    override fun commit(): Boolean = true

    override fun apply() = Unit
}

class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args
    val instance: Any? = chain.thisObject
    var hasResult = false
        private set
    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }
}

fun getObjectField(instance: Any?, fieldName: String): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
    if (instance == null) return
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
            return
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
            it.isAccessible = true
            return it.invoke(instance, *args)
        }
        cls = cls.superclass
    }
    throw NoSuchMethodException(methodName)
}

fun callStaticMethod(cls: Class<*>?, methodName: String, vararg args: Any?): Any? {
    if (cls == null) return null
    var c: Class<*>? = cls
    while (c != null && c != Any::class.java) {
        c.declaredMethods.firstOrNull {
            it.name == methodName && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == args.size
        }?.let {
            it.isAccessible = true
            return it.invoke(null, *args)
        }
        c = c.superclass
    }
    throw NoSuchMethodException("${cls.name}#$methodName")
}

fun getStaticObjectField(cls: Class<*>?, fieldName: String): Any? {
    if (cls == null) return null
    val field = cls.getDeclaredField(fieldName)
    field.isAccessible = true
    return field.get(null)
}
