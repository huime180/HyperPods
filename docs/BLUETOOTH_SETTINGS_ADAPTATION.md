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

## 用户澄清（第 2 轮，逐条原话 + 由此确定的做法）

1. **「通透置灰即可，最好是显示相关的自适应 抗风噪 基本这个滑块」**
   → 通透档：把档位控件**置灰**（和关闭一致）。
   → 降噪档：那个档位控件要显示/可选的就是 **自适应 / 抗风噪 / 基本** 这三个（即原生那条档位滑杆）。
2. **「原生滑块是 4 个选项，改改」**
   → 真机上这个原生滑杆当前是 **4 档**，要把它改成上面那 3 档（自适应 / 抗风噪 / 基本）。
   对应模块侧语义：`ANC_SUB_ORDER = [adaptive, anti_wind, anc]`（`ui/components/AncSwitch.kt`）。
   待核实：这台机器上原生滑杆 max 到底是 4（`MiuiHeadsetAncAdjustView` 的 seekBar max），
   以及 4 档里多的那一档是什么（小米自家的档位命名，还是「自适应」被拆开）。
3. **「通知栏显示是蓝牙设置中的一项开关，与模块同步控制通知栏显示」**
   → 原生设备页里**有一项叫「通知栏显示」的开关**；要让它与**模块自己的「通知栏显示」设置双向同步**
   （模块侧已有对应键，见 `ui/ModuleSettings`/`HyperPodsPrefsKey` 一族；hook 侧读的是同一组远程偏好）。
   待核实：该 preference 的 key 与它当前的读写路径（`MiuiHeadsetFragment` 里哪一条）。
4. **「AAC/LHDC 开关仅是系统的高品质控制……未 hook 前是正常的，正常开启耳机的 LHDC 功能后会变成
   LHDC 开关，但 hook 后开启耳机的 LHDC 功能始终显示的是 AAC 开关」**
   → **这是模块 hook 引入的回归**，不是要做新功能：系统那一行本来会随实际编码在
   「AAC 开关 ↔ LHDC 开关」之间切换，hook 之后**一直显示 AAC**（即系统认为当前走的是基础编码）。
   → 排查方向（按可能性，未验证）：① 模块是否在某处把编码**默认写成了 AAC**（关 LHDC 的帧是
   `00 1D 20 06 00`，若连接时下发过就会一直是 AAC）；② `SettingsHeadsetHook` 对
   `HeadsetIDConstants.checkSupport` / `isTWS01Headset` 的改写让系统走了「只支持 AAC」的机型号分支；
   ③ `MoondropController.onSystemCodecChanged` / `reprobeSystemCodec` 回灌给系统的编码名不对；
   ④ `BluetoothUpstreamHeadsetHook` 对 A2DP 编解码状态的改写。**先在真机上抓「未 hook vs hook」两种状态的
   `dumpsys bluetooth_manager` 编解码段与模块日志，再动代码** —— 这条最怕乱改。
5. **「更多设置也是系统蓝牙设置中的一项」**
   → 确认原生设备页上有「更多设置」这一项；把它 hook 成打开**模块自己的耳机控制页**（`SHOW_UI` 那条路已存在）。
   待核实：该 preference 的 key / 它当前的 onClick（真机 dump 认一次）。

## 决定：改走「整页接管」兜底方案（用户拍板）

用户决定：**不再逐个 hook 原生设备页的那些独立项**（通知栏显示 / 高质量-AAC / 更多设置 …），
直接**把原生设备页整页接管，内容换成模块自己的耳机页**，**同时把系统原生的「低延迟」开关保留/插入**。
理由（用户原话）：这样**切换耳机品牌也方便** —— 模块页本来就会按品牌分流。

### 一个必须先说清的硬约束

**设置进程里跑不了 Compose。** 模块自己的耳机页是 Compose/Miuix 写的（`ui/`），而
`com.android.settings` 进程里没有（也不该硬塞）Compose 运行时。所以「换成模块耳机页的内容」只有两条路：

**A. 重定向（推荐）**：hook 原生设备页的入口，直接**打开模块自己的耳机页**（`SHOW_UI` 那条路已存在），
   并把原生的那层页面关掉/退到后面。这样：
   - 品牌分流、10 行手势、ANC 五档、电量、增益…**全部复用模块已有实现**，一行 Compose 都不用重写；
   - 「切换品牌方便」这个诉求天然满足（模块页本来就按品牌分流）；
   - 代价：那一页不再是系统的样子；系统原生那行「低延迟」需要**在模块页里以一个入口/委托项**保留
     （点它再进系统的设备页或直接调系统的低延迟开关），否则用户就找不到它了。

**B. 内嵌自绘控件**：在设置进程里用 `android.view` 手写一套等价控件，隐藏原生各分组，只保留系统那行低延迟。
   参照实现（`NativeThreeModeAncUi`）走的就是这条路。代价：等于把模块耳机页**再实现一遍**（两套 UI 要同步维护），
   而且每个控件都要处理厂商布局差异 —— 与「切换品牌方便」正好相反。

→ 按用户的理由（品牌切换方便、少维护），**取 A**；B 只在 A 出现不可接受的副作用时才考虑。

### 待办（A 路线的拆解）

1. 认准「原生设备页」的入口：`MiuiHeadsetActivity` 与从蓝牙设置点设备那一步（两者可能都要重定向）。
2. 重定向方式：hook `onCreate`/`onResume` 后 `startActivity(模块 MainActivity 的耳机页)` + `finish()`；
   注意**别做成死循环**（模块页 `SHOW_UI` 回来时不能再触发重定向），需要一个「本次由重定向进来」的一次性标记。
3. 低延迟**不跳系统页**：低延迟是系统侧 A2DP 特性，模块**直接控制** —— 模块页一个真开关，经 `LOW_LATENCY_SELECT` / `LOW_LATENCY_CHANGED`（`EXTRA_ENABLED`）与蓝牙进程通信，能力位 `hasLowLatency` 门控显隐。（先前写的「跳系统设备页那一项」已作废：
   系统设备页本身要被重定向到模块页，跳过去会成环。）
4. 品牌判定放在模块页（已有），原生页不再需要按品牌分叉。
5. 回归：OPPO/普通耳机进这一页的行为要明确 —— 是同样重定向到模块页，还是只有水月雨重定向。
