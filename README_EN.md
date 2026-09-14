
<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="HyperPods"/>

# HyperPods

**System-level OPPO / MOONDROP earphone control for HyperOS devices**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS3-orange?style=flat-square)](https://hyperos.mi.com)

**English** | **[简体中文](README.md)**

</div>

One module drives two brands of earbuds: **OPPO / OnePlus** (the HeyMelody private RFCOMM
protocol) and **MOONDROP** (the GAIA protocol). Which one to take over is decided
automatically at connection time, and both are driven from the same earphones page, with
controls shown only for the capabilities the connected device actually reports.

### Earphone Features

**OPPO / OnePlus**

- **ANC** — Off / Noise Cancellation / Adaptive / Transparency
- **Game Mode** — low-latency audio, optionally auto-enabled on connect
- **Battery** — left / right / case
- Equalizer, spatial audio and dual connection depend on the model profile

**MOONDROP**

- **ANC** — modes probed from the device (three ANC paths chosen automatically), never guessed from the model
- **Battery** — three-way, including the "single device battery" models
- **Gain / Indicator LED / Prompt tone and volume**
- **LHDC toggle** — mutually exclusive with dual connection; enabling one turns the other off with a hint
- **Dual connection (OneBringTwo)**
- **Gesture controls (TOUCHV2)** — 5 slots × 2 ears, on its own page

### HyperOS Integration

- **Super Island** — official or built-in
- **Device center** — with MOONDROP levels translated into system semantics
- **Settings integration** — the Bluetooth settings page is impersonated as a supported Xiaomi headset
- **Model spoofing**

### Module Features

- **Bottom bar with three pages** — Module / Earbuds / Settings
- **Quick popup** from the notification or the device-center card
- **One-tap scope restart** — no phone reboot needed

### Requirements

- Xiaomi device running **HyperOS** (Android 15+), Super Island requires OS3
- **LSPosed** API version >= 101

### Usage

1. Install the APK
2. Enable the module in LSPosed with the recommended scope
3. Restart the scope from the module (or reboot)
4. Connect your earbuds over Bluetooth

> If other modules of the same kind are installed (PuddingPods, an older MOONDROP module),
> disable their scope: several modules fighting over the same system classes is a common
> cause of "my changes do nothing".

### Architecture

See [docs/FUSION_ARCHITECTURE.md](docs/FUSION_ARCHITECTURE.md) for the layering, the brand
routing rule, which process hosts each protocol stack, and the coding conventions.

### Credits

- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — the original project both lines descend from
- [1812z/OppoPods](https://github.com/1812z/OppoPods) — this project's skeleton: the three-page bottom bar, device center and settings impersonation, scope restart
- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) — device profile system and UI details
- [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) — MOONDROP model adaptation and on-device measurements
- [MegaSuite/moondrop-link-desktop](https://github.com/MegaSuite/moondrop-link-desktop) — EDGE protocol reverse engineering (GAIA V3 command catalogue)
- [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods) — PUDDING (MD-TWS-056) protocol documentation
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS-style Compose UI components

### License

GPL-3.0
