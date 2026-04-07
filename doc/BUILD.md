# AdSweep 建置與開發指南

## 開發環境

```mermaid
graph LR
    subgraph Tools
        A[Java 11+]
        B[Python 3.8+]
        C[Android SDK]
        D[apktool 3.0.1+]
        E[baksmali 2.5.2]
    end

    subgraph SDK["Android SDK Components"]
        C --> C1[build-tools 36.1.0]
        C --> C2[NDK 27.0+]
        C --> C3[CMake 3.22.1]
        C --> C4[platform-tools]
    end

    subgraph Python
        B --> B1[androguard]
    end
```

## 工具路徑

`injector/config.py`：

```python
ANDROID_HOME = "~/Library/Android/sdk"
BUILD_TOOLS_VERSION = "36.1.0"
APKTOOL = "/opt/homebrew/bin/apktool"
```

## 建置流程

```mermaid
graph TD
    A["./gradlew :core:assembleDebug"] --> B["core-debug.aar"]
    B --> C["解壓 AAR"]
    C --> D["d8 classes.jar → classes.dex"]
    C --> E["複製 jni/**/*.so"]
    C --> F["複製 assets/"]
    D --> G["prebuilt/classes.dex"]
    E --> H["prebuilt/lib/"]
    F --> I["prebuilt/assets/"]
    G --> J["inject.py 使用"]
    H --> J
    I --> J
```

### 步驟

```bash
# 1. 建立 local.properties
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 2. 編譯
./gradlew :core:assembleDebug

# 3. 更新 prebuilt
cd /tmp && mkdir aar && cd aar
unzip -q path/to/core-debug.aar

mkdir dex
~/Library/Android/sdk/build-tools/36.1.0/d8 --output dex classes.jar

PREBUILT=path/to/AdSweep/prebuilt
cp dex/classes.dex $PREBUILT/
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p $PREBUILT/lib/$abi
  cp jni/$abi/*.so $PREBUILT/lib/$abi/
done
cp assets/* $PREBUILT/assets/
```

## 注入流程

```mermaid
graph TD
    A["python inject.py --apk target.apk"] --> B["apktool d -r"]
    B --> C["Layer 2 掃描"]
    C --> D["smali 注入"]
    D --> E["複製 payload"]
    E --> F["apktool b"]
    F --> G["Binary manifest patch"]
    G --> H["zipalign -p"]
    H --> I["apksigner sign"]
    I --> J["patched.apk"]
```

### 指令

```bash
cd injector

# 基本
python inject.py --apk target.apk

# 帶 App 規則
python inject.py --apk target.apk \
  --rules rules/money_manager.json \
  --keystore path/to/debug.keystore

# 保留工作目錄（除錯用）
python inject.py --apk target.apk --keep-work --work-dir ./work
```

### 自動下載規則

```bash
# 從社群規則庫自動下載（最推薦）
python inject.py --apk target.apk --rules-url auto

# 使用自訂規則庫
python inject.py --apk target.apk \
  --rules-url https://raw.githubusercontent.com/someone/adsweep-rules/main
```

### Discover 模式

```bash
# 1. 注入 discover 版本（只觀察不攔截）
python inject.py --apk target.apk --discover

# 2. 安裝，正常使用 App 幾分鐘

# 3. 拉 log（需 root 或 debug build）
adb shell cat /data/data/<package>/files/adsweep/discovery_log.txt > log.txt

# 4. 分析產出規則
python discover_analyzer.py log.txt -o rules.json

# 5. 用發現的規則注入正式版
python inject.py --apk target.apk --rules rules.json
```

### 更新域名清單

```bash
python domain_converter.py ../core/src/main/assets/adsweep_domains.txt
# 下載 EasyList + EasyPrivacy + AdGuard → 合併去重 → ~99K 域名
```

### 安裝

```bash
ADB=~/Library/Android/sdk/platform-tools/adb

# 單一 APK
$ADB install patched.apk

# Split APK（需同一把 keystore 簽名）
$ADB uninstall com.example.app
$ADB install-multiple \
  patched.apk \
  split_config.arm64_v8a.apk \
  split_config.xxhdpi.apk
```

### 驗證

```bash
$ADB logcat -s "AdSweep" "AdSweep.HookManager" "AdSweep.Block" "AdSweep.L3"
```

預期輸出：

```
I AdSweep : === AdSweep Initializing ===
I AdSweep : LSPlant initialized successfully
I AdSweep : Hook engine ready
I AdSweep.HookManager: Loading 32 rules
I AdSweep.HookManager: Hooked: com.google.android.gms.ads.BaseAdView.loadAd [BLOCK_RETURN_VOID]
...
I AdSweep.HookManager: Initialization complete: 22/32 hooks installed
I AdSweep.L3: Installing Layer 3 monitors...
I AdSweep.L3: Hooked: WebView.loadUrl
I AdSweep.L3: Monitoring: com.google.android.gms.ads.AdListener.onAdLoaded
I AdSweep.L3: Layer 3: 6 monitors installed
I AdSweep : === AdSweep Ready: 22 hooks active ===
I AdSweep.Block: Blocked: com.applovin.sdk.AppLovinSdk.initialize
```

## Manager App（On-Device Patching）

### 建置

```bash
./gradlew :manager:assembleDebug
# 輸出: manager/build/outputs/apk/debug/manager-debug.apk
```

### 安裝到模擬器

```bash
adb install -r manager/build/outputs/apk/debug/manager-debug.apk
```

### 完整 Patch 流程

```bash
N="-n com.adsweep.manager/.CommandReceiver"

# 先啟動 Manager（確保進程存活）
adb shell am start -n com.adsweep.manager/.MainActivity

# 1. 選取目標 App
adb shell am broadcast -a com.adsweep.manager.CMD_SELECT $N \
  --es package com.realbyteapps.moneymanagerfree

# 2. Patch（約 50-90 秒）
adb shell am broadcast -a com.adsweep.manager.CMD_PATCH $N

# 3. 解除安裝原版
adb shell am broadcast -a com.adsweep.manager.CMD_UNINSTALL $N
# → 手動確認解除安裝

# 4. 安裝 patched 版
adb shell am broadcast -a com.adsweep.manager.CMD_INSTALL $N
# → 手動確認安裝

# 5. 查看狀態
adb shell am broadcast -a com.adsweep.manager.CMD_STATUS $N
```

### 查看 Patch 進度

```bash
adb logcat -s "AdSweep.Cmd" "AdSweep.Patch" "AdSweep.DexPatch" "AdSweep.Manifest"
```

### 單元測試（PC 端）

在修改 DexPatcher 後，先跑 PC 端測試確認：

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :patchtest:test
```

### Manager App 依賴

```
com.android.tools.smali:smali-baksmali:3.0.7  # baksmali DEX → smali
com.android.tools.smali:smali:3.0.7           # smali → DEX
com.android.tools.build:apksig:8.7.3          # APK 簽名
org.apache.commons:commons-compress:1.27.1     # ZIP STORED 支援
```

## 簽名金鑰

```bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -alias debugkey \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass android \
  -keypass android \
  -dname "CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=US"
```

## 模擬器測試

ShadowHook 不支援 x86，需使用 ARM64 映像：

```bash
sdkmanager "system-images;android-34;google_apis;arm64-v8a"

avdmanager create avd -n AdSweep_Test \
  -k "system-images;android-34;google_apis;arm64-v8a" -d "pixel_6"

emulator -avd AdSweep_Test
```

> API 36 的 ShadowHook 有 linker error 12，但 LSPlant 仍可運作。建議用 API 34。

## 目錄結構

```
AdSweep/
├── build.gradle.kts            # Root Gradle
├── settings.gradle.kts
├── local.properties            # SDK path
├── core/                       # Android Library (Hook 引擎)
│   ├── build.gradle.kts        # NDK/CMake/LSPlant config
│   └── src/main/
│       ├── java/com/adsweep/   # Java sources
│       ├── jni/                # C++ (LSPlant JNI)
│       └── assets/             # Built-in rules JSON
├── manager/                    # Manager App (On-Device Patching)
│   ├── build.gradle.kts        # dexlib2, apksig, commons-compress
│   └── src/main/
│       ├── java/com/adsweep/manager/
│       │   ├── CommandReceiver.java   # adb broadcast 介面
│       │   ├── PatchEngine.java       # 打包 + 簽名
│       │   ├── DexPatcher.java        # baksmali/smali DEX 注入
│       │   ├── ManifestPatcher.java   # Binary manifest 修改
│       │   └── InstallReceiver.java   # 安裝狀態回調
│       ├── AndroidManifest.xml
│       └── assets/                    # debug.p12, payload/
├── patchtest/                  # PC 端 DexPatcher 單元測試
│   └── src/test/java/          # JUnit tests
├── injector/                   # Python toolchain (PC 端)
│   ├── inject.py               # Main CLI
│   ├── scanner.py              # Layer 2 + auto rules
│   ├── patcher.py              # smali injection
│   ├── manifest_patcher.py     # Binary AXML patching
│   ├── packager.py             # zipalign + sign
│   └── rules/                  # App rule examples
├── prebuilt/                   # Build output for injector
│   ├── classes.dex
│   ├── lib/{arm64-v8a,armeabi-v7a}/*.so
│   └── assets/
└── doc/                        # Documentation
```
