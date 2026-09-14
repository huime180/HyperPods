package com.chenyc.hyperpods.hook

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.chenyc.hyperpods.BuildConfig
import com.chenyc.hyperpods.pods.PodCatalog
import com.chenyc.hyperpods.pods.moondrop.MoondropGaia
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.WeakHashMap

/**
 * 原生「耳机按键配置」页（MiuiHeadsetKeyConfigFragment）的水月雨手势接管。
 *
 * 为什么需要这一路：这一页读的是 AIDL `setCommonCommand(106, "", device)` 返回的 12 字符配置串，
 * 而本模块在设置进程里把 `IMiuiHeadsetService$Stub$Proxy.setCommonCommand` 的返回值统一改写成 "1"，
 * 厂商读回因此必然失败，回落到它自己构造器里的默认串 —— 页面显示的永远是一份与实际不符的状态。
 * 水月雨 TOUCHV2 的真实手势只存在于本模块自己的 5 字节配置里（见 [MoondropGaia.GestureConf]）。
 *
 * 做法：**只增补，不重画**。厂商的 PreferenceScreen / PreferenceCategory、6 个原有 preference 的
 * key、以及「左右成对」的排列顺序全部保留，只在两端各补一组：
 *   · 「单击」左 / 右（新 key single_left / single_right）
 *   · 「长按3秒」左 / 右（新 key long_press3_left / long_press3_right）
 * 厂商原有的 long_press_left_headset / long_press_right_headset 原地改名为「长按1秒」，
 * key 不变，厂商自己的字段绑定（mDropdownPrefLeft / pref_left）依旧认得它们。
 *
 * 这两行原本点进去是厂商的二级页（MiuiHeadsetPressKeyFragment，只有「语音助手 / 降噪切换」两项），
 * 现在这一次点击由我们接管：弹本模块动作表的单选弹窗，选中即下发 GESTURE_SELECT，不再进二级页。
 * 类没有换（initResource 里有 `as ValuePreference` 强转，换类会崩设置进程），
 * 变的只是「点下去之后发生什么」。
 *
 * 读写都走本模块的广播契约，不去猜厂商那条 106/105 配置串里其余下标的语义：
 *   · 读：改写 getRadioButtonConfig() 的返回值，把我们自己拼的 12 字符串交给厂商解析；
 *        页面打开时再向 com.android.bluetooth 要一次重放，GESTURE_CHANGED 回来后再刷新一遍。
 *   · 写：把 10 行的 OnPreferenceChangeListener 全换成自己的，用户改哪一格就发 GESTURE_SELECT。
 *   同侧「长按1秒 / 长按3秒」互斥由收件方 pods/moondrop/MoondropController 负责，这里不重复实现。
 *
 * 健壮性：所有厂商调用都在 runCatching 里，任何一步失败都只让「这一路失效并打日志」，
 * 绝不把异常抛回被注入进程；页面引用走 WeakHashMap，不长期持有。
 */
@SuppressLint("MissingPermission")
object NativeGestureKeyConfig {

    private const val TAG = "HyperPods-GestureKey"

    /** 厂商把 6 个手势 preference 都挂在 key 为 headsetKeyConfig 的 PreferenceCategory 下。 */
    private const val GROUP_KEY = "headsetKeyConfig"

    /** 水月雨协议栈（MoondropController）在蓝牙进程，手势命令与状态都从那边走。 */
    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    /** 厂商长按两行：key 沿用不换，厂商自己的 findPreference 绑定因此依旧认它们。 */
    private const val KEY_LONG_LEFT = "long_press_left_headset"
    private const val KEY_LONG_RIGHT = "long_press_right_headset"

    /**
     * 厂商默认配置串。
     *
     * 不是猜的：MiuiHeadsetKeyConfigFragment 的构造器里就把 PRESS_KEY_INIT 初始化成这个字面量
     * （反汇编确认）。它只在「厂商原值与本地缓存都不是合法 12 位十进制串」时兜底，
     * 用来给本模块没有证据的 6 个下标留一份原样透传的底座。
     */
    private const val VENDOR_DEFAULT_CONFIG = "000011101110"

    /** 本地缓存文件：页面打开时先用上一次读到的载荷渲染，不必等广播回来。 */
    private const val GESTURE_PREFS = "hyperpods_moondrop_gesture"
    private const val PREF_KEY_PAYLOAD = "payload"

    /** miuix 下拉的父类名：两种可能的实现类都继承它，用它判断「这一行是不是下拉」。 */
    private const val DROP_DOWN_BASE = "miuix.preference.DropDownPreference"

    /** 取厂商某一行的可用状态当样板（它们由厂商统一 setEnabled），我们新建的行跟着走。 */
    private const val VENDOR_SAMPLE_KEY = "left_double"

    /** 下拉控件类名，按优先级尝试；XML 用的是 settingslib 那个子类，父类是同 APK 里的 miuix 实现。 */
    private val DROP_DOWN_CLASSES = listOf(
        "com.android.settingslib.miuisettings.preference.miuix.DropDownPreference",
        "miuix.preference.DropDownPreference",
    )

    /**
     * 页面顺序：单击 → 双击 → 三击 → 长按1秒 → 长按3秒，每一档先左后右。
     *
     * 与厂商 XML 原有的「左右成对」顺序保持一致，所以原有 6 行的相对次序没有被动过；
     * 新建的 4 行只是插进对应的档位。order 是给「按 order 排序」的 PreferenceGroup 兜底的，
     * 与列表顺序同向，两种排序策略下结果一致。
     */
    private val ROWS: List<Row> = listOf(
        Row("single_left", MoondropGaia.GestureSlot.SINGLE_TAP, MoondropGaia.Ear.LEFT, 10, true, true),
        Row("single_right", MoondropGaia.GestureSlot.SINGLE_TAP, MoondropGaia.Ear.RIGHT, 20, true, true),
        Row("left_double", MoondropGaia.GestureSlot.DOUBLE_TAP, MoondropGaia.Ear.LEFT, 30, false, false),
        Row("right_double", MoondropGaia.GestureSlot.DOUBLE_TAP, MoondropGaia.Ear.RIGHT, 40, false, false),
        Row("left_triple", MoondropGaia.GestureSlot.TRIPLE_TAP, MoondropGaia.Ear.LEFT, 50, false, false),
        Row("right_triple", MoondropGaia.GestureSlot.TRIPLE_TAP, MoondropGaia.Ear.RIGHT, 60, false, false),
        Row(KEY_LONG_LEFT, MoondropGaia.GestureSlot.LONG_PRESS_1S, MoondropGaia.Ear.LEFT, 70, false, true),
        Row(KEY_LONG_RIGHT, MoondropGaia.GestureSlot.LONG_PRESS_1S, MoondropGaia.Ear.RIGHT, 80, false, true),
        Row("long_press3_left", MoondropGaia.GestureSlot.LONG_PRESS_3S, MoondropGaia.Ear.LEFT, 90, true, true),
        Row("long_press3_right", MoondropGaia.GestureSlot.LONG_PRESS_3S, MoondropGaia.Ear.RIGHT, 100, true, true),
    )

    private val ROW_BY_KEY: Map<String, Row> = ROWS.associateBy { it.key }

    private lateinit var host: HookContext

    private val installedFragments = WeakHashMap<Any, Boolean>()
    private var receiverRegistered = false
    private var context: Context? = null
    private var changeListener: Any? = null
    private var changeListenerClass: Class<*>? = null

    /** 最近一次从蓝牙进程收到的手势配置 5 字节；null = 还没读到过。 */
    @Volatile private var payloadBytes: ByteArray? = null

    /**
     * 一行手势的定义。
     *
     * @param createIfMissing true = 厂商布局里没有这一档（单击 / 长按3秒），由我们新建下拉。
     * @param rename true = 标题由我们改写（单击 / 长按1秒 / 长按3秒）；双击/三击保留厂商文案。
     */
    private class Row(
        val key: String,
        val slot: MoondropGaia.GestureSlot,
        val ear: MoondropGaia.Ear,
        val order: Int,
        val createIfMissing: Boolean,
        val rename: Boolean,
    )

    /**
     * 由 [SettingsHeadsetHook] 在 onHook() 里调用安装。
     *
     * 只挂几个点：读回串的构造、页面初始化完成、长按两行的点击接管、从别处返回、
     * 设备连接状态变化、页面销毁清理。
     * 每个钩子内部都 runCatching，装不上就只留下一条日志。
     */
    fun install(host: HookContext) {
        this.host = host
        runCatching { hookGetRadioButtonConfig() }
            .onFailure { Log.w(TAG, "hook getRadioButtonConfig skipped", it) }
        runCatching { hookInitResource() }
            .onFailure { Log.w(TAG, "hook initResource skipped", it) }
        runCatching { hookPreferenceTreeClick() }
            .onFailure { Log.w(TAG, "hook onPreferenceTreeClick skipped", it) }
        runCatching { hookHiddenChanged() }
            .onFailure { Log.w(TAG, "hook onHiddenChanged skipped", it) }
        runCatching { hookPreferenceEnable() }
            .onFailure { Log.w(TAG, "hook setPreferenceEnable skipped", it) }
        runCatching { hookFragmentDestroy() }
            .onFailure { Log.w(TAG, "hook onDestroyView skipped", it) }
    }

    /**
     * 这一页重新可见时（onHiddenChanged(false)）把长按行的显示值刷成真实动作。
     *
     * 原先的触发场景是「从厂商的长按二级页返回」—— 那个二级页现在已经被我们接管掉了，
     * 但 onHiddenChanged 依旧是「页面又可见了」的统一信号（从别的设置页返回也算），
     * 所以照旧挂在它后面：万一还有没发现的入口改过厂商那套语义，这里也会把它盖回来。
     */
    private fun hookHiddenChanged() {
        host.hookAfter(
            host.findMethod(FRAGMENT_CLASS, "onHiddenChanged", Boolean::class.javaPrimitiveType!!),
        ) {
            val fragment = instance
            runCatching { applyValues(fragment) }
                .onFailure { Log.w(TAG, "refresh after onHiddenChanged failed", it) }
        }
    }

    /**
     * 厂商只对自己那 6 行做 setEnabled（设备断开时整体变灰），我们新建的 4 行跟着一起变，
     * 免得出现「设备断了还有两行能点」的错位。
     */
    private fun hookPreferenceEnable() {
        host.hookAfter(
            host.findMethod(FRAGMENT_CLASS, "setPreferenceEnable", Boolean::class.javaPrimitiveType!!),
        ) {
            val fragment = instance ?: return@hookAfter
            val enabled = args.getOrNull(0) as? Boolean ?: return@hookAfter
            runCatching { setCreatedRowsEnabled(fragment, enabled) }
                .onFailure { Log.w(TAG, "mirror preference enable failed", it) }
        }
    }

    private fun setCreatedRowsEnabled(fragment: Any, enabled: Boolean) {
        for (row in ROWS) {
            if (!row.createIfMissing) continue
            val pref = callOn(fragment, "findPreference", row.key) ?: continue
            callOn(pref, "setEnabled", enabled)
        }
    }

    private fun hookGetRadioButtonConfig() {
        host.hookAfter(host.findMethod(FRAGMENT_CLASS, "getRadioButtonConfig")) {
            val fragment = instance
            runCatching {
                if (!isManagedMoondrop(fragment)) return@runCatching
                val conf = currentConf()
                if (conf == null) {
                    Log.d(TAG, "getRadioButtonConfig: 还没有读到过手势配置，保持厂商原值")
                    return@runCatching
                }
                val original = result as? String
                val forced = buildConfigString(fragment, conf, original)
                Log.d(TAG, "getRadioButtonConfig: ${original} -> $forced")
                result = forced
            }.onFailure { Log.w(TAG, "getRadioButtonConfig override failed", it) }
        }
    }

    private fun hookInitResource() {
        host.hookAfter(host.findMethod(FRAGMENT_CLASS, "initResource")) {
            val fragment = instance
            runCatching { onFragmentReady(fragment) }
                .onFailure { Log.w(TAG, "install gesture rows failed", it) }
        }
    }

    private fun hookFragmentDestroy() {
        host.hookAfter(host.findMethod(FRAGMENT_CLASS, "onDestroyView")) {
            // instance 是 Any?，而 WeakHashMap.remove 的形参非空，必须先收窄。
            val fragment = instance ?: return@hookAfter
            runCatching {
                installedFragments.remove(fragment)
                Log.d(TAG, "KeyConfig 页面销毁，手势行引用已释放")
            }.onFailure { Log.w(TAG, "fragment cleanup failed", it) }
        }
    }

    /**
     * 吃掉「长按」那两行的点击，改成我们自己的动作选择器。
     *
     * 为什么挂 onPreferenceTreeClick：这是厂商进二级页的**唯一**入口。反汇编确认
     * `gotoPressKeyFragment` 在整个 settings APK 里只有两个 invoke-direct，都在
     * MiuiHeadsetKeyConfigFragment.onPreferenceTreeClick(PreferenceScreen, Preference)
     * 里（分别传 "left" / "right"）；MiuiHeadsetPressKeyFragment 的 new-instance 也只有
     * 这一处。厂商声明的是 PreferenceFragmentCompat 那个已废弃的两参重载，settingslib 的
     * PreferenceFragment.onPreferenceTreeClick(Preference) 用 invoke-virtual 调它：
     * 我们返回 true 就不再落到厂商的左 / 右分支。
     *
     * HookContext.hookBefore 支持在回调里写 `result`，写了就不再执行原方法（见 HookContext.kt），
     * 所以不用换 click listener，也不用反射去动 Preference 的私有监听器。
     * 实参按「谁的 key 是我们的长按行」来认，不按位置认：位置不是契约。
     */
    private fun hookPreferenceTreeClick() {
        host.hookBefore(host.findMethodByParamCount(FRAGMENT_CLASS, "onPreferenceTreeClick", 2)) {
            val fragment = instance
            runCatching {
                val (pref, row) = matchLongPressRow(args) ?: return@runCatching
                if (!isManagedMoondrop(fragment)) return@runCatching
                // 先吃掉这次点击再弹窗：本模块的目的就是这一页不再出现厂商二级页，
                // 所以弹窗失败也只让「这一行点不动」并留下日志，不退回厂商那条路。
                result = true
                showActionDialog(fragment, pref, row)
            }.onFailure { Log.w(TAG, "接管长按行点击失败", it) }
        }
    }

    /**
     * 从 onPreferenceTreeClick 的实参里认出「长按」两行。
     *
     * 按 key 认而不是按位置认：厂商签名是 (PreferenceScreen, Preference)，
     * 但上层 settingslib 只保证「把被点的 preference 传下来」，位置不值得当契约。
     * PreferenceScreen 也有 getKey()，只是它的 key 不是我们这两行，会自然被跳过。
     */
    private fun matchLongPressRow(args: List<Any?>): Pair<Any, Row>? {
        for (arg in args) {
            val key = callOn(arg, "getKey") as? String ?: continue
            if (key != KEY_LONG_LEFT && key != KEY_LONG_RIGHT) continue
            ROW_BY_KEY[key]?.let { row -> return arg to row }
        }
        return null
    }

    /**
     * 我们自己的动作选择器（单选）。
     *
     * 厂商二级页只有「语音助手 / 降噪切换」两项，而本模块的动作表有 8 项 —— 用户要的是
     * 10 行统一用同一张表，所以这里直接列 [MoondropGaia.TouchActions.ALL]，文案走
     * [actionLabel]（中文 labelZh / 英文 labelEn）；推断项「（推断）」/「(inferred)」
     * 的标注本来就写在数据层的标签里，不在这里二次拼接。
     *
     * 用 android.app.AlertDialog 而不是 miuix 的对话框：不依赖目标进程的内部类，
     * 万一本 ROM 的主题不认这个弹窗，也只是这一路失效并打日志。
     * 对话框不缓存引用，页面销毁后不会留下泄漏的窗口。
     */
    private fun showActionDialog(fragment: Any?, pref: Any, row: Row) {
        // 只用 Fragment 自己的 Context（Activity 主题包装过的那个）：
        // 应用级 Context 弹 Dialog 拿不到窗口令牌，会直接抛异常。
        val ctx = callOn(fragment, "getContext") as? Context
        if (ctx == null) {
            Log.w(TAG, "拿不到 Fragment Context，动作选择器弹不出来")
            return
        }
        val currentId = currentConf()?.action(row.slot, row.ear) ?: MoondropGaia.TouchActions.NONE
        val checked = MoondropGaia.TouchActions.ALL
            .indexOfFirst { it.id == (currentId and MoondropGaia.TOUCH_ACTION_MASK) }
        if (checked < 0) {
            // 表外的取值（例如厂商写进去的 8..15）：照实不预选，用户仍能看到新的选项。
            Log.d(TAG, "长按行当前值 $currentId 不在动作表里，选择器不预选")
        }
        AlertDialog.Builder(ctx)
            .setTitle(rowTitle(row))
            .setSingleChoiceItems(actionEntries(), checked) { dialog, which ->
                runCatching {
                    val actionId = MoondropGaia.TouchActions.ALL.getOrNull(which)?.id
                    if (actionId == null) {
                        Log.w(TAG, "选择器下标 $which 越界，忽略")
                    } else if (sendGestureSelect(row, actionId)) {
                        // 立刻把这一行右侧的值刷成刚下发的动作；GESTURE_CHANGED 回来后会再整体刷一遍。
                        setRowText(pref, actionId)
                    }
                }.onFailure { Log.w(TAG, "动作选择失败", it) }
                // 单选弹窗不会自己关，选完（成功或失败）都收起来。
                runCatching { dialog.dismiss() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        Log.d(TAG, "动作选择器已弹出 key=${row.key} 当前值=$currentId 预选下标=$checked")
    }

    /**
     * initResource() 是厂商唯一一处「findPreference + 绑定监听 + 设置当前值」的地方，
     * 而且 onCreateView 与 onServiceConnected 都会调它，所以我们挂在它后面：
     * 无论厂商按哪条时序初始化，我们的条目与监听都在最后一次生效。
     */
    private fun onFragmentReady(fragment: Any?) {
        if (fragment == null) return
        registerReceiver(fragment)
        if (!isManagedMoondrop(fragment)) return
        val group = resolveGroup(fragment)
        if (group == null) {
            Log.w(TAG, "找不到手势分组，这一路失效")
            return
        }
        installRows(fragment, group)
        installedFragments[fragment] = true
        // setPreferenceEnable 在 initResource 内部已经跑过一次（那时我们的行还不存在），这里补一次。
        val sample = callOn(fragment, "findPreference", VENDOR_SAMPLE_KEY)
        (callOn(sample, "isEnabled") as? Boolean)?.let { setCreatedRowsEnabled(fragment, it) }
        requestGestureRefresh()
        applyValues(fragment)
    }

    private fun registerReceiver(fragment: Any) {
        val ctx = callOn(fragment, "getContext") as? Context
        if (ctx != null) context = ctx.applicationContext ?: ctx
        if (receiverRegistered) return
        val target = context ?: return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(HyperPodsAction.GESTURE_CHANGED)
                addAction(HyperPodsAction.PODS_CONNECTED)
            }
            target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            loadPayload()
            Log.d(TAG, "gesture receiver registered")
        }.onFailure { Log.w(TAG, "register gesture receiver failed", it) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // 先收窄再分派：when 的 subject 是 intent?.action，Kotlin 不会因此把 intent 智能转换成非空。
            if (intent == null) return
            when (intent.action) {
                HyperPodsAction.GESTURE_CHANGED -> {
                    val bytes = intent.getByteArrayExtra(HyperPodsAction.EXTRA_GESTURE_PAYLOAD)
                    val conf = MoondropGaia.parseGestureConf(bytes)
                    if (conf == null) {
                        Log.w(TAG, "GESTURE_CHANGED 载荷无法解析 size=${bytes?.size}")
                        return
                    }
                    payloadBytes = bytes
                    savePayload()
                    Log.d(TAG, "手势配置已更新 conf=$conf")
                    refreshVisibleFragments()
                }

                HyperPodsAction.PODS_CONNECTED -> requestGestureRefresh()
            }
        }
    }

    private fun refreshVisibleFragments() {
        installedFragments.keys.toList().forEach { fragment ->
            runCatching { applyValues(fragment) }
                .onFailure { Log.w(TAG, "refresh gesture rows failed", it) }
        }
    }

    /**
     * 页面打开时主动要一次重放。
     *
     * 权威状态在 `com.android.bluetooth` 的 MoondropController 里，所以真正会触发读回的是
     * REQUEST_CAPABILITIES（它走 HeadsetStateDispatcher 的命令表，会 refreshAll -> refreshGestures），
     * 随后对方主动 GESTURE_CHANGED 回来。REQUEST_GESTURE 按契约发给应用进程，属于「发了也无副作用」
     * 的一路：应用侧目前没有处理器，等以后补上了就自动生效。
     */
    private fun requestGestureRefresh() {
        val ctx = context ?: return
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.REQUEST_CAPABILITIES).apply {
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            ctx.sendBroadcast(Intent(HyperPodsAction.REQUEST_GESTURE).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d(TAG, "已请求蓝牙进程重放手势配置")
        }.onFailure { Log.w(TAG, "request gesture refresh failed", it) }
    }

    /** 逐行取到（缺的按需新建），配置条目与监听，然后按 [ROWS] 的顺序重排。 */
    private fun installRows(fragment: Any, group: Any) {
        val ctx = callOn(fragment, "getContext") as? Context
        val resolved = LinkedHashMap<String, Any>()
        for (row in ROWS) {
            val existing = callOn(fragment, "findPreference", row.key)
            val pref = existing ?: if (row.createIfMissing) createDropDown(ctx) else null
            if (pref == null) {
                Log.w(TAG, "preference ${row.key} 不存在且无法新建，这一行失效")
                continue
            }
            configureRow(pref, row)
            resolved[row.key] = pref
        }
        if (resolved.isEmpty()) return
        reorder(group, resolved)
    }

    private fun configureRow(pref: Any, row: Row) {
        if (row.createIfMissing) {
            // 先给 key 再写值：miuix 下拉的 setValue 会 persistString，没有 key 会打错误日志。
            callOn(pref, "setKey", row.key)
            callOn(pref, "setPersistent", false)
        }
        if (row.rename) callOn(pref, "setTitle", rowTitle(row))
        callOn(pref, "setOrder", row.order)
        if (isDropDown(pref)) {
            // 顺序不能反：miuix 的 setEntries 会把 mEntryValues 覆盖成 entries，
            // 所以必须 setEntries 在前、setEntryValues 在后。
            callOn(pref, "setEntries", actionEntries())
            callOn(pref, "setEntryValues", actionEntryValues())
            // 只在下拉上换监听：非下拉那两行没有下拉可选，它们的点击由上面的
            // onPreferenceTreeClick 接管（弹我们自己的选择器），不走这条写回路径。
            ensureChangeListener()?.let { callOn(pref, "setOnPreferenceChangeListener", it) }
        }
    }

    /** 按 [ROWS] 的顺序把 10 行重新挂回分组：厂商原有 6 行的相对次序不变，新行插进对应档位。 */
    private fun reorder(group: Any, resolved: Map<String, Any>) {
        val wanted = ROWS.mapNotNull { resolved[it.key] }
        val current = children(group).filter { child ->
            val key = callOn(child, "getKey") as? String
            key != null && ROW_BY_KEY.containsKey(key)
        }
        // 已经是同一批对象、同一个次序就不动，避免无谓的 remove/add 抖动。
        if (current.size == wanted.size && current.withIndex().all { (i, child) -> child === wanted[i] }) {
            Log.d(TAG, "手势行顺序已是最新，跳过重排")
            return
        }
        for (child in current) callOn(group, "removePreference", child)
        for (pref in wanted) callOn(group, "addPreference", pref)
        Log.d(TAG, "手势行已重排 keys=${wanted.map { callOn(it, "getKey") }}")
    }

    private fun children(group: Any): List<Any> {
        val count = (callOn(group, "getPreferenceCount") as? Int) ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            runCatching { callOn(group, "getPreference", index) }.getOrNull()
        }
    }

    private fun resolveGroup(fragment: Any): Any? {
        val screen = callOn(fragment, "getPreferenceScreen")
        if (screen == null) {
            Log.w(TAG, "getPreferenceScreen() 返回 null")
            return null
        }
        val category = callOn(screen, "findPreference", GROUP_KEY)
        if (category == null) {
            Log.w(TAG, "找不到 $GROUP_KEY 分组，改挂在 PreferenceScreen 上")
            return screen
        }
        return category
    }

    private fun createDropDown(ctx: Context?): Any? {
        if (ctx == null) {
            Log.w(TAG, "没有 Context，无法新建下拉")
            return null
        }
        for (className in DROP_DOWN_CLASSES) {
            val created = runCatching {
                val cls = host.findClass(className)
                val ctor = cls.getConstructor(Context::class.java)
                ctor.isAccessible = true
                ctor.newInstance(ctx)
            }.onFailure { Log.w(TAG, "新建 $className 失败", it) }.getOrNull()
            if (created != null) return created
        }
        return null
    }

    /** 把当前缓存的真实动作写回每一行。下拉按 entryValues 的下标选，非下拉只改显示值。 */
    private fun applyValues(fragment: Any?) {
        if (fragment == null) return
        val conf = currentConf() ?: return
        for (row in ROWS) {
            val pref = callOn(fragment, "findPreference", row.key) ?: continue
            val actionId = conf.action(row.slot, row.ear)
            if (isDropDown(pref)) setDropDownAction(pref, actionId) else setRowText(pref, actionId)
        }
    }

    private fun setDropDownAction(pref: Any, actionId: Int) {
        val masked = actionId and MoondropGaia.TOUCH_ACTION_MASK
        val index = MoondropGaia.TouchActions.ALL.indexOfFirst { it.id == masked }
        if (index < 0) {
            Log.w(TAG, "动作 id $masked 不在动作表里，下拉保持原值")
            return
        }
        // entryValues 就是动作 id 且顺序与 ALL 一致，所以下标 = 在动作表里的位置。
        callOn(pref, "setValueIndex", index)
    }

    /**
     * 非下拉的那两行（厂商在非 K77s 布局里把长按做成 ValuePreference：右侧显示当前动作）。
     * 它的类换不掉 —— initResource 会 `as ValuePreference`，换成下拉会直接 ClassCastException
     * 崩设置进程 —— 所以只把显示值改成真实动作。
     *
     * 文案跟着 Locale 走：已观测动作取 [actionLabel]（中文 labelZh / 英文 labelEn），
     * 只有动作表外的 id 才回落到数据层那句中文的 [MoondropGaia.TouchActions.matchOrUnknown]。
     */
    private fun setRowText(pref: Any, actionId: Int) {
        val action = MoondropGaia.TouchActions.byId(actionId)
        val label = if (action != null) {
            actionLabel(action)
        } else {
            MoondropGaia.TouchActions.matchOrUnknown(actionId)
        }
        callOn(pref, "setValue", label)
    }

    /**
     * 拼厂商那条 12 字符串。
     *
     * 语义由 initKeyConfig 的字节码坐实：hexToByteArray 逐字符 `Integer.parseInt(单字符)`，
     * 所以每个字符只能是 '0'..'9'；换算下来的下标是
     * 0=左双击、1=右双击、2=左三击、3=右三击、4=左长按、8=右长按。
     * 其余下标（5/6/7/9/10/11）本模块没有证据，一律从厂商原值透传，不猜、不写。
     */
    private fun buildConfigString(fragment: Any?, conf: MoondropGaia.GestureConf, original: String?): String {
        val vendorValue = runCatching { getObjectField(fragment, "PRESS_KEY_INIT") as? String }.getOrNull()
        val base = when {
            isPlainDecimal(original) -> original!!
            isPlainDecimal(vendorValue) -> vendorValue!!
            else -> VENDOR_DEFAULT_CONFIG
        }
        val out = StringBuilder(base)
        out[0] = digit(conf.action(MoondropGaia.GestureSlot.DOUBLE_TAP, MoondropGaia.Ear.LEFT))
        out[1] = digit(conf.action(MoondropGaia.GestureSlot.DOUBLE_TAP, MoondropGaia.Ear.RIGHT))
        out[2] = digit(conf.action(MoondropGaia.GestureSlot.TRIPLE_TAP, MoondropGaia.Ear.LEFT))
        out[3] = digit(conf.action(MoondropGaia.GestureSlot.TRIPLE_TAP, MoondropGaia.Ear.RIGHT))
        out[4] = digit(conf.action(MoondropGaia.GestureSlot.LONG_PRESS_1S, MoondropGaia.Ear.LEFT))
        out[8] = digit(conf.action(MoondropGaia.GestureSlot.LONG_PRESS_1S, MoondropGaia.Ear.RIGHT))
        return out.toString()
    }

    /** 厂商按十进制单字符解析，只有 0..9 是安全的；>=10 的动作 id 只能退回 0 并打日志。 */
    private fun digit(actionId: Int): Char {
        val value = actionId and MoondropGaia.TOUCH_ACTION_MASK
        if (value > 9) {
            Log.w(TAG, "动作 id $value 超出厂商配置串的单字符范围，按 0 透传")
            return '0'
        }
        return ('0' + value)
    }

    private fun isPlainDecimal(value: String?): Boolean =
        value != null && value.length == 12 && value.all { it in '0'..'9' }

    private fun currentConf(): MoondropGaia.GestureConf? = MoondropGaia.parseGestureConf(payloadBytes)

    private fun isDropDown(pref: Any): Boolean {
        var cls: Class<*>? = pref.javaClass
        while (cls != null) {
            if (cls.name == DROP_DOWN_BASE) return true
            cls = cls.superclass
        }
        return false
    }

    private fun actionEntries(): Array<CharSequence> =
        MoondropGaia.TouchActions.ALL.map { actionLabel(it) as CharSequence }.toTypedArray()

    private fun actionEntryValues(): Array<CharSequence> =
        MoondropGaia.TouchActions.ALL.map { it.id.toString() as CharSequence }.toTypedArray()

    private fun actionLabel(action: MoondropGaia.TouchAction): String =
        if (useChinese()) action.labelZh else action.labelEn

    /**
     * 换掉厂商原有的 OnPreferenceChangeListener：厂商那个监听会走 updateKeyConfig() ->
     * setCommonCommand(105, ...) -> saveCurrentKeyConfig(...) 这条写回路径，我们不需要它，
     * 而且它会把厂商那套语义写进页面状态。换成我们的之后，改哪一格就只发一条 GESTURE_SELECT。
     *
     * 监听器接口本身在目标进程里（androidx.preference 不是本模块的编译期依赖），
     * 所以用动态代理按目标类加载器实现它。
     */
    private fun ensureChangeListener(): Any? {
        changeListener?.let { return it }
        val iface = changeListenerClass ?: runCatching {
            host.findClass("androidx.preference.Preference\$OnPreferenceChangeListener")
        }.onFailure {
            Log.w(TAG, "找不到 OnPreferenceChangeListener，写入路径失效", it)
        }.getOrNull()?.also { changeListenerClass = it }
        if (iface == null) return null
        val created = Proxy.newProxyInstance(
            host.appClassLoader,
            arrayOf(iface),
            InvocationHandler { _, method, args ->
                // 显式标成 Any?：when 的分支混了 Boolean / Int / null，
                // 类型推断一旦落到交叉类型上，SAM 转换就会含糊。
                val value: Any? = when (method?.name) {
                    "onPreferenceChange" -> onRowChanged(args?.getOrNull(0), args?.getOrNull(1))
                    "equals" -> false
                    "hashCode" -> System.identityHashCode(this@NativeGestureKeyConfig)
                    else -> null
                }
                value
            },
        )
        changeListener = created
        return created
    }

    /** miuix 下拉把 entryValues 的字符串交给监听器（已由 DropDownPreference$1$1 的字节码坐实）。 */
    private fun onRowChanged(preference: Any?, newValue: Any?): Boolean {
        runCatching {
            val key = callOn(preference, "getKey") as? String
            if (key == null) {
                Log.w(TAG, "preference 变化事件缺少 key，忽略")
                return@runCatching
            }
            val row = ROW_BY_KEY[key]
            if (row == null) {
                Log.d(TAG, "非手势行 $key 的变化不接管")
                return@runCatching
            }
            val actionId = (newValue as? Number)?.toInt()
                ?: newValue?.toString()?.trim()?.toIntOrNull()
            if (actionId == null) {
                Log.w(TAG, "$key 的新值无法解析成动作 id: $newValue")
                return@runCatching
            }
            sendGestureSelect(row, actionId)
        }.onFailure { Log.w(TAG, "gesture write failed", it) }
        // 无论如何都返回 true：让下拉把选中项显示出来；写回失败只会「没生效」并已打日志。
        return true
    }

    /**
     * 把「手势槽位 + 耳朵 + 动作 id」下发给蓝牙进程的 MoondropController。
     *
     * setPackage 必须有：Android 14+ 会丢弃没指定包名的隐式广播（AGENTS.md 的约定）。
     * 同侧「长按1秒 / 长按3秒」的互斥由收件方做，这里只发这一条，不重复判断。
     *
     * @return true = 广播确实发出去了。调用方据此决定要不要把界面刷成新值：
     *   没发出去还刷，就成了「显示改了、其实没生效」。
     */
    private fun sendGestureSelect(row: Row, actionId: Int): Boolean {
        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "手势写回跳过：还没有拿到 Context")
            return false
        }
        val sent = runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.GESTURE_SELECT).apply {
                putExtra(HyperPodsAction.EXTRA_GESTURE_SLOT, row.slot.index)
                putExtra(HyperPodsAction.EXTRA_GESTURE_EAR, row.ear.ordinal)
                putExtra(HyperPodsAction.EXTRA_STATUS, actionId and MoondropGaia.TOUCH_ACTION_MASK)
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { Log.w(TAG, "gesture write failed", it) }.isSuccess
        if (sent) {
            Log.d(TAG, "GESTURE_SELECT slot=${row.slot.index} ear=${row.ear.ordinal} action=$actionId")
        }
        return sent
    }

    /**
     * 这一页是不是本模块接管的水月雨设备。
     *
     * 判定规则集中在 [PodCatalog]（AGENTS.md 明确要求 Hook 层不自带型号表）：
     * 认不出来就 fail-closed 放手，让厂商原样显示，绝不拿水月雨的动作表去糊一台别的耳机。
     */
    private fun isManagedMoondrop(fragment: Any?): Boolean {
        if (fragment == null) return false
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val name = runCatching { device?.name ?: device?.alias }.getOrNull()
        val address = runCatching { device?.address }.getOrNull()
        val model = runCatching { PodCatalog.moondropModelOf(name, address) }.getOrNull()
        if (model == null) {
            Log.d(TAG, "不是本模块认得的水月雨设备，手势接管跳过 name=$name address=$address")
            return false
        }
        return true
    }

    /**
     * 行的标题：跟厂商原有行同一形态 —— 中文「双击左耳机」、英文「Triple tap left earphone」。
     *
     * 数据层只给到「左耳 / Left」（[MoondropGaia.Ear]），厂商把「耳机 / earphone」写进了同一串
     * 文案里（rom 里 双击左耳机 / Triple tap left earphone 就是这个写法），
     * 所以这里按厂商写法补后缀：不改数据层的标签，也不新增字段。
     * 厂商原有的双击 / 三击行不由我们改名，形态与这里一致。
     */
    private fun rowTitle(row: Row): String = if (useChinese()) {
        "${row.slot.labelZh}${row.ear.labelZh}机"
    } else {
        "${row.slot.labelEn} ${row.ear.labelEn.lowercase(Locale.ROOT)} earphone"
    }

    private fun useChinese(): Boolean =
        runCatching { Locale.getDefault().language.startsWith("zh") }.getOrDefault(false)

    private fun savePayload() {
        val ctx = context ?: return
        val bytes = payloadBytes ?: return
        runCatching {
            ctx.getSharedPreferences(GESTURE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY_PAYLOAD, bytes.joinToString(",") { (it.toInt() and 0xFF).toString() })
                .apply()
        }.onFailure { Log.w(TAG, "缓存手势载荷失败", it) }
    }

    private fun loadPayload() {
        if (payloadBytes != null) return
        val ctx = context ?: return
        runCatching {
            val raw = ctx.getSharedPreferences(GESTURE_PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY_PAYLOAD, null) ?: return@runCatching
            val bytes = raw.split(",").mapNotNull { it.trim().toIntOrNull()?.toByte() }.toByteArray()
            if (bytes.size >= MoondropGaia.TOUCHV2_CONF_SIZE) {
                payloadBytes = bytes
                Log.d(TAG, "从缓存读到手势配置 conf=${MoondropGaia.parseGestureConf(bytes)}")
            }
        }.onFailure { Log.w(TAG, "读取手势载荷缓存失败", it) }
    }

    /**
     * 按名字 + 参数类型调用。
     *
     * 不用 HookContext.callMethod 是因为它只按参数个数匹配：`setEntries(int)` 与
     * `setEntries(CharSequence[])` 参数个数一样，会挑错重载。
     */
    private fun callOn(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull { candidate ->
                candidate.name == methodName &&
                    candidate.parameterTypes.size == args.size &&
                    candidate.parameterTypes.withIndex().all { (i, type) -> accepts(type, args[i]) }
            }
            if (method != null) {
                method.isAccessible = true
                return runCatching { method.invoke(target, *args) }
                    .onFailure { Log.w(TAG, "调用 $methodName 失败", it) }
                    .getOrNull()
            }
            cls = cls.superclass
        }
        Log.w(TAG, "${target.javaClass.name} 上没有 $methodName(${args.size} 参)")
        return null
    }

    private fun accepts(type: Class<*>, arg: Any?): Boolean = when {
        arg == null -> !type.isPrimitive
        type.isPrimitive -> acceptsPrimitive(type.name, arg)
        else -> type.isInstance(arg)
    }

    /** 装箱后的实参要跟基本类型形参对得上，否则会挑错重载。 */
    private fun acceptsPrimitive(typeName: String, arg: Any?): Boolean = when (typeName) {
        "int" -> arg is Int
        "boolean" -> arg is Boolean
        "long" -> arg is Long
        "float" -> arg is Float
        "double" -> arg is Double
        "short" -> arg is Short
        "byte" -> arg is Byte
        "char" -> arg is Char
        else -> false
    }

    private const val FRAGMENT_CLASS = "com.android.settings.bluetooth.MiuiHeadsetKeyConfigFragment"
}
