
<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="HyperPods"/>

# HyperPods

**为 HyperOS 设备提供系统级 OPPO / 水月雨耳机控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-澎湃OS3-orange?style=flat-square)](https://hyperos.mi.com)

**[English](README_EN.md)** | **简体中文**

</div>

一个模块同时接管两个厂牌的耳机：**OPPO / 一加**（欢律私有 RFCOMM 协议）与
**水月雨 MOONDROP**（GAIA 协议）。接入哪一台由品牌判定自动决定，两条线的功能都摆在
同一个耳机页上，按耳机实际具备的能力显示。

### 耳机功能

**OPPO / 一加**

- **降噪控制** — 关闭 / 降噪 / 自适应 / 通透
- **游戏模式** — 低延迟音频开关，支持连接时自动开启
- **电量显示** — 左耳 / 右耳 / 充电盒
- 均衡器、空间音频、双设备连接等由型号配置档决定

**水月雨 MOONDROP**

- **降噪控制** — 档位由设备实际能力探测得出（三条 ANC 路径自动选择），不靠型号猜测
- **电量显示** — 三路电量，含「单设备电量」机型的兼容处理
- **增益 / 指示灯 / 提示音开关与音量**
- **LHDC 开关** — 与双设备连接互斥，开启时自动关掉另一个并给出提示
- **双设备连接（一拖二）**
- **手势控制（TOUCHV2）** — 5 个槽位 × 2 只耳，单独一页

### 澎湃集成

- **超级岛** — 支持官方超级岛或模块内建超级岛
- **融合设备中心** — 支持融合设备中心控制（水月雨档位会翻译成系统语义）
- **设置集成** — 系统蓝牙设置页伪装成受支持的小米耳机
- **设备流转** — 支持融合设备中心内多设备一键流转
- **型号伪装** — 伪装受支持的小米耳机

### 模块功能

- **底栏三页** — 模块 / 耳机 / 设置
- **快捷弹窗** — 点击通知或控制中心耳机卡片，弹出浮窗显示电量与降噪控制
- **快捷跳转** — 支持快速跳转欢律 / 模块设置 / 系统设置
- **一键重启作用域** — 不用重启手机

### 系统要求

- 小米设备，运行 **HyperOS**（Android 15+）（超级岛仅支持 OS3）
- **LSPosed** API 版本 >= 101

### 使用

1. 安装 APK
2. 在 LSPosed 中启用模块并勾选推荐作用域
3. 用模块右上角一键重启作用域（或重启手机）
4. 通过蓝牙连接你的耳机

> 同时装着其它同类模块（如 PuddingPods、旧的水月雨模块）时，请关掉它们的作用域——
> 多个模块会抢同一批系统类，导致「改了没反应」这类问题。

### 架构与融合说明

见 [docs/FUSION_ARCHITECTURE.md](docs/FUSION_ARCHITECTURE.md)：分层、品牌分流规则、
两套协议栈各跑在哪个进程、以及代码风格约定。

### 致谢

- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 两条线共同的原始项目
- [1812z/OppoPods](https://github.com/1812z/OppoPods) — 本项目的骨架：底栏三页结构、
  融合设备中心与设置页伪装、重启作用域
- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) — 配置档体系与界面细节
- [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) — 水月雨机型适配与真机实测数据
- [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) — EDGE 协议逆向（GAIA V3 命令目录）
- [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) — PUDDING（MD-TWS-056）协议文档
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件

### 许可证

GPL-3.0
