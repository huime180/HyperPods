# Repository Guidelines

## 项目结构与模块组织

单模块 Android/Xposed 工程（仅 `:app`），在 Xiaomi HyperOS 上同时接管两个厂牌的耳机：
OPPO / 一加（欢律私有 RFCOMM 协议）与水月雨 MOONDROP（GAIA 协议）。根构建配置位于
`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`。

主要源码在 `app/src/main/java/com/chenyc/hyperpods/`：

- 根包：`MainActivity`、`HyperPodsApp`、`PopupActivity`、`ConnectionPopupActivity`。
- `config/`：运行配置（含设置页伪装用的设备 ID）。
- `hook/`：Xposed 入口 `HookEntry`、`HookContext` 基类，以及面向 `com.android.bluetooth`、
  `com.xiaomi.bluetooth`、`com.android.settings` 的 Hook 适配；`hook/milink/` 是
  `com.milink.service`（融合设备中心、空间音频）的适配。
- `pods/`：**双品牌共享**的连接与配置层 —— `PodCatalog`/`PodBrand`（品牌与型号判定的唯一入口）
  与 OPPO 侧的 `Packets`/`RfcommController`。
- `pods/moondrop/`：水月雨协议族 —— `MoondropGaia`（帧格式、特性/命令号表、手势动作表）、
  `MoondropFramer`、`MoondropBatteryCodec`、`MoondropSrcProtocol`、`MoondropModelRegistry`、
  `MoondropCapabilities`、`MoondropController`。**协议常量逐字节保留**：重构只改结构与写法，
  不改协议语义。
- `ui/`：Compose/Miuix 界面 —— `MainUI`/`MainTabs`/`MainBottomNavigation`（底栏三页）与
  `ui/pages/`。水月雨控件打包成 `MoondropControls` 载体透传。
- `utils/`：Focus Island、媒体控制、系统 API，以及 `miuiStrongToast/` 的跨进程通知数据与 helper。

资源在 `app/src/main/res/`；Xposed 元数据在 `app/src/main/resources/META-INF/xposed/`。
**`scope.list` 变更时必须同步更新 `README.md` 与本文件。**
架构与融合说明见 `docs/FUSION_ARCHITECTURE.md`。

## 融合原则（最重要的一条）

**骨架、界面与代码写法来自 OppoPods；水月雨侧只贡献功能与协议知识。**

不要把水月雨侧曾经的桥接架构（`ControlBridge` 那套「协议栈放应用进程 + manifest 接收器拉起」）、
状态模型（`PodSnapshot`/`PodEvent`/`PodListener`）、独立入口类、SPDX 文件头或装饰性方框注释
搬进来。判定规则集中在 `pods/`，hook 与 UI 层不得自带型号表或「名称含 oppo」这类宽匹配。

## 构建、测试与开发命令

- 构建：`./gradlew :app:assembleDebug`（Windows：`.\gradlew.bat :app:assembleDebug`）
- 发布：`./gradlew :app:assembleRelease`
- 静态检查：`./gradlew :app:lintDebug`
- 模块内引用自查（本机无 JDK/Android SDK 时）：`python3 tools/check_imports.py`

Release 启用 R8 与资源压缩（具体见 `app/build.gradle.kts`）。GitHub Actions 在
`master` / `main` / `dev` / `rebase-1812z` 分支与对应 PR 上构建 release APK。

## 工作区缓存

构建或 IDE 产生的缓存必须保留在本地，不得为了消除未跟踪文件而删除或移动。
确认不应提交的缓存时，先把精确路径加入 `.gitignore`；来源或用途不明确时先询问用户。

## 编码风格与约定

Kotlin + Gradle Kotlin DSL，4 空格缩进。类型/页面/Composable 用 `PascalCase`，
函数/变量/preference key 用 `camelCase`。注释用中文、讲「为什么」，不要 emoji、不要 SPDX 头。
仓库未配置 ktlint 或 detekt，至少应跑一次 debug 构建。

Miuix 是默认 UI 工具包，页面放在 `ui/`，以主题包裹。**协议命令、字节解析与设备能力判断
集中在 `pods/`**；UI 与 Hook 层不得重复硬编码协议包。跨进程电量一律走
`utils/miuiStrongToast/data/BatteryStatusIntent.kt`，不要自建 extra 格式。
跨进程广播一律 `setPackage(...)`（Android 14+ 丢弃未指定包名的隐式广播）。

界面约定：**耳机相关功能直接铺在耳机页上，不藏二级页**；唯一允许的二级页是手势控制。
新增控件优先走 `MoondropControls` 这类载体，避免在五层参数链上逐个加参数。

LibXposed 入口只保留 `HookEntry` 一个 entry。所有 Hook 必须经 `HookContext` 注册以获得稳定
hook ID；不要直接调 `module.hook()`。热重载需要时必须在 `onHotReloading()` 中停止线程/轮询
并注销广播接收器，避免旧 classloader 被目标进程留住。**注意：当前入口尚未实现热重载，
所以 `module.prop` 不开 `autoHotReload`。**

`object`（standalone）内不能声明 `companion object`，常量直接做成员。
远程设置组名为 `hyperpods_settings`；框架不支持远程首选项时会回落到内存空实现
（等同全部取默认值），判断「设置为什么改不动」时查 `prefsAvailable`。

## 测试与设备验证

提交前至少执行 `python3 tools/check_imports.py`，并确认 GitHub Actions 的构建通过。

改动 Hook、蓝牙、跨进程广播、通知、设置页或界面时，必须在 HyperOS + LSPosed 真机上回归，
覆盖 `scope.list` 中全部受影响进程，**并且两个品牌都要各测一遍**（改动 `pods/` 共享层或
`hook/` 时尤其如此）。不要把只在目标系统上存在的反射失败当成普通逻辑错误；
Hook 代码应保持兼容性保护与可诊断日志。

## 提交、PR 与安全

提交需说明变更目的、验证命令与受影响的系统进程；界面改动附截图，协议改动引用或更新 `docs/`。
不要提交签名密钥、设备日志中的蓝牙地址或私有抓包数据。新增权限、导出组件或 Hook 目标时，
同时审查 `AndroidManifest.xml`、`scope.list` 与 `README.md`。
