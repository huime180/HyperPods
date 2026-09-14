# HyperPods 融合架构（实施总纲）

本文件是本次融合工作的**唯一依据**。任何实现都必须先符合这里定义的架构分层与代码风格。

---

## 1. 目标与来源

- 项目：**HyperPods** —— 一个在 Xiaomi HyperOS 上提供系统级耳机控制的 libxposed 模块，同时接管两个厂牌。
- 形态来源（架构、UI、命名、注释、构建、CI）：`Leaf-lsgtky/OppoPods`。
- 功能来源（能力与协议知识）：`huime180/HyperPods-for-Moondrop`。

**核心原则：只从水月雨侧取「功能」，一律用 OppoPods 的写法表达。**

具体地说，下列**不得**出现在本仓库里：

| 禁止 | 原因 | 替代 |
|------|------|------|
| `ControlBridge` 那一套「协议栈放应用进程 + manifest 接收器拉起」的桥接架构 | 与 OppoPods「协议栈跑在被 hook 的 `com.android.bluetooth` 进程」的架构冲突 | `pods/MoondropController.kt` 采用 `RfcommController` 的形态：`object` + `connectPod/disconnectedPod/handleUIEvent/shutdownForHotReload` + 主动广播 |
| `PodSnapshot` / `PodEvent` / `PodListener` 这套状态模型 | OppoPods 的跨进程状态模型是「广播 + extra」与「AppXxxController 的逐项 StateFlow」 | 复用既有 `BatteryParams`、`BatteryStatusIntent`、`DeviceCapabilities`/`DeviceProfile` 能力位；跨进程用 `HyperPodsAction` 的 extra |
| `XposedEntry` 入口类 | 入口只能有一个，且必须叫 `HookEntry` | 已合并进 `hook/HookEntry.kt` |
| `SPDX-License-Identifier` 文件头、`HyperPods for Moondrop — xxx` 抬头 | OppoPods 没有这种文件头 | 直接删掉，用 OppoPods 的 `/** 中文说明 */` 风格 |
| 水月雨侧的短横线注释腔调（`── xxx ──`）、`⚠` 标记 | 与 OppoPods 注释风格不一致 | OppoPods 风格：`//` 中文说明，讲「为什么」 |
| `file: moe.chenxy.*` | 包名已统一 | `com.chenyc.hyperpods.*` |

---

## 2. 项目身份（已落地）

| 项 | 值 |
|----|----|
| namespace / applicationId | `com.chenyc.hyperpods` |
| 源码包根 | `app/src/main/java/com/chenyc/hyperpods/` |
| 模块 id / 名称 | `com.chenyc.hyperpods` / `HyperPods` |
| Xposed 入口 | `com.chenyc.hyperpods.hook.HookEntry`（`java_init.list` 单入口） |
| RemotePreferences 组 | `hyperpods_settings` |
| 广播前缀 | OPPO 侧 `chen.action.hyperpods.*`；水月雨侧 `chen.action.hyperpods.moondrop.*` |
| 作用域 | `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings` |

---

## 3. 分层

```
com.chenyc.hyperpods
├─ MainActivity / PopupActivity / ConnectionPopupActivity      承载界面（OppoPods 原样）
├─ hook/        HookEntry + HookContext + 每个被注入进程一个 HookContext 子类
├─ pods/        OPPO/HeyMelody 协议族 + 双品牌共享的配置档与身份判定
│   ├─ PodBrand.kt / PodCatalog.kt      品牌与型号判定的唯一入口
│   ├─ Packets.kt / RfcommController.kt / AppRfcommController.kt / DeviceModelRegistry.kt ...
│   └─ moondrop/                        水月雨 GAIA 协议族（新）
│       ├─ MoondropGaia.kt              GAIA 帧格式、特性/命令号表
│       ├─ MoondropFramer.kt            帧切分与校验
│       ├─ MoondropBatteryCodec.kt      三路电量编解码
│       ├─ MoondropSrcProtocol.kt       SRC 子协议
│       ├─ MoondropModelRegistry.kt     型号表 → DeviceCapabilities/DeviceProfile
│       └─ MoondropController.kt        水月雨控制器（RfcommController 形态）
├─ ui/          Compose/Miuix 页面（OppoPods 原样 + 依能力位显示水月雨专属项）
└─ utils/       Focus Island、媒体控制、系统 API、偏好、miuiStrongToast
```

### 3.1 两个厂牌怎么分流

被注入进程只拿到「设备名 / MAC / productId」，必须自己算出该设备归谁管：

```kotlin
val brand = PodCatalog.brandOf(context, deviceName, mac)   // PodBrand.OPPO / MOONDROP / null
```

顺序不能反：水月雨侧是**白名单精确匹配**，OPPO 侧的 `isOppoName` 是 `contains` 宽匹配，
先查白名单可以避免水月雨设备被 OPPO 协议栈抢走（见 `pods/PodBrand.kt` 的注释）。

### 3.2 数据流

```
OPPO 线（沿用 OppoPods 原样）
  A2DP 连接 → hook/HeadsetStateDispatcher(蓝牙进程) → RfcommController.connectPod()
            → 协议收包 → 广播 chen.action.hyperpods.* → 各被注入进程 / 应用进程

水月雨线（功能取自 Moondrop，形态按 OppoPods）
  A2DP 连接 → hook/HeadsetStateDispatcher(蓝牙进程) → MoondropController.connectPod()
            → GAIA 收包 → 广播 chen.action.hyperpods.moondrop.* → 各被注入进程 / 应用进程
```

**两条线的接管点都在 `com.android.bluetooth` 进程**，由 `HeadsetStateDispatcher` 按 `PodBrand` 二选一。

---

## 4. 代码风格（硬约束）

1. **缩进 4 空格**，Gradle Kotlin DSL；类型/页面/Composable 用 `PascalCase`，函数/变量/偏好键用 `camelCase`。
2. **一切 Hook 经 `HookContext` 注册**，禁止直接调用 `module.hook()`。HookContext 提供：
   `hookBefore/hookAfter/hookConstructorAfter`、`findClass/findClassOrNull/firstPresentClass`、
   `findMethod/findMethodOrNull/findMethodByParamCount(OrNull)/findConstructorByParamCount(OrNull)/findAnyMethod`、
   `prefBoolean/isEnabled/processContextOrNull/bind`，以及顶层 `getObjectField/setObjectField/callMethod/callStaticMethod/getStaticObjectField`。
3. **任何 hook 注册与反射失败都不得让被注入进程崩溃**：统一
   `runCatching { ... }.onFailure { Log.w(TAG, "xxx skipped", it) }`。
4. **协议命令与字节解析只在 `pods/` 里**；UI 与 Hook 层不得重复硬编码协议包（AGENTS.md 既有约定）。
5. **跨进程电量状态复用 `utils/miuiStrongToast/data/BatteryStatusIntent.kt` 的兼容读写 helper**，不得自建 extra 格式。
6. 日志用 `private const val TAG = "HyperPods-XXX"`，与 OppoPods 现有 TAG 命名一致。
7. 跨进程广播一律 `setPackage(...)`（Android 14+ 丢弃未指定包名的隐式广播）——水月雨侧广播的既有约定，保持。
8. 注释用中文，讲「为什么」，与 OppoPods 现有注释同腔调；不要 emoji、不要 SPDX 头。
9. `module.prop` 的 `minApiVersion`/`targetApiVersion`/`autoHotReload` 必须保持 `101`/`102`/`true`。
10. 新增作用域包必须同步 `scope.list`、`README.md`、`AGENTS.md`。

---

## 5. 验证

本机没有 JDK/Android SDK（可用内存不足以跑 AGP），因此：

- 提交前的一致性检查：包名/符号残留、常量重名、引用完整性（见 `tools/check_imports.py`）。
- 真正的编译验证走 GitHub Actions（`.github/workflows/build.yml`），产出 APK。
- Hook/蓝牙/跨进程改动必须在 HyperOS + LSPosed 真机上回归，并覆盖 `scope.list` 全部进程。
