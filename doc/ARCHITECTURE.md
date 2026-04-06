# AdSweep 技術架構

## 概覽

AdSweep 由兩部分組成：

1. **Python Injector** — 在電腦上執行，將 Hook 模組注入到目標 APK
2. **Android Core** — 被注入的模組，在 App 啟動時自動攔截廣告

## 注入流程

```
python inject.py --apk target.apk --rules rules/app.json
  │
  ├─ 1. apktool 反編譯 APK
  │
  ├─ 2. Layer 2 靜態掃描（scanner.py）
  │     掃描 smali 目錄，偵測已知廣告 SDK 和可疑模式
  │
  ├─ 3. 修改 Application.onCreate()（patcher.py）
  │     注入 AdSweep.init(this) 呼叫
  │
  ├─ 4. 複製 payload
  │     ├─ classes.dex → baksmali → smali_classesN/
  │     ├─ lib/**/*.so（libadsweep, liblsplant, libshadowhook, libc++_shared）
  │     └─ assets/（通用規則 + App 規則）
  │
  ├─ 5. 修復 apktool 反編譯問題
  │     drawable @null → @android:color/transparent
  │
  ├─ 6. 修改 AndroidManifest.xml
  │     ├─ extractNativeLibs=true
  │     ├─ SYSTEM_ALERT_WINDOW 權限
  │     └─ SettingsActivity 註冊
  │
  └─ 7. apktool 打包 → zipalign → apksigner 簽名
```

## Hook 引擎

### 架構層次

```
Java 層
  AdSweep.init() → HookManager → HookEngine (JNI)
     ↓                                ↓
  RuleStore                      Native 層
  (JSON 規則)               LSPlant → ShadowHook
                                ↓
                           ART Runtime
                           (修改方法入口)
```

### LSPlant

[LSPlant](https://github.com/LSPosed/LSPlant) 是 LSPosed 團隊的 ART Hook 庫：
- 支援 Android 5.0 ~ 15（API 21-35）
- 透過修改 ART 方法入口指標實現 Java 方法 Hook
- 使用 `lsplant-standalone:6.4`（內含 libc++ 靜態連結）

### ShadowHook

[ShadowHook](https://github.com/nicedayzhu/ShadowHook) 是 ByteDance 的 inline hook 庫：
- LSPlant 內部需要它來修改 ART 的 native 函數
- 提供 `shadowhook_hook_func_addr()` 做 native inline hook
- 提供 `shadowhook_dlsym()` 做符號解析

### Hook 流程

1. `JNI_OnLoad` → 初始化 ShadowHook + LSPlant
2. `AdSweep.init(context)` → 建立 `HookManager`
3. `HookManager` 載入規則 → 用 `ClassLoader.loadClass()` 探測每個規則的 class
4. 找到的 class → 用 `HookEngine.hook()` 安裝 Hook
5. 原始方法被呼叫時 → `BlockCallback.handleHook()` 攔截，回傳設定的值

## 規則系統

詳見 [RULES.md](RULES.md)

## 專案結構

```
AdSweep/
├── core/                          # Android Library 模組
│   ├── src/main/java/com/adsweep/
│   │   ├── AdSweep.java          # 入口點
│   │   ├── hook/
│   │   │   ├── HookEngine.java   # JNI bridge
│   │   │   ├── HookManager.java  # 規則調度
│   │   │   ├── HookCallback.java # 回調基類
│   │   │   └── BlockCallback.java# 攔截回調
│   │   └── rules/
│   │       ├── Rule.java         # 規則資料類
│   │       └── RuleStore.java    # 規則讀寫合併
│   ├── src/main/jni/
│   │   ├── adsweep_jni.cpp       # LSPlant + ShadowHook JNI
│   │   └── CMakeLists.txt
│   └── src/main/assets/
│       └── adsweep_rules_common.json
│
├── injector/                      # Python 注入工具
│   ├── inject.py                  # 主 CLI
│   ├── decompiler.py              # apktool 封裝
│   ├── scanner.py                 # Layer 2 靜態掃描
│   ├── patcher.py                 # smali 注入 + manifest 修改
│   ├── packager.py                # 打包簽名
│   ├── config.py                  # 工具路徑設定
│   ├── baksmali.jar               # DEX 轉 smali 工具
│   └── rules/                     # App 專屬規則範例
│       └── money_manager.json
│
├── prebuilt/                      # 編譯產出（供 injector 使用）
│   ├── classes.dex
│   ├── lib/{arm64-v8a,armeabi-v7a}/*.so
│   └── assets/
│
└── doc/                           # 文件
```

## 已知限制

- **Android API 36+**：ShadowHook linker 初始化失敗（error 12），但 LSPlant 有 fallback 機制仍可正常運作，需進一步測試
- **x86_64**：ShadowHook 不支援，模擬器測試需用 ARM64 映像
- **Private 方法**：LSPlant 可以 Hook private 方法（已驗證 `Main.C2()`）
- **Split APK**：注入的 base APK 需搭配原版 split APK 一起安裝（split 需同一把 keystore 簽名）
