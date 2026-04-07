# AdSweep 技術架構

## 概覽

AdSweep 由三部分組成：

1. **Python Injector** — 在電腦上執行，將 Hook 模組注入到目標 APK
2. **Android Core** — 被注入的模組，在 App 啟動時自動攔截廣告
3. **Manager App** — Android 上的管理工具，可在手機上完成 SELECT → PATCH → UNINSTALL → INSTALL 全流程

## PC 端注入流程

```mermaid
sequenceDiagram
    participant User
    participant inject.py
    participant apktool
    participant scanner.py
    participant patcher.py
    participant manifest_patcher
    participant packager.py

    User->>inject.py: --apk target.apk --rules app.json
    inject.py->>apktool: apktool d -r (不反編譯資源)
    apktool-->>inject.py: decompiled/ (smali + binary res)

    inject.py->>scanner.py: 掃描 smali 目錄
    scanner.py-->>inject.py: 6 SDKs, 50 suggested rules

    inject.py->>patcher.py: 注入 AdSweep
    Note over patcher.py: 1. 找 Application class (androguard)
    Note over patcher.py: 2. 注入 AdSweep.init() 到 onCreate
    Note over patcher.py: 3. 複製 .so + DEX + assets
    Note over patcher.py: 4. 修改 apktool.yml (doNotCompress)
    patcher.py-->>inject.py: smali patched

    inject.py->>apktool: apktool b (重建 APK)
    apktool-->>inject.py: unsigned.apk

    inject.py->>manifest_patcher: 修改 binary manifest
    Note over manifest_patcher: extractNativeLibs = true (binary patch)
    manifest_patcher-->>inject.py: manifest patched

    inject.py->>packager.py: zipalign -p + apksigner
    packager.py-->>User: patched.apk
```

## Manager App On-Device 流程

```mermaid
sequenceDiagram
    participant PC as PC (adb)
    participant Mgr as Manager App
    participant Sys as Android System

    PC->>Mgr: CMD_SELECT --es package com.example.app
    Mgr->>Sys: PackageManager.getApplicationInfo()
    Mgr->>Mgr: 複製 base.apk + split APKs 到 internal storage

    PC->>Mgr: CMD_PATCH
    Mgr->>Mgr: RuleFetcher: 從 GitHub 下載 app rules
    Note over Mgr: adsweep-rules repo → index.json → rules.json
    Mgr->>Mgr: baksmali → 修改 Application.onCreate smali → smali
    Note over Mgr: 注入 AdSweep.init() 到 onCreate
    Mgr->>Mgr: buildPatchedApk (保留原始壓縮方式)
    Note over Mgr: isSignatureFile() 只跳過簽名檔，其餘原樣複製
    Mgr->>Mgr: ManifestPatcher (binary patch)
    Mgr->>Mgr: ApkSigner (v1+v2, alignment)

    PC->>Mgr: CMD_UNINSTALL
    Mgr->>Sys: ACTION_DELETE intent

    PC->>Mgr: CMD_INSTALL
    Mgr->>Mgr: 重簽名 split APKs (同一 debug key)
    Mgr->>Sys: PackageInstaller.Session (base + splits)
    Sys->>Sys: 用戶確認 → 安裝
```

### CommandReceiver Broadcast 指令

所有指令需加 `-n com.adsweep.manager/.CommandReceiver`（Android 14+ 隱式 broadcast 限制）：

```bash
# 選取目標 App（複製 APK + splits 到 internal storage）
adb shell am broadcast -a com.adsweep.manager.CMD_SELECT \
  -n com.adsweep.manager/.CommandReceiver --es package com.example.app

# Patch（baksmali/smali + 打包 + 簽名，約 50-90 秒）
adb shell am broadcast -a com.adsweep.manager.CMD_PATCH \
  -n com.adsweep.manager/.CommandReceiver

# 解除安裝原版（彈出確認對話框）
adb shell am broadcast -a com.adsweep.manager.CMD_UNINSTALL \
  -n com.adsweep.manager/.CommandReceiver

# 安裝 patched APK（彈出安裝確認）
adb shell am broadcast -a com.adsweep.manager.CMD_INSTALL \
  -n com.adsweep.manager/.CommandReceiver

# 查看狀態
adb shell am broadcast -a com.adsweep.manager.CMD_STATUS \
  -n com.adsweep.manager/.CommandReceiver
```

### On-Device Patching 技術要點

| 元件 | 技術 | 說明 |
|------|------|------|
| DEX Patching | baksmali/smali | 避免 dexlib2 DexPool OOM 和 debug info 損壞 |
| APK 打包 | Apache Commons Compress | 保留原始壓縮方式（STORED/DEFLATED），只跳過簽名檔 |
| resources.arsc | 原樣複製（STORED） | 不修改資源表，保證資源引用完整 |
| Manifest | Binary patch (commons-compress) | extractNativeLibs=true, isSplitRequired=false |
| 簽名 | ApkSigner (setAlignmentPreserved=false) | 讓 ApkSigner 主動做 alignment |
| Split APK | Multi-APK install | 不合併 splits，重簽名後一起安裝（PackageInstaller.Session） |
| 記憶體 | File-based streaming | 避免同時載入所有 DEX 到記憶體 |
| App Rules | RuleFetcher | 自動從 adsweep-rules GitHub repo 下載 app-specific 規則 |

## 為什麼用 -r 模式

```mermaid
graph TD
    A{反編譯模式} -->|全反編譯| B[資源 XML 被解碼]
    A -->|"-r 不反編譯資源"| C[資源保持 binary]

    B --> D["@null 損壞（40+ 檔案）"]
    D --> E[InflateException crash]
    E --> F[需要逐一修復]

    C --> G[資源完整無損]
    G --> H[Manifest 是 binary]
    H --> I[用 manifest_patcher 直接改 bytes]
    I --> J[完美運作]

    style C fill:#4CAF50,color:#fff
    style J fill:#4CAF50,color:#fff
    style D fill:#f44336,color:#fff
    style E fill:#f44336,color:#fff
```

## Hook 引擎架構

```mermaid
graph TB
    subgraph Java Layer
        A[AdSweep.init] -->|建立| B[HookManager]
        B -->|載入| C[RuleStore]
        C -->|通用規則| C1[adsweep_rules_common.json]
        C -->|App 規則| C2[adsweep_rules_app.json]
        B -->|安裝 Hook| D[HookEngine]
        D -->|JNI 呼叫| E[nativeHook]
    end

    subgraph Native Layer
        E --> F[LSPlant 6.4]
        F -->|需要 inline hook| G[ShadowHook 1.1.1]
        F -->|修改| H[ART Method Entry]
    end

    subgraph At Runtime
        I[App 呼叫 AdView.loadAd] --> H
        H -->|跳轉| J[BlockCallback.handleHook]
        J -->|回傳 null| K[廣告不載入]
    end
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

## 三層偵測架構

```mermaid
graph TB
    subgraph Layer1["Layer 1 — 已知 SDK 比對（全自動）"]
        L1A["ClassLoader.loadClass()"] -->|找到| L1B["HookEngine.hook()"]
        L1A -->|ClassNotFoundException| L1C[跳過]
        L1B --> L1D["BlockCallback 攔截"]
    end

    subgraph Layer2["Layer 2 — 靜態掃描（注入時）"]
        L2A[掃描 smali 目錄] --> L2B[已知 SDK package 比對]
        L2A --> L2C["啟發式分析（繼承、字串、API 模式）"]
        L2B --> L2D[suggested_rules.json]
        L2C --> L2D
    end

    subgraph Layer3["Layer 3 — 行為偵測（Runtime）"]
        L3A["WebView.loadUrl"] -->|廣告 URL| L3B[攔截 + 回報]
        L3C["AdListener.onAdLoaded"] -->|廣告載入| L3D[記錄 + 回報用戶]
        L3D --> L3E{用戶判斷}
        L3E -->|是廣告| L3F[建立規則]
        L3E -->|不是| L3G[加入白名單]
    end

    Layer2 -->|建議規則| Layer1
    Layer3 -->|用戶回報規則| Layer1
```

## 規則系統

詳見 [RULES.md](RULES.md)

## Binary Manifest Patching

```mermaid
graph LR
    A[APK built by apktool] --> B[讀取 AndroidManifest.xml binary]
    B --> C[解析 Resource Map]
    C --> D["找到 extractNativeLibs 的 string index"]
    D --> E["找到 boolean attribute entry"]
    E --> F["0x00000000 (false) → 0xFFFFFFFF (true)"]
    F --> G[寫回 APK ZIP]
```

## 專案結構

```mermaid
graph TB
    subgraph Root["AdSweep/"]
        subgraph Core["core/ — Android Library"]
            C1["AdSweep.java — 入口點"]
            C2["hook/ — Hook 引擎"]
            C3["rules/ — 規則系統"]
            C4["reporter/ — 浮動 UI"]
            C5["ui/ — Settings Activity"]
            C6["jni/ — LSPlant JNI"]
        end

        subgraph Injector["injector/ — Python 工具"]
            I1["inject.py — 主 CLI"]
            I2["scanner.py — 靜態掃描"]
            I3["patcher.py — smali 注入"]
            I4["manifest_patcher.py — binary XML"]
            I5["packager.py — 打包簽名"]
            I6["rules/ — App 規則範例"]
        end

        subgraph Manager["manager/ — Android Manager App"]
            M1["CommandReceiver — adb broadcast 介面"]
            M2["PatchEngine — on-device patching"]
            M3["DexPatcher — baksmali/smali DEX 注入"]
            M4["ManifestPatcher — binary manifest 修改"]
            M5["InstallReceiver — 安裝狀態回調"]
        end

        subgraph PatchTest["patchtest/ — PC 端單元測試"]
            PT1["DexPatcherTest — DexPool 驗證"]
            PT2["DexPatcherSmaliTest — baksmali/smali 驗證"]
        end

        subgraph Prebuilt["prebuilt/ — 編譯產出"]
            P1["classes.dex"]
            P2["lib/**/*.so"]
            P3["assets/"]
        end

        D["doc/ — 文件"]
    end
```

## 錯誤處理策略

```mermaid
graph TD
    A["AdSweep.init()"] -->|try-catch Throwable| B{成功?}
    B -->|是| C[HookManager 初始化]
    B -->|否| D["Log error, App 繼續運作"]

    C --> E["逐條規則 Hook"]
    E -->|ClassNotFoundException| F["SDK 不存在，跳過"]
    E -->|Hook 失敗| G["Log warning, 繼續下一條"]
    E -->|Hook 成功| H["BlockCallback 攔截"]

    H -->|Callback 異常| I["callOriginal() fallback"]
    H -->|正常| J["回傳設定的值"]

    style D fill:#FF9800,color:#fff
    style F fill:#9E9E9E,color:#fff
    style G fill:#FF9800,color:#fff
    style I fill:#FF9800,color:#fff
    style J fill:#4CAF50,color:#fff
```

## 規則引擎

```mermaid
graph TD
    A["方法被呼叫"] --> B["RuleBasedCallback"]
    B --> C["HookContext（強型別）"]
    C --> D["HookRule.apply()"]
    D --> E{"RuleCondition.evaluate()"}
    E -->|"ALWAYS（無條件）"| F["BlockAction → 回傳固定值"]
    E -->|"URL_MATCHES"| G["DomainMatcher → 比對 99K 域名"]
    G -->|符合| F
    G -->|不符合| H["PassThroughAction → callOriginal"]
    E -->|"ARG_CONTAINS / REGEX"| I["字串比對"]
    I -->|符合| F
    I -->|不符合| H
```

借鑑 Easy Rules 的設計模式（RuleCondition / RuleAction / HookRule），但簡化為 AdSweep 的 1-to-1 Hook 模型。

詳見 [RULE_ENGINE.md](RULE_ENGINE.md)

## Discover 模式

```mermaid
sequenceDiagram
    participant User
    participant inject.py
    participant App
    participant analyzer

    User->>inject.py: --apk app.apk --discover
    inject.py->>inject.py: 掃描 → 50 suggested rules → MONITOR_ONLY
    inject.py-->>User: discover APK

    User->>App: 安裝，正常使用幾分鐘
    App->>App: MonitorAction 記錄所有方法呼叫到 discovery_log.txt

    User->>analyzer: discover_analyzer.py discovery_log.txt
    analyzer->>analyzer: 分析頻率 + 關鍵字 + call stack
    analyzer-->>User: rules_discovered.json (已驗證的規則)

    User->>inject.py: --apk app.apk --rules rules_discovered.json
    inject.py-->>User: 正式版 patched APK
```

## 規則倉庫

```mermaid
graph LR
    A["inject.py --rules-url auto"] --> B["下載 index.json"]
    B --> C["查找 package name"]
    C --> D["下載 rules.json"]
    D --> E["注入到 APK"]

    F["adsweep-rules repo"] --> B
    F --> G["社群貢獻 PR"]
    G --> F
```

倉庫：[tzyyung/adsweep-rules](https://github.com/tzyyung/adsweep-rules)

## 已知限制

| 限制 | 說明 | 解決方向 |
|------|------|---------|
| Android API 36+ | ShadowHook linker error 12 | LSPlant fallback 仍可運作 |
| x86/x86_64 | ShadowHook 不支援 | 使用 ARM64 模擬器測試 |
| Binary Manifest | 目前只能改 boolean 屬性 | 未來擴充新增 permission/activity |
| Layer 3 UI | 需要 SYSTEM_ALERT_WINDOW | 未註冊到 manifest，降級為通知 |
| Split APK | 需同一把 keystore 簽名 | 手動重簽 split APK |
| On-device OOM | 大 APK (>50MB) 需 largeHeap | Manager 已設定 largeHeap=true |
| Android 14+ Broadcast | 隱式 broadcast 受限 | 需加 -n 指定 component |
