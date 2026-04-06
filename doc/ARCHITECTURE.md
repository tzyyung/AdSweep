# AdSweep 技術架構

## 概覽

AdSweep 由兩部分組成：

1. **Python Injector** — 在電腦上執行，將 Hook 模組注入到目標 APK
2. **Android Core** — 被注入的模組，在 App 啟動時自動攔截廣告

## 注入流程

```
python inject.py --apk target.apk --rules rules/app.json
  │
  ├─ 1. apktool -r 反編譯（不反編譯資源，避免 @null 損壞）
  │     Manifest 另外用 --only-manifest 解碼為文字版
  │
  ├─ 2. Layer 2 靜態掃描（scanner.py）
  │     ├─ 掃描 smali 目錄，偵測已知廣告 SDK
  │     ├─ 啟發式分析（class 繼承、字串常量、API 呼叫模式）
  │     └─ 自動產生建議規則（suggested_rules.json）
  │
  ├─ 3. 修改 Application.onCreate()（patcher.py）
  │     注入 AdSweep.init(this) 呼叫
  │
  ├─ 4. 複製 payload
  │     ├─ classes.dex → baksmali → smali_classesN/
  │     ├─ lib/**/*.so（libadsweep, liblsplant, libshadowhook, libc++_shared）
  │     └─ assets/（通用規則 + App 規則）
  │
  ├─ 5. 修改 apktool.yml
  │     └─ 加入 .so 到 doNotCompress（配合 extractNativeLibs=false）
  │
  └─ 6. apktool 打包 → zipalign -p（頁對齊）→ apksigner 簽名
```

## 為什麼用 -r 模式

apktool 全反編譯會將資源 XML 中的 binary 引用解碼為 `@null`，重建後導致：
- `InflateException` — drawable 載入失敗
- 需要修復大量 XML 檔案（40+）

使用 `-r` 模式不反編譯資源，原始資源保持 binary 格式，完全避免此問題。
代價是 AndroidManifest.xml 也保持 binary，無法直接文字編輯。
目前的解決方式：
- `extractNativeLibs`：透過 doNotCompress + zipalign -p 替代
- 權限/Activity 註冊：暫未修改，不影響核心功能

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
- 每個 App 進程獨立運作，不影響系統或其他 App
- 使用 `lsplant-standalone:6.4`

### ShadowHook

[ShadowHook](https://github.com/bytedance/android-inline-hook) 是 ByteDance 的 inline hook 庫：
- LSPlant 內部需要它來修改 ART 的 native 函數
- 提供 `shadowhook_hook_func_addr()` 做 native inline hook
- 提供 `shadowhook_dlsym()` 做符號解析

### Hook 流程

1. `JNI_OnLoad` → 初始化 ShadowHook + LSPlant
2. `AdSweep.init(context)` → 建立 `HookManager`
3. `HookManager` 載入規則 → 用 `ClassLoader.loadClass()` 探測每個規則的 class
4. 找到的 class → 用 `HookEngine.hook()` 安裝 Hook
5. 原始方法被呼叫時 → `BlockCallback.handleHook()` 攔截，回傳設定的值
6. 找不到的 class → `ClassNotFoundException` 自動跳過，無副作用

## 三層偵測

| 層次 | 時機 | 方式 | 狀態 |
|------|------|------|------|
| Layer 1 | Runtime | ClassLoader 探測 + Hook 已知 SDK | **已完成** |
| Layer 2 | 注入時 | 靜態掃描 smali，自動產生建議規則 | **已完成** |
| Layer 3 | Runtime | 行為偵測 + 用戶回報 UI | 程式碼就緒，暫時禁用 |

### Layer 3 暫時禁用原因

Hook `ViewGroup.addView()` 和 `Activity.startActivity()` 等高頻系統方法
會嚴重影響 App UI 渲染和 Activity 跳轉，導致閃退。
需重新設計為侵入性更低的偵測點。

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
│   │   │   ├── BlockCallback.java# 攔截回調（多種 action）
│   │   │   └── LayerThreeMonitor.java # Layer 3（暫時禁用）
│   │   ├── rules/
│   │   │   ├── Rule.java         # 規則資料類
│   │   │   └── RuleStore.java    # 規則讀寫合併
│   │   ├── reporter/
│   │   │   ├── FloatingReporter.java  # 浮動回報 UI
│   │   │   └── DetectionEvent.java    # 偵測事件
│   │   └── ui/
│   │       └── SettingsActivity.java  # 設定介面
│   ├── src/main/jni/
│   │   ├── adsweep_jni.cpp       # LSPlant + ShadowHook JNI
│   │   └── CMakeLists.txt
│   └── src/main/assets/
│       └── adsweep_rules_common.json
│
├── injector/                      # Python 注入工具
│   ├── inject.py                  # 主 CLI
│   ├── decompiler.py              # apktool -r 封裝 + manifest 解碼
│   ├── scanner.py                 # Layer 2 靜態掃描 + 自動產生規則
│   ├── patcher.py                 # smali 注入 + apktool.yml 修改
│   ├── packager.py                # 打包（zipalign -p）+ 簽名
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

- **Android API 36+**：ShadowHook linker 初始化失敗（error 12），但 LSPlant 有 fallback 仍可正常運作
- **x86/x86_64**：ShadowHook 不支援，模擬器測試需用 ARM64 映像
- **Private 方法**：LSPlant 可以 Hook private 方法（已驗證 `Main.C2()`）
- **Split APK**：注入的 base APK 需搭配原版 split APK 一起安裝（split 需同一把 keystore 簽名）
- **Manifest 修改**：-r 模式下 manifest 保持 binary，目前無法直接編輯（不影響核心功能）
- **Layer 3**：高頻系統方法 Hook 會影響穩定性，暫時禁用
