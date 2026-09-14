# 蓝牙设置（原生设备页）适配清单

真机：Xiaomi Pad 8 Pro / HyperOS 4（Android 17），耳机 MOONDROP Pudding（FW 3.5.6）。

本文件记录与 HyperOS「蓝牙设备详情页 / 高级耳机页」有关的适配知识。**设置进程 hook 已整体
回退**（提交 `d66eb86`，见第二部分），所以文件分成两块：

- **第一部分 仍然有效的知识**：两类页面的区别、原生设备页的结构、模块侧可复用的语义、
  硬约束，以及**当前的**模块入口写法。
- **第二部分 已作废的路线**：设置页伪装 / 整页接管 / 自绘控件那套方案，只作背景保留，
  不要再照着做。

---

## 第一部分 仍然有效的知识

### 1. 两类页面不是同一张（用户实测）

- 从「设置 → 蓝牙 → 点设备」进入的是 Settings 的**设备详情页**，action 为
  `android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS`。
- 硬编码 `com.android.settings.bluetooth.MiuiHeadsetActivity` 打开的是 HyperOS 的
  **高级耳机页**，与上一张**不是同一张页面**。用户实测过差异。

因此模块自己的入口必须用前者（见第 5 节）；旧实现里那套硬编码 `MiuiHeadsetActivity`
加 MIUI 私有 extra 的写法不要再采用。

### 2. 原生设备页的结构（来源：真机 `com.android.settings.apk` + 真机 dump）

- 原生 ANC 三档：`anclayout` 容器里的 `ancLayoutInfo`（transport / openAnc / closeAnc
  三个可点控件），以及档位滑杆行 `ancAdjust`（`MiuiHeadsetAncAdjustView`）；通透档滑杆是
  另一个类 `MiuiHeadsetTransparentAdjustView`（替换 ANC 控件时必须排除它与它的同族）。
- 真机 dump 到的、系统原生设备页自带的项（**系统自己的，不是模块加的**）：
  `重命名` / `取消配对` / `设备类型` / `通话` / `媒体音频` / `允许访问通讯录和通话记录` /
  `与本机音量同步` / **`LHDC`（副标题「提供高质量音频体验」）** /
  **`低延迟`（副标题「在游戏音视频同步下提供低延迟体验」）**。
- 原生「耳机按键配置」页（`MiuiHeadsetKeyConfigFragment`）的事实见
  `hook/NativeGestureKeyConfig.kt` 的文件头。

### 3. 用户提出的 5 项（原话记录，勿改写）

1. **通透：同关闭锁定（变灰）下方选项**
   选中「通透」时，下方档位控件应与选中「关闭」时一样被锁定/置灰。
2. **降噪：自适应 抗风噪 基本 关闭 保留锁定**
   选中「降噪」时，子档为 **自适应 / 抗风噪 / 基本**；选中「关闭」时**保持锁定**（置灰下方选项）。
3. **适配通知栏显示**
   上述档位变化要能反映到通知栏/焦点通知/超级岛的显示上。
4. **AAC/LHDC 开关根据 LHDC 状态去显示对应开关，为 AAC 开关时可控制 SBC/AAC**
   原生页那一行要**随实际编码状态变化**：当前是 LHDC 时显示 LHDC 开关；LHDC 关闭（走基础编码）时显示为
   AAC 开关，且该开关可控制 SBC / AAC。
5. **更多设置 hook 到模块耳机控制页面**
   原生页的「更多设置」入口改为打开模块自己的耳机控制页。

这 5 项都是针对**被设置进程 hook 的原生页**提的；设置 hook 回退后，它们的落点不复存在，
详见第二部分。

### 4. 模块侧可复用的语义

- ANC 档位与家族（`ui/components/AncSwitch.kt`）：`ANC_NC_FAMILY = ["anc","anti_wind","adaptive"]`、
  `ANC_SUB_ORDER = ["adaptive","anti_wind","anc"]`、`ANC_TRANSPARENCY_FAMILY = ["transparent","live"]`。
  即「降噪」族下正好是 **自适应 / 抗风噪 / 基本**（`adaptive` / `anti_wind` / `anc`）。
- 档位标签（`values-zh-rCN/strings.xml`）：`anc_normal_title=基本`、`anc_anti_wind_title=抗风噪`、
  `adaptive_title=自适应`、`transparency_title=通透`、`off=关闭`。
- 编码：`CODEC_TYPE`（feature 0x10）cmd 5 读 / cmd 6 写；关 LHDC 的帧是 `00 1D 20 06 00`
  （真机验证过 LHDCv5 → AAC）。系统侧实际协商结果由 `CODEC_CHANGED` 广播回灌
  （`MoondropController.onSystemCodecChanged`）。

### 5. 硬约束：设置进程里跑不了 Compose

模块自己的耳机页是 Compose/Miuix 写的（`ui/`），而 `com.android.settings` 进程里没有
（也不该硬塞）Compose 运行时。所以「在设置进程里换成模块耳机页的内容」这条路走不通；
这也是后来整体回退设置 hook 的原因之一。

### 6. 现行程：模块自己的「系统蓝牙设置」入口

`ui/MainUI.kt` 的 `openSystemHeadsetSettings()`（提交 `30b884b`）是唯一的入口实现
（顶栏图标与耳机页底部的「系统蓝牙设置」项都调它）：

```kotlin
// 实现在 MainUI.kt，action / extra 名是文件里的私有常量
context.startActivity(Intent("android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS").apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // device 只在 BLUETOOTH_CONNECT 已授权时才有，未授权就不带这个 extra
    if (device != null) putExtra("android.bluetooth.device.extra.DEVICE", device)
    putExtra("bluetoothaddress", address)
})
// 失败兜底：Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
```

落到与「设置 → 蓝牙 → 点设备」完全相同的设备详情页；无需注入任何 MIUI 私有 extra。

**已知不一致（本次只改文档，未动业务代码）**：`PopupActivity.openSystemSettings()` 仍走旧写法
（`setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")`
+ `MIUI_HEADSET_SUPPORT` / `DEVICE_ID` 等私有 extra）。设置页里「点击通知时行为 / 点击更多时行为」
的「系统设置」选项会走到这里。也就是说模块目前有两条「打开系统蓝牙设置」的路径，只有
`MainUI` 那条跟到了设备详情页；`PopupActivity` 那条待跟进。

---

## 第二部分 已作废的路线（仅作背景）

### 7. 设置进程 hook 已整体回退

提交 `d66eb86`：`hook/HookEntry.kt` 不再分发设置相关 hook，`com.android.settings` 分支被注释掉；
`app/src/main/resources/META-INF/xposed/scope.list` 现在只有 3 条
（`com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`）。

- `hook/SettingsHeadsetHook.kt` 仍在源码树里（785 行，设备页伪装 / 电量注入 / 代理改写等），
  但**没有调用方**，是死代码。
- `hook/NativeGestureKeyConfig.kt` 只被 `SettingsHeadsetHook.onHook()` 调用，因此同样不生效。

所以下面这些说法当前都不成立，不要再写进文档或 PR 描述：

- 「设置页伪装成原生耳机页」「原生耳机页被重定向到模块页」
- 「内嵌自绘三档降噪控件」「作用域 4 个、必须勾选 `com.android.settings`」

### 8. 「整页接管」方案（A 路线）与它的待办：作废

原方案（用户拍板）是**不再逐个 hook 原生页的独立项**，直接**把原生设备页整页接管，
内容换成模块自己的耳机页**，同时保留系统原生的「低延迟」开关。两条路：

- **A. 重定向（当时推荐）**：hook 原生设备页入口，直接打开模块自己的耳机页。
  代价是那一页不再是系统的样子。
- **B. 内嵌自绘控件**：在设置进程里用 `android.view` 手写等价控件。

**该决策与全部待办（认准原生页入口、hook `onCreate`/`onResume` 后 `startActivity` + `finish()`、
防死循环的一次性标记、按品牌是否重定向的回归……）随设置 hook 回退一并作废。** 保留这段只是
说明当时的取舍理由：A 能复用模块已有的品牌分流与全部功能，B 等于把耳机页再实现一遍。

### 9. 自绘控件与 `SHOW_UI`

- B 路线当时参照的实现 `hook/NativeThreeModeAncUi.kt` **已不在源码树里**
  （`ls app/src/main/java/com/chenyc/hyperpods/hook/` 只有 `BluetoothUpstreamHeadsetHook.kt`、
  `HeadsetStateDispatcher.kt`、`HookContext.kt`、`HookEntry.kt`、`MiBluetoothToastHook.kt`、
  `NativeGestureKeyConfig.kt`、`SettingsHeadsetHook.kt` 与 `milink/`）。
- `HyperPodsAction.SHOW_UI`（`chen.action.hyperpods.moondrop.show_ui`）常量仍在，但全仓库
  **没有任何接收方**；A 路线待办里「`SHOW_UI` 那条路已存在」的说法不准确。

### 10. 旧文里被推翻的细节

- 第 3 条「适配通知栏显示」在本文件旧版里被追成一个「原生页有一项叫『通知栏显示』的开关」的
  待确认项 —— 该前提是设置页被 hook，现已作废。
- 第 4 条曾被当成**模块 hook 引入的回归**（hook 后原生页一直显示 AAC）来排查；设置页不再被
  hook，这个回归的观测面已不存在。当时列出的排查方向（是否默认写 AAC 帧、`HeadsetIDConstants`
  改写、`onSystemCodecChanged` 回灌、`BluetoothUpstreamHeadsetHook` 改写 A2DP 编解码状态）
  仅作历史记录。
- 「原生滑杆当前是 4 档、要改成 3 档」的待确认项同属设置页范畴，作废。
- 旧文说模块通知上的「循环切换降噪」按钮与 `MoondropController`「接同一个 action、语义错位」——
  现在两条线用的是**不同的 action**：OPPO 侧 `HyperPodsAction.ACTION_ANC_SELECT`
  （`chen.action.hyperpods.anc_select`，`MiBluetoothToastHook` 里按 `listOf(2,4,3,1)` 这套
  OPPO 档位表循环），水月雨侧 `HyperPodsAction.ANC_SELECT`
  （`chen.action.hyperpods.moondrop.anc_select`，下标语义，由 `MoondropController` 处理）。
  旧描述已不准确；「通知栏那个循环按钮只服务 OPPO」这一遗留仍未处理。

### 11. 低延迟的现状（单独核对过）

`AGENTS.md` 旧版写「水月雨的低延迟由本模块直接控制」，更早的用户决定是「低延迟不做设置、
走系统蓝牙设置」。按代码现状，两边都只说对了一半：

- **界面侧确实按「模块直控」写好了**：`PodDetailPage` 用 `SwitchPreference` 渲染，
  可见性看能力位 `hasLowLatency`（`MoondropControls.KEY_LOW_LATENCY`），
  拨动发 `LOW_LATENCY_SELECT`、状态等 `LOW_LATENCY_CHANGED` 回灌（不乐观更新），
  文案是 `system_low_latency` / `system_low_latency_summary`。
- **蓝牙进程侧没有接线**：`pods/moondrop/MoondropCapabilities.kt` 里没有 `hasLowLatency`
  字段，`MoondropController.publishCapabilities()` 的能力包里也不发这个键；
  `hook/HeadsetStateDispatcher.kt` 的 `MOONDROP_CONTROL_ACTIONS` 不含 `LOW_LATENCY_SELECT`，
  即该 action **没有接收方**；`MoondropModelRegistry.FeatureProfile.lowLatency` 虽在 3 个机型上
  置 true，但全仓库无处读取。

结论：这是一个**未接线的半成品开关** —— 能力位恒为 false，耳机页上那一行目前不会显示。
系统侧的低延迟目前只能走系统蓝牙设置页（第 6 节的入口）。本节即 `AGENTS.md` 里低延迟说法的依据。
