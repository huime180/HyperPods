# 蓝牙设置（原生设备页）适配清单

真机：Xiaomi Pad 8 Pro / HyperOS 4（Android 17），耳机 MOONDROP Pudding（FW 3.5.6）。
目标：**尽量保留系统原生 UI**，只在其上做必要增补与接管（不另造一套自绘页面）。

## 用户提出的 5 项（原话记录，勿改写）

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

## 已核实的原生页事实（来源：真机 com.android.settings.apk + 真机 dump）

- 原生 ANC 三档：`anclayout` 容器里的 `ancLayoutInfo`（transport / openAnc / closeAnc 三个可点控件），
  以及档位滑杆行 `ancAdjust`（`MiuiHeadsetAncAdjustView`）；通透档滑杆是另一个类
  `MiuiHeadsetTransparentAdjustView`（替换 ANC 控件时必须排除它与它的同族）。
- 真机 dump 到的系统原生设备页自带的项（**这些是系统自己的，不是我加的**）：
  `重命名` / `取消配对` / `设备类型` / `通话` / `媒体音频` / `允许访问通讯录和通话记录` /
  `与本机音量同步` / **`LHDC`（副标题「提供高质量音频体验」）** / **`低延迟`（副标题「在游戏音视频同步下提供低延迟体验」）**。
- 按键配置页的事实见 `NativeGestureKeyConfig.kt` 的文件头（那一块已完成）。

## 本模块侧已有的、可直接复用的语义

- ANC 档位与家族（`ui/components/AncSwitch.kt`）：`ANC_NC_FAMILY = ["anc","anti_wind","adaptive"]`、
  `ANC_SUB_ORDER = ["adaptive","anti_wind","anc"]`、`ANC_TRANSPARENCY_FAMILY = ["transparent","live"]`。
  即「降噪」族下正好是 **自适应 / 抗风噪 / 基本**（`adaptive` / `anti_wind` / `anc`）—— 与第 2 条要求一致。
- 档位标签：`anc_normal_title=基本`、`anc_anti_wind_title=抗风噪`、`adaptive_title=自适应`、
  `transparency_title=通透`、`off=关闭`。
- 编码：`CODEC_TYPE`（feature 0x10）cmd 5 读 / cmd 6 写；关 LHDC 的帧是 `00 1D 20 06 00`（真机验证过
  LHDCv5 → AAC）。系统侧实际协商结果由 `CODEC_CHANGED` 广播回灌（`MoondropController.onSystemCodecChanged`）。

## 待确认（不确定，不猜）

- 第 4 条「为 AAC 开关时可控制 SBC/AAC」：系统那行是**开关**（两态），而 SBC/AAC 是**两个取值** ——
  是指「这一行在 LHDC 关闭时改名为 AAC，拨动它就在 AAC 与 SBC 之间切换」，还是另外加一个二选一控件？
- 第 3 条「适配通知栏显示」：是指焦点通知/超级岛上那个「切换降噪」按钮要跟着本页档位走（点一下切到下一个档位），
  还是仅指通知副标题里的档位文案？两者实现差别较大。
- 「更多设置」具体指原生页上哪个入口（`key_config` 那条？还是别的 preference）—— 需要按真机 dump 认一次。
- 原生页的 LHDC 行是系统自己的实现（走 `AudioManager`/蓝牙栈），模块接管它需要 hook 哪一层（设置侧 preference
  还是蓝牙栈的 codec 协商）尚未核实。

## 已知的相邻缺陷（本轮报告过，未修）

模块通知上那个「循环切换降噪」按钮对两条线共用，但处理时用 **OPPO 的档位表**算出 1/2/3 再发
`ACTION_ANC_SELECT`，而 `MoondropController` 也接同一个 action 并把 status 当**水月雨 UI 档位下标**解释 ——
插水月雨时语义错位；该 hook 的过滤器也只订阅 OPPO 的 `ANC_CHANGED`。
