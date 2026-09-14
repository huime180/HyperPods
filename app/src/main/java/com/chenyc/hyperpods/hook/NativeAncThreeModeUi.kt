package com.chenyc.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.chenyc.hyperpods.pods.PodCatalog
import com.chenyc.hyperpods.utils.miuiStrongToast.data.HyperPodsAction
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap

/**
 * 原生「蓝牙设置里的耳机设备页」（MiuiHeadsetFragment）内的水月雨 ANC 三档控件。
 *
 * 做法照 `_refs/pudding-docs/PUDDING_ADAPTATION.md` 的口径，不自己另设计：**不删除系统
 * View，也不改系统滑杆的 max**。系统的四档滑杆宿主 `ancAdjust`（里面是自绘的
 * MiuiHeadsetAncAdjustView）与四档标签行 `ancAdjustText` 原样保留，只把它们设为 GONE；
 * 再用 `android.view` 自绘一个三段控件，插进**同一个父容器 `anclayout` 的同一位置**，
 * 视觉上正好顶掉原来那一段。三段的取值与顺序照模块侧唯一语义来源
 * `ui/components/AncSwitch.kt` 的 ANC_SUB_ORDER：自适应 / 抗风噪 / 基本（协议文档写
 * 「基础」，而模块自己的字符串资源 `anc_normal_title` 是「基本」，这里以模块为准）。
 *
 * 为什么不动 max、也不删 View：MiuiHeadsetAncAdjustView 是自绘控件，档位、间距、文案都在
 * 它自己的绘制逻辑里，改 max 只会让它继续按自己那套画。删 View 更不行 —— 厂商的刷新路径
 * 会自己把 `ancAdjustText` 的四个子 TextView 找回来逐个更新，删掉等于给它埋一层崩溃。
 * 保留对象 + 隐藏，是既不与厂商逻辑打架、又不会被它反噬的做法。
 *
 * 读写都走**已经存在**的跨进程契约，不新增常量、不改协议：读 `ANC_CHANGED`
 * （extra `status` = 档位下标、`anc_ids` = 档位 id 列表），写 `ANC_SELECT`
 * （extra `status` = 下标）。下标到设备码的换算在蓝牙进程的 MoondropController 里做，
 * 这里一个字都不碰。
 *
 * 时序：原生页要等 onCreateView / onServiceConnected 之后才可能有视图，而 ANC_CHANGED
 * 是异步来的。所以控件必须能在「还不知道当前档位」时安全存在：档位表为空时三段全部
 * 置灰且不选中（等待态），拿到广播后再刷新，绝不因为状态缺失而崩或误发命令。
 * 页面打开时主动请蓝牙进程重放一次状态（UI_INIT），把等待时间压到最短。
 *
 * 置灰规则（用户要求）：当前处于「通透」或「关闭」时控件不可用（置灰）；处于降噪族
 * （自适应 / 抗风噪 / 基本）时才可点。族的判定用 ANC_NC_FAMILY，与 ui 层同一份语义。
 *
 * 健壮性：每个 hook、每个回调体、每次反射调用都整段 runCatching —— libxposed 的 hook
 * chain 里抛异常会直接崩 com.android.settings。任何一步失败只留一条日志并让「这一路失效」，
 * 原生页保持它自己的样子。视图引用一律弱引用，页面销毁即释放，不长期持有。
 */
@SuppressLint("MissingPermission")
object NativeAncThreeModeUi {

    private const val TAG = "HyperPods-AncUi"

    private const val FRAGMENT_CLASS = "com.android.settings.bluetooth.MiuiHeadsetFragment"

    /** 原生页所在进程；里面的资源 id 按名字从它自己的资源表查。 */
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /** 水月雨协议栈（MoondropController）在蓝牙进程，读写都发给它。 */
    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    /** 原生页里用到的三个 id 名（真机 dump 核实：容器 / 四档滑杆宿主 / 四档标签行）。 */
    private const val ID_ANC_LAYOUT = "anclayout"
    private const val ID_ANC_ADJUST = "ancAdjust"
    private const val ID_ANC_ADJUST_TEXT = "ancAdjustText"

    /** 三段取值与**展示顺序**：与 ui/components/AncSwitch.kt 的 ANC_SUB_ORDER 一致。 */
    private val SEGMENT_IDS = listOf("adaptive", "anti_wind", "anc")

    /** 降噪族：当前档位落在这一族里控件才可点（通透 / 关闭都要置灰）。 */
    private val ANC_NC_FAMILY = listOf("anc", "anti_wind", "adaptive")

    /** 插进去的控件打一个 tag：重复安装时靠它认出自己那一个，不重复插。 */
    private const val VIEW_TAG = "hyperpods_anc_three_mode"

    /**
     * 量不到原生两行高度时的兜底总高（dp）。
     *
     * 真机（3200x2136 / density 440）实测：滑杆行 66px + 标签行 49px = 115px ≈ 42dp，
     * 所以即使一行高度都拿不到，插进去的控件也不会塌成一条线。
     */
    private const val FALLBACK_HEIGHT_DP = 42

    private lateinit var host: HookContext

    private var context: Context? = null
    private var receiverRegistered = false

    /** 当前设备的档位表与当前档位下标；空表 / -1 都表示「还不知道」。 */
    @Volatile private var ancIds: List<String> = emptyList()
    @Volatile private var ancIndex = -1

    /** fragment -> 我们插进去的控件。弱引用：页面销毁重建时不能把视图留在设置进程里。 */
    private val controls = WeakHashMap<Any, WeakReference<SegmentedAncView>>()

    /** 由 [SettingsHeadsetHook] 在 onHook() 里调用安装。 */
    fun install(host: HookContext) {
        this.host = host
        hookFragmentViewReady()
        hookAncUiRefresh()
        hookFragmentDestroy()
    }

    /**
     * 页面视图就绪的两个时机都挂：onCreateView 与 onServiceConnected 谁先谁后由厂商决定，
     * 两边都调同一个幂等的安装过程（已经装过就只刷新状态）。
     */
    private fun hookFragmentViewReady() {
        runCatching {
            host.hookAfter(host.findMethodByParamCount(FRAGMENT_CLASS, "onCreateView", 3)) {
                val fragment = instance ?: return@hookAfter
                runCatching { onFragmentViewReady(fragment) }
                    .onFailure { Log.w(TAG, "内嵌三档 ANC 控件安装失败，原生页保持原样", it) }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            host.hookAfter(host.findMethodByParamCount(FRAGMENT_CLASS, "onServiceConnected", 0)) {
                val fragment = instance ?: return@hookAfter
                runCatching { onFragmentViewReady(fragment) }
                    .onFailure { Log.w(TAG, "内嵌三档 ANC 控件安装失败，原生页保持原样", it) }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onServiceConnected skipped", it) }
    }

    /**
     * 厂商刷新 ANC UI 之后再压一次隐藏并刷新我们的控件。
     *
     * 真机观察到的行为是「关闭 / 通透 时原生那两行仍然可见，只是置灰」，所以大概率没有人
     * 去动它们的 visibility；但厂商刷新路径里确实会 findViewById 回这两行（ancAdjustText
     * 的四个子 TextView 就是它逐个更新的），一旦哪天它把行显出来，用户就会看到
     * 「我们的三档 + 系统四档」叠着两套。挂在 updateAncUi 之后重压一次，代价极小。
     */
    private fun hookAncUiRefresh() {
        runCatching {
            host.hookAfter(host.findMethodByParamCount(FRAGMENT_CLASS, "updateAncUi", 2)) {
                runCatching {
                    reapplyHiding()
                    refreshControls()
                }.onFailure { Log.w(TAG, "刷新内嵌 ANC 控件失败", it) }
            }
        }.onFailure { Log.d(TAG, "没有 updateAncUi（2 参）可挂，跳过重压隐藏，不影响主路径") }
    }

    private fun hookFragmentDestroy() {
        runCatching {
            host.hookAfter(host.findMethodByParamCount(FRAGMENT_CLASS, "onDestroyView", 0)) {
                val fragment = instance ?: return@hookAfter
                runCatching {
                    controls.remove(fragment)
                    Log.d(TAG, "原生耳机页销毁，内嵌 ANC 控件引用已释放")
                }.onFailure { Log.w(TAG, "清理内嵌 ANC 控件引用失败", it) }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onDestroyView skipped", it) }
    }

    /** 认设备 -> 注册广播 -> 找到原生视图 -> 插入控件。任何一步拿不到就保持原生页原样。 */
    private fun onFragmentViewReady(fragment: Any) {
        if (!isManagedMoondrop(fragment)) return
        registerReceiver(fragment)
        val root = runCatching { callMethod(fragment, "getView") as? View }.getOrNull()
        if (root == null) {
            Log.d(TAG, "fragment.getView() 还是 null，视图没建好，等下一次回调解")
            return
        }
        val ctx = root.context ?: return
        val layout = findView(root, ctx, ID_ANC_LAYOUT)
        if (layout == null) {
            Log.w(TAG, "原生页里没有 $ID_ANC_LAYOUT，这一页不内嵌，保持原样")
            return
        }
        val slider = findView(root, ctx, ID_ANC_ADJUST)
        if (slider == null) {
            Log.w(TAG, "原生页里没有 $ID_ANC_ADJUST，这一页不内嵌，保持原样")
            return
        }
        val labels = findView(root, ctx, ID_ANC_ADJUST_TEXT)
        val parent = slider.parent as? LinearLayout
        if (parent == null || parent !== layout) {
            // 只往 anclayout 这个已知的 LinearLayout 里插：它是纵向排列，插进去天然就在
            // 原滑杆的位置；父容器类型变了就宁可不动，免得把原生布局插坏。
            Log.w(TAG, "$ID_ANC_ADJUST 的父容器不是 $ID_ANC_LAYOUT（LinearLayout），这一页不内嵌")
            return
        }
        val control = installControl(parent, slider, labels)
        controls[fragment] = WeakReference(control)
        refreshControls()
        requestStateReplay("fragment-view-ready")
    }

    /**
     * 把三档控件插进原生滑杆原来的位置。
     *
     * 插进去用的布局参数是**从原滑杆复制**的：横向边距、宽度、权重全部继承，所以「相同区域」
     * 不是靠猜像素，而是直接复用厂商自己的布局参数，只改高度。
     */
    private fun installControl(parent: LinearLayout, slider: View, labels: View?): SegmentedAncView {
        existingControl(parent)?.let {
            Log.d(TAG, "三档 ANC 控件已经装过，只刷新状态")
            return it
        }
        val ctx = parent.context ?: slider.context
        val control = SegmentedAncView(ctx, SEGMENT_IDS, SEGMENT_IDS.map { segmentLabel(it) }) { id ->
            onSegmentSelected(id)
        }
        control.tag = VIEW_TAG
        val index = parent.indexOfChild(slider).takeIf { it >= 0 } ?: parent.childCount
        parent.addView(control, index, layoutParamsFor(slider, labels, ctx))
        Log.d(TAG, "三档 ANC 控件已插入 ${parent.javaClass.simpleName} index=$index")
        hideNativeRows(parent, control, slider, labels)
        return control
    }

    /**
     * 隐藏原生两行（只隐藏、不删除），并把控件高度对齐成两行之和。
     *
     * 为什么要等一帧：视图刚创建时还没量过（height = 0），这时就把两行设成 GONE，它们的
     * 真实高度就永远量不到了。所以只有「已经量过」（页面第二次进来、或厂商刷新后重装）
     * 才立刻隐藏；否则挂一次 OnLayoutChangeListener，在第一帧布局完成后按真实高度定稿再隐藏。
     * 首帧布局没等到也不会坏：高度先按 layoutParams 里声明的高度，再退到兜底 dp 值。
     */
    private fun hideNativeRows(parent: LinearLayout, control: SegmentedAncView, slider: View, labels: View?) {
        val measured = rowsHeight(slider, labels)
        if (measured > 0) {
            setControlHeight(control, measured)
            applyHiding(slider, labels)
            return
        }
        slider.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int) {
                runCatching {
                    v.removeOnLayoutChangeListener(this)
                    if (control.parent !== parent) return@runCatching
                    val height = rowsHeight(slider, labels)
                    if (height > 0) setControlHeight(control, height)
                    applyHiding(slider, labels)
                    Log.d(TAG, "首帧布局后隐藏原生档位控件 height=$height")
                }.onFailure { Log.w(TAG, "首帧布局后隐藏原生档位控件失败", it) }
            }
        })
    }

    /**
     * 再压一次隐藏（只隐藏，不删除：系统对象留着，厂商刷新逻辑依旧找得到它们）。
     *
     * 控件与原生两行是同一个父容器的兄弟，所以从控件往上拿父容器就能重新查到它们，
     * 不需要额外长期持有 View 引用。
     */
    private fun reapplyHiding() {
        controls.values.toList().forEach { ref ->
            runCatching {
                val control = ref.get() ?: return@runCatching
                val parent = control.parent as? LinearLayout ?: return@runCatching
                val ctx = control.context ?: return@runCatching
                hideOnly(findView(parent, ctx, ID_ANC_ADJUST))
                hideOnly(findView(parent, ctx, ID_ANC_ADJUST_TEXT))
            }.onFailure { Log.w(TAG, "重新隐藏原生档位控件失败", it) }
        }
    }

    /** 把最新档位写进所有活着的控件；档位表为空时是「等待态」（全灰、不选中）。 */
    private fun refreshControls() {
        val currentId = currentAncId()
        val enabled = currentId != null && ANC_NC_FAMILY.contains(currentId)
        controls.values.toList().forEach { ref ->
            runCatching { ref.get()?.bind(currentId, ancIds, enabled) }
                .onFailure { Log.w(TAG, "刷新内嵌 ANC 控件失败", it) }
        }
    }

    /**
     * 注册状态广播接收器。
     *
     * 接收器注册在设置进程（页面所在的进程），只收水月雨这一路的两个 action：
     * ANC_CHANGED（档位回灌）与 PODS_CONNECTED（连接后补一次状态请求）。
     * setPackage 只管发送方，这里收的是发给 com.android.settings 的显式广播，
     * 所以必须 RECEIVER_EXPORTED，否则跨进程收不到（与仓库既有做法一致）。
     */
    private fun registerReceiver(fragment: Any) {
        val ctx = runCatching { callMethod(fragment, "getContext") as? Context }.getOrNull()
        if (ctx != null) context = ctx.applicationContext ?: ctx
        if (receiverRegistered) return
        val target = context ?: return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(HyperPodsAction.ANC_CHANGED)
                addAction(HyperPodsAction.PODS_CONNECTED)
            }
            target.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            Log.d(TAG, "水月雨 ANC 广播接收器已注册")
        }.onFailure { Log.w(TAG, "注册 ANC 广播接收器失败", it) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            runCatching {
                if (intent == null) return@runCatching
                when (intent.action) {
                    HyperPodsAction.ANC_CHANGED -> {
                        val ids = intent.getStringArrayListExtra(HyperPodsAction.EXTRA_ANC_IDS)
                        if (ids != null && ids.isNotEmpty()) ancIds = ids
                        if (intent.hasExtra(HyperPodsAction.EXTRA_STATUS)) {
                            ancIndex = intent.getIntExtra(HyperPodsAction.EXTRA_STATUS, -1)
                        }
                        Log.d(TAG, "ANC_CHANGED ids=$ancIds index=$ancIndex current=${currentAncId()}")
                        reapplyHiding()
                        refreshControls()
                    }

                    HyperPodsAction.PODS_CONNECTED -> requestStateReplay("pods-connected")
                    else -> Unit
                }
            }.onFailure { Log.w(TAG, "处理 ANC 广播失败", it) }
        }
    }

    /**
     * 请蓝牙进程重放一次状态。
     *
     * 用 UI_INIT（不是 ACTION_PODS_UI_INIT）：它走 HeadsetStateDispatcher 的水月雨命令表，
     * 由 MoondropController 无条件重放缓存并 refreshAll，随后 ANC_CHANGED 才会广播到本进程。
     * 状态发布是「变化触发」的，页面后开时不做这一步就只能干等下一次状态变化。
     */
    private fun requestStateReplay(reason: String) {
        val ctx = context ?: return
        runCatching {
            ctx.sendBroadcast(Intent(HyperPodsAction.UI_INIT).apply {
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d(TAG, "已请蓝牙进程重放一次 ANC 状态 reason=$reason")
        }.onFailure { Log.w(TAG, "请求 ANC 状态重放失败", it) }
    }

    /**
     * 用户点了三档里的一段。
     *
     * 只发 `ANC_SELECT` + extra status = 下标，换算与下发由蓝牙进程的 MoondropController
     * 负责。发出去之后先按乐观值把控件点亮，等 `ANC_CHANGED` 回来再校正 —— 设备回包有几百
     * 毫秒，不先亮一下用户会以为没点上；真没生效也会被随后的广播盖回来。
     */
    private fun onSegmentSelected(id: String) {
        runCatching {
            val index = ancIds.indexOf(id)
            if (index < 0) {
                Log.w(TAG, "档位 $id 不在当前档位表 $ancIds 里，这次点选忽略")
                return@runCatching
            }
            val ctx = context
            if (ctx == null) {
                Log.w(TAG, "还没有拿到 Context，这次点选忽略 id=$id")
                return@runCatching
            }
            ctx.sendBroadcast(Intent(HyperPodsAction.ANC_SELECT).apply {
                putExtra(HyperPodsAction.EXTRA_STATUS, index)
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d(TAG, "已下发 ANC_SELECT id=$id index=$index")
            ancIndex = index
            refreshControls()
        }.onFailure { Log.w(TAG, "下发 ANC_SELECT 失败", it) }
    }

    /**
     * 这一页是不是本模块接管的水月雨设备。
     *
     * 判定规则集中在 [PodCatalog]（AGENTS.md 要求 Hook 层不自带型号表）。这里只认水月雨
     * 白名单，**不**用 isOppoName 那种宽匹配：OPPO 设备的原生页是另一条路（重定向/OPPO 那套
     * hook），我们的三档控件绝不能插到它上面去。认不出来就 fail-closed 放手。
     */
    private fun isManagedMoondrop(fragment: Any): Boolean {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val name = runCatching { device?.name ?: device?.alias }.getOrNull()
        val address = runCatching { device?.address }.getOrNull()
        val model = runCatching { PodCatalog.moondropModelOf(name, address) }.getOrNull()
        if (model == null) {
            Log.d(TAG, "不是本模块认得的水月雨设备，内嵌 ANC 控件跳过 name=$name address=$address")
            return false
        }
        return true
    }

    @Suppress("DiscouragedApi")
    private fun findView(root: View, ctx: Context, name: String): View? {
        val id = runCatching { ctx.resources.getIdentifier(name, "id", SETTINGS_PACKAGE) }.getOrDefault(0)
        if (id == 0) {
            Log.w(TAG, "$SETTINGS_PACKAGE 的资源里没有 $name，这一页不内嵌")
            return null
        }
        return root.findViewById(id)
    }

    private fun existingControl(parent: LinearLayout): SegmentedAncView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is SegmentedAncView && child.tag == VIEW_TAG) return child
        }
        return null
    }

    private fun layoutParamsFor(slider: View, labels: View?, ctx: Context): LinearLayout.LayoutParams {
        val fallback = (FALLBACK_HEIGHT_DP * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val height = rowsHeight(slider, labels).takeIf { it > 0 } ?: fallback
        val source = slider.layoutParams
        val params = if (source is LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams(source)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
        params.height = height
        if (params.width == ViewGroup.LayoutParams.WRAP_CONTENT && params.weight <= 0f) {
            // 宽度 wrap_content 的自绘 View 量出来就是 0，那是「插了但看不见」，必须兜住。
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        return params
    }

    private fun setControlHeight(control: View, height: Int) {
        val lp = control.layoutParams ?: return
        if (lp.height == height) return
        lp.height = height
        control.requestLayout()
    }

    private fun rowsHeight(slider: View, labels: View?): Int = naturalHeight(slider) + naturalHeight(labels)

    /** 行高：已经布局过就用真实高度，否则退到 XML 里声明的高度（这两行都是固定高度）。 */
    private fun naturalHeight(view: View?): Int {
        if (view == null) return 0
        if (view.height > 0) return view.height
        val lp = view.layoutParams ?: return 0
        return if (lp.height > 0) lp.height else 0
    }

    private fun applyHiding(slider: View, labels: View?) {
        hideOnly(slider)
        hideOnly(labels)
    }

    /**
     * 用 GONE 而不是 INVISIBLE：我们的控件占了它原来的位置，留着空位会多出一截空白。
     * 这只是「隐藏」——对象还在树上，厂商的 findViewById 与它自己的状态更新都不受影响。
     */
    private fun hideOnly(view: View?) {
        if (view == null) return
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun currentAncId(): String? = ancIds.getOrNull(ancIndex)

    private fun segmentLabel(id: String): String = if (useChinese()) {
        when (id) {
            "adaptive" -> "自适应"
            "anti_wind" -> "抗风噪"
            "anc" -> "基本"
            else -> id
        }
    } else {
        when (id) {
            "adaptive" -> "Adaptive"
            "anti_wind" -> "Wind noise reduction"
            "anc" -> "Basic"
            else -> id
        }
    }

    private fun useChinese(): Boolean =
        runCatching { Locale.getDefault().language.startsWith("zh") }.getOrDefault(false)

    /**
     * 三段自绘控件。
     *
     * 为什么自绘而不是拼三个 TextView：设置进程里既没有模块的 Compose/Miuix 运行时，也读不到
     * 模块的 drawable 与字符串资源。一个 View 画「圆角轨道 + 选中胶囊 + 三段文字」，不依赖任何
     * 模块资源，也不受厂商主题 selector 的影响。
     *
     * 可点性分两层：控件级 [bind] 的 enabled（只在降噪族里为 true），以及段级的「这个 id 在不
     * 在设备的档位表里」。任何一层不满足，那一段就是灰的。
     */
    private class SegmentedAncView(
        context: Context,
        private val segmentIds: List<String>,
        private val segmentLabels: List<String>,
        private val onSegmentClick: (String) -> Unit,
    ) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val trackRect = RectF()
        private val pillRect = RectF()

        private var currentId: String? = null
        private var knownIds: List<String> = emptyList()
        private var controlEnabled = false

        /** 手指当前压在哪一段（-1 = 没压）。 */
        private var pressedSegment = -1

        private val night: Boolean
            get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        /** 唯一的状态入口：当前档位 id、设备档位表、控件级是否可点。 */
        fun bind(id: String?, ids: List<String>, enabled: Boolean) {
            if (id == currentId && ids == knownIds && enabled == controlEnabled) return
            currentId = id
            knownIds = ids
            controlEnabled = enabled
            isEnabled = enabled
            isClickable = enabled
            contentDescription = describe()
            invalidate()
        }

        private fun describe(): String {
            val current = currentId
            val selected = if (current == null) -1 else segmentIds.indexOf(current)
            val parts = segmentLabels.mapIndexed { index, label ->
                if (index == selected) "$label（已选中）" else label
            }
            return parts.joinToString("，") + if (controlEnabled) "" else "，当前不可用"
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val count = segmentIds.size
            if (w <= 0f || h <= 0f || count <= 0) return
            val density = resources.displayMetrics.density
            // 取色全部写死在这里：设置进程里拿不到模块资源，硬反射厂商私有主题色又会在换 ROM
            // 时崩；轨道 + 选中胶囊这样一层浅色能同时适配浅色与深色。
            val trackColor = if (night) 0x1AFFFFFF else 0x0D000000
            val pillColor = if (night) 0x4D3482FF else 0x333482FF
            val accentColor = 0xFF3482FF
            val textColor = if (night) 0xFFE8E8E8 else 0xFF1F1F1F
            val disabledTextColor = if (night) 0x61FFFFFF else 0x61000000

            val verticalPad = 4f * density
            val barHeight = (h - verticalPad * 2f).coerceAtLeast(1f)
            val radius = barHeight / 2f
            val segmentWidth = w / count

            fillPaint.color = trackColor
            trackRect.set(0f, verticalPad, w, h - verticalPad)
            canvas.drawRoundRect(trackRect, radius, radius, fillPaint)

            val current = currentId
            val selected = if (current == null) -1 else segmentIds.indexOf(current)
            val pillInset = 3f * density
            if (controlEnabled && selected >= 0) {
                fillPaint.color = pillColor
                pillRect.set(
                    selected * segmentWidth + pillInset,
                    verticalPad,
                    (selected + 1) * segmentWidth - pillInset,
                    h - verticalPad,
                )
                canvas.drawRoundRect(pillRect, radius, radius, fillPaint)
            }

            textPaint.textSize = (barHeight * 0.42f).coerceIn(11f * density, 18f * density)
            val metrics = textPaint.fontMetrics
            val baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f
            for (index in 0 until count) {
                val id = segmentIds[index]
                textPaint.color = when {
                    !controlEnabled || !knownIds.contains(id) -> disabledTextColor
                    index == selected -> accentColor
                    else -> textColor
                }
                canvas.drawText(segmentLabels.getOrElse(index) { id }, (index + 0.5f) * segmentWidth, baseline, textPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!controlEnabled) return false
            val index = segmentIndexAt(event.x)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (pressedSegment != index) {
                        pressedSegment = index
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pressedSegment != index) {
                        pressedSegment = index
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val hit = pressedSegment
                    pressedSegment = -1
                    invalidate()
                    performClick()
                    if (hit >= 0) clickSegment(hit)
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressedSegment = -1
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun clickSegment(index: Int) {
            val id = segmentIds.getOrNull(index)
            if (id == null) {
                Log.w(TAG, "点选下标 $index 越界，忽略")
            } else if (!knownIds.contains(id)) {
                Log.w(TAG, "档位 $id 不在当前档位表 $knownIds 里，这次点选忽略")
            } else {
                runCatching { onSegmentClick(id) }
                    .onFailure { Log.w(TAG, "处理三档点选失败 id=$id", it) }
            }
        }

        private fun segmentIndexAt(x: Float): Int {
            val count = segmentIds.size
            if (count <= 0 || width <= 0) return -1
            val index = (x / (width.toFloat() / count)).toInt()
            return if (index in 0 until count) index else -1
        }

        override fun performClick(): Boolean = super.performClick()
    }
}
