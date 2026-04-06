# AdSweep 技術架構

## 概覽

AdSweep 由兩部分組成：

1. **Python Injector** — 在電腦上執行，將 Hook 模組注入到目標 APK
2. **Android Core** — 被注入的模組，在 App 啟動時自動攔截廣告

## 注入流程

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

## 已知限制

| 限制 | 說明 | 解決方向 |
|------|------|---------|
| Android API 36+ | ShadowHook linker error 12 | LSPlant fallback 仍可運作 |
| x86/x86_64 | ShadowHook 不支援 | 使用 ARM64 模擬器測試 |
| Binary Manifest | 目前只能改 boolean 屬性 | 未來擴充新增 permission/activity |
| Layer 3 UI | 需要 SYSTEM_ALERT_WINDOW | 未註冊到 manifest，降級為通知 |
| Split APK | 需同一把 keystore 簽名 | 手動重簽 split APK |
