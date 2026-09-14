# HyperPods 架构与融合说明

本文件说明这个模块怎么同时接管两个厂牌的耳机，以及为什么这么分层。

---

## 1. 来源

| 线 | 取用了什么 |
|---|---|
| [1812z/OppoPods](https://github.com/1812z/OppoPods) | **骨架**：底栏三页（模块/耳机/设置）、融合设备中心与设置页伪装、重启作用域、多语言、悬浮底栏 |
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | 配置档体系（能力位驱动界面）与部分界面细节 |
| 水月雨线 | GAIA 协议层、型号档案、能力探测与各功能命令；其上游为 [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods)、[bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop)、[MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop)、[lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) |

**融合原则**：骨架、界面与代码写法沿用 OppoPods；水月雨只贡献**功能与协议知识**。
水月雨侧曾经的桥接架构（协议栈放应用进程 + manifest 接收器拉起）、状态模型
（`PodSnapshot` 那一套）、独立入口类都没有搬进本项目。

---

## 2. 项目身份

| 项 | 值 |
|---|---|
| namespace / applicationId / 模块 id | `com.chenyc.hyperpods` |
| 源码根 | `app/src/main/java/com/chenyc/hyperpods/` |
| Xposed 入口 | `com.chenyc.hyperpods.hook.HookEntry`（`java_init.list` 单入口） |
| 作用域 | `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings` |
| 远程设置组 | `hyperpods_settings` |

---

## 3. 分层

```
com.chenyc.hyperpods
├─ MainActivity / HyperPodsApp / PopupActivity / ConnectionPopupActivity
├─ config/      运行配置（含伪装用的设备 ID）
├─ hook/        HookEntry + HookContext + 每个被注入进程一个 HookContext 子类
│   └─ milink/  com.milink.service 的适配（设备中心、空间音频）
├─ pods/        OPPO 线：Packets（协议包）+ RfcommController（控制器）
│   ├─ PodBrand.kt / PodCatalog.kt   品牌与型号判定的唯一入口
│   └─ moondrop/                     水月雨线：GAIA 协议族
│       ├─ MoondropGaia.kt           GAIA 帧格式、特性/命令号表、手势动作表
│       ├─ MoondropFramer.kt         RFCOMM/SPP 流式切帧
│       ├─ MoondropBatteryCodec.kt   三路电量解析（含「单设备电量」兼容）
│       ├─ MoondropSrcProtocol.kt    中科蓝讯 9ECA 私有协议
│       ├─ MoondropModelRegistry.kt  型号档案（17 款 + 兜底）
│       ├─ MoondropCapabilities.kt   探测到的能力
│       └─ MoondropController.kt     控制器（建链、收包、发布状态）
├─ ui/          Compose + Miuix：MainUI / MainTabs / MainBottomNavigation + pages/
└─ utils/       Focus Island、媒体控制、系统 API、miuiStrongToast（跨进程通知数据）
```

---

## 4. 品牌分流

被注入进程只拿得到「设备名 / MAC」，必须自己算出该设备归谁管：

```kotlin
val brand = PodCatalog.brandOf(context, deviceName, mac)   // OPPO / MOONDROP / null
```

**判定顺序不能反**：水月雨按型号白名单精确匹配（别名 + MAC 前缀），OPPO 按名称宽匹配
（`oppo` / `oneplus` / `oplus`）。先查白名单，水月雨设备才不会被 OPPO 协议栈抢走。

接管点只有一处：`hook/HeadsetStateDispatcher.kt`。连接、断开、以及设备选择页发起的
连接请求都在那里按品牌分派；装备 `DevicePickerPage` 手选的设备同样走这条分流。

---

## 5. 两套协议栈都跑在 `com.android.bluetooth` 进程

这是与「协议栈放应用进程」的最大区别，也是很多接线的由来：

- **收包**：控制器在本进程内解析 GAIA / 欢律数据包
- **状态出口**：只有广播（进程之间不共享内存）
- **命令入口**：应用 UI 与被伪装的原生耳机页把命令**广播回本进程**，
  `HeadsetStateDispatcher` 的接收器转交 `MoondropController.handleUIEvent`

| 目标进程 | 水月雨侧收到的 action |
|---|---|
| `com.android.settings` | `BATTERY_CHANGED`、`ANC_CHANGED`、`GAIN_CHANGED`、`LED_CHANGED`、`PROMPT_TONE_CHANGED`、`PROMPT_VOLUME_CHANGED`、`LHDC_CHANGED`、`DUAL_CONNECTION_CHANGED`、`GESTURE_CHANGED` |
| `com.milink.service` | `BATTERY_CHANGED`、`ANC_CHANGED`、`DUAL_CONNECTION_CHANGED` |
| `com.xiaomi.bluetooth` | `UPDATE_PODS_NOTIFICATION`、`SEND_STRONG_TOAST`、`CANCEL_PODS_NOTIFICATION` |
| 应用进程 | 上面大部分 + `CAPABILITIES_CHANGED`、`CODEC_CHANGED` |

**档位语义要翻译**：水月雨线上传的是型号相关的档位下标，而 HyperOS 各处以
1/2/3/4（关/降噪/通透/自适应）问答，所以界面与融合设备中心两处都要做映射。

---

## 6. 跨进程契约与共用件

- `utils/miuiStrongToast/data/HyperPodsAction.kt` / `HyperPodsPrefsKey.kt`：
  两个品牌的 action 与配置键**只有这一份**（水月雨侧字符串带 `.moondrop.` 段以便区分来源）。
- `utils/miuiStrongToast/data/BatteryStatusIntent.kt`：跨进程电量读写的唯一 helper
  （键 `status` 存 `BatteryParams`，另加一组扁平 extra）。**不要自建 extra 格式**，
  否则通知、设置页、设备中心三处会各写一套。
- 显式广播一律 `setPackage(...)`：Android 14+ 会丢弃未指定包名的隐式广播。

---

## 7. 界面结构

- **底栏三页**：模块 / 耳机 / 设置（`MainBottomNavigation` + `MainTabs`），二级页不显示底栏。
- **功能不藏在二级页**：耳机相关的控件全部直接铺在耳机页（`PodDetailPage` 的
  `podControlItems`）上，按能力位决定显示哪些。
- **唯一的二级页是手势控制**（`Screen.Gesture` + `pages/GesturePage.kt`）：
  5 槽位 × 2 耳的 10 组选择塞进耳机页会把其它功能挤没。
- 控件从 `MainUI` 到详情页要过五层
  （`MainUI → MainTabs → EarphonesTabShell → EarphonesTabPage → PodDetailPage`），
  所以水月雨那些控件打包成一个 `MoondropControls` 载体，每层只多传一个参数。

---

## 8. 代码约定（硬约束）

1. 4 空格缩进；类型/Composable 用 `PascalCase`，函数/变量/偏好键用 `camelCase`。
2. **所有 Hook 经 `HookContext` 注册**，禁止直接调 `module.hook()`；
   `findXxxOrNull` / `findAnyMethod` / `firstPresentClass` 优先，ROM 代数差异靠候选表适配。
3. 任何 hook 注册与反射失败都不得让被注入进程崩溃：统一
   `runCatching { ... }.onFailure { Log.w(TAG, "xxx skipped", it) }`。
4. **协议命令与字节解析只在 `pods/` 里**；UI 与 Hook 层不得重复硬编码协议包，
   也不得自带型号表——品牌与型号一律问 `PodCatalog`。
5. 注释用中文、讲「为什么」；不要 emoji、不要 SPDX 头、不要装饰性方框注释。
6. `object`（standalone）内**不能**写 `companion object`，常量直接做成员。
7. `module.prop` 的 `minApiVersion` / `targetApiVersion` 与 `staticScope` 跟随上游设定；
   本项目未实现 `onHotReloading`，因此**不开** `autoHotReload`。

---

## 9. 验证

- 本机若无 JDK / Android SDK，用 `python3 tools/check_imports.py` 自查模块内引用一致性、
  顶层声明重名与已移除架构残留。
- 真正的编译验证走 GitHub Actions
  （`.github/workflows/build.yml`，触发分支 `master` / `main` / `dev` / `rebase-1812z`）。
- Hook、蓝牙、跨进程广播的改动必须在 HyperOS + LSPosed 真机上回归，并覆盖作用域内全部进程；
  **两个品牌都要各测一遍**。日志过滤 `HyperPods` 可同时看到品牌分流、能力探测与广播收发。
