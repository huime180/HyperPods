# HyperPods

[English](#english) | [中文](#中文)

---

## English

Xposed module that brings system-level **OPPO / OnePlus** and **MOONDROP** earphone control to Xiaomi HyperOS devices.

HyperPods merges two projects into one module:

| Source | What it contributes |
|--------|--------------------|
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | Architecture, UI, build setup — the whole project skeleton |
| [huime180/HyperPods-for-Moondrop](https://github.com/huime180/HyperPods-for-Moondrop) | MOONDROP support: the GAIA protocol, model profiles, feature set |

Both descend from [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen.

### Features

**OPPO / OnePlus earbuds (HeyMelody RFCOMM protocol)**

- **ANC Control** — Off / Noise Cancellation / Adaptive / Transparency
- **Spatial Audio** — Off / Fixed / Head Tracking
- **Game Mode** — low-latency toggle, optional auto-enable on connect
- **Equalizer** — device presets plus user-defined custom EQ
- **Battery** — left / right / case, live in the status bar and Focus Island
- **Quick Popup** — tap the notification for a compact battery / ANC / game-mode panel
- **Device Profiles** — capability-based model registry (`assets/device_models.json`)

**MOONDROP earbuds (GAIA protocol)**

- **ANC Control** — modes are probed from the device, not assumed
- **Battery** — three-way, fixed for the "right earbud shows nothing" family of bugs
- **Gain / LED / Prompt tone & volume** / **Dual connection (OneBringTwo)**
- **LHDC toggle** with the system codec re-probe so "current codec" is not stale
- **Gestures (TOUCHV2)** — 5 slots × 2 ears
- **Low latency** — drives the HyperOS system-side A2DP low-latency configuration

### Requirements

- Xiaomi device running **HyperOS** (Android 15+)
- **LSPosed** or a compatible Xposed framework
- Module scope: `com.android.bluetooth`, `com.milink.service`, `com.xiaomi.bluetooth`, `com.android.settings`
(`com.android.systemui` will be added together with the device-card takeover)

### How It Works

The protocol stack runs **inside the hooked `com.android.bluetooth` process**. Every other
process is a pure state adapter: it receives state over explicit broadcasts and sends
commands back the same way.

| Process | Purpose |
|---------|---------|
| `com.android.bluetooth` | Detect the earbuds on A2DP, pick the right protocol family, open the control channel, parse packets, publish state |
| `com.milink.service` | Mirror ANC / battery / multipoint state into the HyperOS device center |
| `com.xiaomi.bluetooth` | Focus Island battery popup and the persistent notification |
| `com.android.settings` | Impersonate the native headset page and route its controls back to the module |

Brand routing happens once, at connection time, through a single entry point
(`pods/PodCatalog.brandOf`): MOONDROP models are matched by an explicit name/MAC whitelist
first, and only then the broader OPPO name match is tried — so a MOONDROP device can never
be captured by the OPPO protocol stack.

### Protocols

- **OPPO / OnePlus** — Bluetooth Classic RFCOMM (HeyMelody SPP UUIDs, or fixed channel 15).
  Frame: `AA [len] 00 00 [cmd 2B LE] [seq] [payloadLen 2B LE] [payload…]`.
- **MOONDROP** — GAIA v3 over BLE GATT, or GAIA v4 over RFCOMM/SPP for models such as PUDDING.
  Requests and responses are correlated by `(feature, command)` with a timeout, because GAIA
  carries no sequence number.

Details, opcode tables and reverse-engineering notes live in `docs/`.

### Build

```bash
./gradlew assembleDebug
```

Release builds enable R8 and resource shrinking. CI builds a release APK on every push to
`master` / `main` / `dev` and on `v*` tags.

### Install

1. Install the APK
2. Enable the module in LSPosed with the scope listed above
3. Reboot
4. Connect your earbuds over Bluetooth

### Status

The OPPO line is the inherited OppoPods 1.2.3 feature set and is unchanged. The MOONDROP line
currently covers device detection, connection, capability probing, battery, ANC and the
command path; the MOONDROP-specific native-page features (gesture card hosting, whole-block
ANC replacement, device-card click) are still being ported. See
`docs/FUSION_ARCHITECTURE.md`.

### Credits

- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — the original project both parents descend from
- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) — architecture, UI and OPPO support
- [huime180/HyperPods-for-Moondrop](https://github.com/huime180/HyperPods-for-Moondrop) — MOONDROP support
- [libxposed](https://github.com/libxposed/api) — Xposed module API
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS-style Compose UI components

### License

GPL-3.0

---

## 中文

为小米 HyperOS 设备提供系统级 **OPPO / 一加** 与 **水月雨（MOONDROP）** 耳机控制的 Xposed 模块。

HyperPods 由两个项目融合而成：

| 来源 | 贡献 |
|------|------|
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | 架构、界面与构建骨架 —— 整个项目的形态来源 |
| [huime180/HyperPods-for-Moondrop](https://github.com/huime180/HyperPods-for-Moondrop) | 水月雨支持：GAIA 协议、型号档案与功能集 |

两者都源自 Art_Chen 的 [HyperPods](https://github.com/Art-Chen/HyperPods)。

### 功能

**OPPO / 一加耳机（欢律 RFCOMM 协议）**

- **降噪控制** — 关闭 / 降噪 / 自适应 / 通透
- **空间音频** — 关闭 / 固定 / 头部跟踪
- **游戏模式** — 低延迟开关，可选连接时自动开启
- **均衡器** — 设备预设 + 自定义 EQ
- **电量显示** — 左耳 / 右耳 / 充电盒，状态栏与焦点岛实时显示
- **快捷弹窗** — 点击通知弹出电量 / 降噪 / 游戏模式面板
- **设备配置档** — 基于能力位的型号注册表（`assets/device_models.json`）

**水月雨耳机（GAIA 协议）**

- **降噪控制** — 档位由设备实际能力探测得出，不靠型号猜测
- **电量显示** — 三路电量，修复「右耳电量不显示」那一类问题
- **增益 / 指示灯 / 提示音开关与音量 / 双设备连接（一拖二）**
- **LHDC 开关** — 切换后主动重探系统编码，避免「当前编码」停在旧值
- **手势（TOUCHV2）** — 5 个槽位 × 2 只耳
- **低延迟** — 操作 HyperOS 系统侧的 A2DP 低延迟配置

### 系统要求

- 小米设备，运行 **HyperOS**（Android 15+）
- **LSPosed** 或兼容的 Xposed 框架
- 模块作用域：`com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings`
  （`com.android.systemui` 等设备卡接管做完再一并加入）

### 工作原理

协议栈跑在**被 hook 的 `com.android.bluetooth` 进程内**。其余进程都是纯状态适配层：
状态通过显式广播收，命令也通过广播发回。

| 进程 | 用途 |
|------|------|
| `com.android.bluetooth` | 在 A2DP 上感知耳机、判定协议族、建立控制通道、解析数据包、发布状态 |
| `com.milink.service` | 把降噪 / 电量 / 多设备连接状态同步进 HyperOS 融合设备中心 |
| `com.xiaomi.bluetooth` | 焦点岛电量弹窗与常驻通知 |
| `com.android.settings` | 伪装成小米原生耳机页，把页面上的操作路由回本模块 |

品牌分流只在连接时做一次，入口唯一（`pods/PodCatalog.brandOf`）：水月雨按型号白名单
（名称 / MAC）精确匹配，匹配不上才去试 OPPO 侧的宽匹配 —— 这样水月雨设备不会被
OPPO 协议栈抢走。

### 协议

- **OPPO / 一加** — 经典蓝牙 RFCOMM（欢律 SPP UUID，或固定通道 15）。
  帧格式：`AA [长度] 00 00 [命令 2B 小端] [序列号] [载荷长度 2B 小端] [载荷…]`。
- **水月雨** — 大多数机型走 GAIA v3 over BLE GATT，布丁 PUDDING 等走 GAIA v4 over
  RFCOMM/SPP。GAIA 不带序列号，因此请求与响应按 `(feature, command)` 配对并带超时。

协议细节、命令号表与逆向记录见 `docs/`。

### 构建

```bash
./gradlew assembleDebug
```

Release 构建启用 R8 与资源压缩。CI 会在推送到 `master` / `main` / `dev` 以及 `v*` 标签时
构建 release APK。

### 安装

1. 安装 APK
2. 在 LSPosed 中启用模块，作用域按上面列出的五个包勾选
3. 重启设备
4. 通过蓝牙连接耳机

### 当前进度

OPPO 线继承自 OppoPods 1.2.3，功能保持原样。水月雨线目前覆盖设备识别、连接、能力探测、
电量、降噪与命令通路；水月雨专属的原生页功能（手势卡托管、ANC 整块替换、设备卡点击接管）
仍在移植中。详见 `docs/FUSION_ARCHITECTURE.md`。

### 致谢

- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 两个上游共同的原始项目
- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) — 架构、界面与 OPPO 支持
- [huime180/HyperPods-for-Moondrop](https://github.com/huime180/HyperPods-for-Moondrop) — 水月雨支持
- [libxposed](https://github.com/libxposed/api) — Xposed 模块 API
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件

### 许可证

GPL-3.0
