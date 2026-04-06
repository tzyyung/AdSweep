# AdSweep

通用 Android 廣告攔截模組。注入任意 APK，自動偵測並攔截已知廣告 SDK，支援 App 專屬規則。

## 特點

- **一條指令注入** — `python inject.py --apk target.apk`
- **自動偵測** — 掃描 APK 裡的廣告 SDK（AdMob, AppLovin, Facebook, IronSource 等 14 種）
- **規則驅動** — JSON 設定檔，通用規則 + App 專屬規則
- **多種攔截方式** — 回傳 void/null/true/false/0/空字串
- **不改 smali** — Runtime Hook，不修改原始字節碼
- **免 root** — 注入後的 APK 在任何設備上都能運作

## 快速開始

```bash
cd injector

# 基本注入（只用通用規則）
python inject.py --apk target.apk

# 帶 App 專屬規則
python inject.py --apk target.apk --rules rules/money_manager.json

# 完整參數
python inject.py --apk target.apk \
  --output patched.apk \
  --rules rules/my_app.json \
  --keystore my.keystore \
  --ks-pass mypass
```

## 技術架構

```
┌─────────────────────────────────────────┐
│  Python Injector                        │
│  反編譯 → 掃描 → 注入 → 打包 → 簽名     │
└─────────────┬───────────────────────────┘
              ↓ 注入到 APK
┌─────────────────────────────────────────┐
│  AdSweep Core (Java + Native)           │
│                                         │
│  ┌──────────┐  ┌───────────────────┐    │
│  │ RuleStore │  │ HookManager      │    │
│  │ 通用規則  │→│ Layer 1: 已知SDK  │    │
│  │ App 規則  │  │ Layer 2: 掃描    │    │
│  │ 白名單    │  │ Layer 3: 行為*   │    │
│  └──────────┘  └────────┬──────────┘    │
│                         ↓               │
│  ┌──────────────────────────────────┐   │
│  │ HookEngine (JNI)                 │   │
│  │ LSPlant 6.4 + ShadowHook 1.1.1  │   │
│  └──────────────────────────────────┘   │
└─────────────────────────────────────────┘
  * Layer 3 尚未實作
```

## 文件

| 文件 | 說明 |
|------|------|
| [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md) | 技術架構詳解 |
| [doc/RULES.md](doc/RULES.md) | 規則系統說明與自訂規則教學 |
| [doc/BUILD.md](doc/BUILD.md) | 建置與開發指南 |

## 支援的廣告 SDK（通用規則）

AdMob, AppLovin, Facebook Audience Network, IronSource, Unity Ads, Vungle, AdColony, InMobi, Chartboost, MoPub, Kakao AdFit, Coupang Ads, StartApp, Pangle (ByteDance)

## 系統需求

- Python 3.8+
- Java 11+（baksmali 用）
- Android SDK build-tools 36.1.0（apktool, zipalign, apksigner）
- apktool 3.0.1+

## 授權

MIT License
