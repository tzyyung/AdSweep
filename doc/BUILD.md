# AdSweep 建置與開發指南

## 前置需求

### 系統工具
- Java 11+
- Python 3.8+

### Android SDK
- build-tools 36.1.0（zipalign, apksigner, d8）
- NDK 27.0+
- CMake 3.22.1
- platform-tools（adb）

### 其他工具
- apktool 3.0.1+（`/opt/homebrew/bin/apktool`）
- baksmali 2.5.2（`injector/baksmali.jar`，自動使用）

## 工具路徑

預設路徑在 `injector/config.py` 中設定，可根據環境調整：

```python
ANDROID_HOME = "~/Library/Android/sdk"
BUILD_TOOLS_VERSION = "36.1.0"
APKTOOL = "/opt/homebrew/bin/apktool"
```

## 建置 Core 模組

```bash
# 建立 local.properties（指定 SDK 路徑）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 編譯
./gradlew :core:assembleDebug

# 產出位置
# core/build/outputs/aar/core-debug.aar
```

## 更新 Prebuilt Payload

Core 模組編譯後，需要將產出更新到 `prebuilt/` 供 injector 使用：

```bash
# 1. 解壓 AAR
cd /tmp && mkdir aar && cd aar
unzip -q /path/to/AdSweep/core/build/outputs/aar/core-debug.aar

# 2. 轉換 DEX
mkdir dex
~/Library/Android/sdk/build-tools/36.1.0/d8 --output dex classes.jar

# 3. 複製到 prebuilt
PREBUILT=/path/to/AdSweep/prebuilt
cp dex/classes.dex $PREBUILT/
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p $PREBUILT/lib/$abi
  cp jni/$abi/*.so $PREBUILT/lib/$abi/
done
cp assets/* $PREBUILT/assets/
```

## 注入

### 基本注入

```bash
cd injector
python inject.py --apk /path/to/target.apk --output patched.apk
```

### 帶 App 規則

```bash
python inject.py \
  --apk /path/to/target.apk \
  --rules rules/money_manager.json \
  --keystore /path/to/debug.keystore
```

### 注入流程說明

1. `apktool d -r` — 反編譯 smali，資源不動
2. Layer 2 掃描 — 自動偵測廣告 SDK，產生 `suggested_rules.json`
3. 注入 — smali 入口 + .so + assets + App 規則
4. `apktool b` — 重建 APK
5. `zipalign -p` — 頁對齊（.so 不壓縮，配合 extractNativeLibs=false）
6. `apksigner sign` — 簽名

### 安裝到模擬器/設備

```bash
ADB=~/Library/Android/sdk/platform-tools/adb

# 單一 APK
$ADB install patched.apk

# 有 Split APK 時
$ADB uninstall com.example.app  # 必須先卸載（簽名不同）
$ADB install-multiple \
  patched.apk \
  split_config.arm64_v8a.apk \
  split_config.xxhdpi.apk
```

### 驗證 Hook

```bash
# 查看 AdSweep 日誌
$ADB logcat -s "AdSweep" "AdSweep.HookManager" "AdSweep.Block"
```

正常輸出：
```
I AdSweep : === AdSweep Initializing ===
I AdSweep : LSPlant initialized successfully
I AdSweep : Hook engine ready
I AdSweep.HookManager: Loading 32 rules
I AdSweep.HookManager: Hooked: com.google.android.gms.ads.BaseAdView.loadAd [BLOCK_RETURN_VOID]
...
I AdSweep.HookManager: Initialization complete: 22/32 hooks installed
I AdSweep : === AdSweep Ready: 22 hooks active ===
I AdSweep.Block: Blocked: com.applovin.sdk.AppLovinSdk.initialize
```

## 簽名金鑰

測試用的 debug keystore：

```bash
# 如果沒有，建立一個
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

需要使用 ARM64 架構的模擬器映像（ShadowHook 不支援 x86）：

```bash
# 安裝 API 34 ARM64 映像
sdkmanager "system-images;android-34;google_apis;arm64-v8a"

# 建立 AVD
avdmanager create avd -n AdSweep_Test \
  -k "system-images;android-34;google_apis;arm64-v8a" -d "pixel_6"

# 啟動
emulator -avd AdSweep_Test
```

> 注意：API 36 的 ShadowHook linker 有相容性問題（error 12），但 LSPlant 仍可正常運作。建議使用 API 34 測試。
