# AdSweep 設計決策與演進

## 設計目標

```mermaid
mindmap
  root((AdSweep))
    通用性
      不依賴特定 App
      規則驅動
      自動偵測 SDK
    穩定性
      不 crash App
      graceful degradation
      不碰資源檔
    易用性
      一條指令注入
      JSON 規則設定
      自動產生建議規則
    免 root
      注入到 APK 內
      不改系統
      不需 Xposed
```

## 技術選型演進

### Hook 引擎

```mermaid
graph LR
    A["直接改 smali（原始做法）"] -->|每次都要分析| B["需要通用化"]
    B --> C{Hook 引擎選擇}
    C -->|"穩定性差 (Android 12+)"| D[Pine]
    C -->|"只支援到 Android 11"| E[SandHook]
    C -->|"API 21-35，LSPosed 驗證"| F["LSPlant ✓"]

    F --> G{Inline Hook}
    G -->|"NDK 27 ASM 不相容"| H[Dobby]
    G -->|"ARM only，但夠用"| I["ShadowHook ✓"]

    style F fill:#4CAF50,color:#fff
    style I fill:#4CAF50,color:#fff
    style D fill:#f44336,color:#fff
    style E fill:#FF9800,color:#fff
    style H fill:#f44336,color:#fff
```

### 反編譯模式

```mermaid
graph TD
    A["全反編譯"] -->|"資源 @null 損壞"| B["修復 40+ drawable XML"]
    B -->|"bitmap → shape/color"| C["依場景不同替換方式"]
    C -->|"仍有漏網之魚"| D["放棄"]

    E["-r 不反編譯資源"] -->|"Manifest 是 binary"| F{如何改 manifest?}
    F -->|"apktool --only-manifest"| G["aapt2 需要 resources.arsc"]
    G -->|"太複雜"| H["放棄"]
    F -->|"androguard 讀取"| I["只能讀不能寫"]
    F -->|"直接改 binary bytes"| J["manifest_patcher ✓"]

    J -->|"Resource Map → String Index → Attribute Entry"| K["修改 boolean 值"]

    style D fill:#f44336,color:#fff
    style H fill:#f44336,color:#fff
    style J fill:#4CAF50,color:#fff
    style K fill:#4CAF50,color:#fff
```

### Layer 3 演進

```mermaid
graph TD
    A["V1: Hook 系統 API"] --> B["ViewGroup.addView"]
    A --> C["Activity.startActivity"]
    B --> D["每個 View 都觸發 → UI 崩潰"]
    C --> E["正常跳轉被干擾 → App 閃退"]

    F["V2: Hook 廣告 callback"] --> G["AdListener.onAdLoaded"]
    F --> H["MaxAdListener.onAdDisplayed"]
    F --> I["WebView.loadUrl（只檢查 URL）"]
    G --> J["低頻呼叫 → 零影響"]
    H --> J
    I --> J

    style D fill:#f44336,color:#fff
    style E fill:#f44336,color:#fff
    style J fill:#4CAF50,color:#fff

    K["V3: Greasemonkey Userscript Engine"] --> L["shouldInterceptRequest（網路層）"]
    K --> M["onPageStarted + onPageFinished"]
    K --> N[".user.js 規則檔（@match/@run-at）"]
    L --> O["99K 域名比對 → 阻止廣告資源下載"]
    M --> P["注入 JS + MutationObserver → 移除 ad 容器"]
    N --> Q["從 GitHub 自動下載更新 → 不需重裝 APK"]

    style O fill:#4CAF50,color:#fff
    style P fill:#4CAF50,color:#fff
    style Q fill:#4CAF50,color:#fff
```

### WebView 混合式 App 的設計教訓（AccuWeather 案例）

AccuWeather 使用 WebView + Vue.js SPA 渲染全部內容，廣告是 server-side HTML 嵌入。
逆向工程過程中踩的坑和解法：

| 嘗試 | 結果 | 原因 |
|------|------|------|
| Hook `getType()` 偽裝訂閱 | hook 安裝成功但從未觸發 | DataStore 無訂閱資料，方法不會被呼叫 |
| Hook `a9.b.b()` 偽裝非免費 | app open ad 消失，但 native ad 還在 | 不同程式碼路徑控制不同廣告 |
| Hook `MobileAds.initialize()` | Method not found | R8 混淆為 `MobileAds.a()` |
| Hook `NativeAdView.setNativeAd()` | 從未觸發 | 廣告不是用 NativeAdView 顯示 |
| CSS `<style>` 注入 | 被 SPA 覆蓋 | Vue.js re-render 覆蓋 `<style>` 標籤 |
| `shouldInterceptRequest` 攔截全部 | "Ad" 佔位符殘留 | 容器是 server HTML，不是 JS 產生 |
| **Inline JS + MutationObserver** | **✅ 成功** | **直接操作 DOM style，survives SPA re-render** |

**關鍵設計決策：**
1. **規則驅動 > 硬編碼**：ad selector 放在 `.user.js` 檔案，從 GitHub 下載更新
2. **四層配合**：shouldInterceptRequest（省頻寬）+ CSS（防閃爍）+ JS（DOM 清除）+ MutationObserver（SPA）
3. **SPA 的 `onPageFinished` body 可能為空**：內容是異步載入的，必須用 MutationObserver 而非一次性查詢

## 規則設計教訓

### Hook 是全局的

```mermaid
graph TD
    A["Globals.e() 被 Hook 回傳 true"] --> B["Intro: if e() → 走購買流程 ✗"]
    A --> C["Main: if !e() → 跳過 ✗"]

    D["正確做法: Hook Globals.k()"] --> E["k() 回傳 true (isPremium)"]
    E --> F["e() = !k() = false（自然正確）"]
    F --> G["Intro: if e()=false → 跳過購買 ✓"]
    F --> H["Main: if !e()=true → 正常 ✓"]

    style B fill:#f44336,color:#fff
    style C fill:#f44336,color:#fff
    style G fill:#4CAF50,color:#fff
    style H fill:#4CAF50,color:#fff
```

### 不要 Hook callback 觸發方法

```mermaid
graph TD
    A["GDPRConsent.n(activity, listener)"] -->|"noop"| B["listener 永遠不回調"]
    B --> C["App 等待 callback → 卡住"]

    D["GDPRConsent.l()"] -->|"回傳 true"| E["上層認為 consent 已取得"]
    E --> F["跳過整個 GDPR 流程 ✓"]

    style C fill:#f44336,color:#fff
    style F fill:#4CAF50,color:#fff
```

## 通用簽名繞過（SPOOF_SIGNATURE）

APK 重簽名後，App 的 tamper detection 會偵測到簽名不符。AdSweep 提供通用的簽名繞過機制：

```mermaid
graph TD
    A["inject.py 注入時"] --> B["從原始 APK 提取簽名"]
    B --> B1["META-INF/*.RSA (v1)"]
    B --> B2["apksigner --print-certs-pem (v2/v3)"]
    B1 --> C["存為 assets/adsweep_original_sig.bin"]
    B2 --> C

    D["Runtime: App 呼叫 getPackageInfo()"] --> E["SpoofSignatureAction"]
    E --> F["呼叫原始方法取得 PackageInfo"]
    F --> G{"是自己的 package<br>+ 要求 signatures?"}
    G -->|是| H["用原始簽名替換 pi.signatures"]
    G -->|否| I["原樣返回"]

    style H fill:#4CAF50,color:#fff
```

**兩層簽名繞過策略：**

| 層級 | 機制 | 適用 |
|------|------|------|
| 通用（common rule） | `SPOOF_SIGNATURE` hook `getPackageInfo()` | 所有用 PackageManager 驗簽的 App |
| App-specific | Hook 具體的驗證方法（如 `oa0$a$a.a()`） | 有 server-side 或自訂驗證的 App |

### CallApp 案例（雙層驗證）

CallApp 使用兩層驗證：OS 層 `getPackageInfo()` + server-side `NLLAppsSHACheckAPIService`。
逆向工程過程中踩的坑和解法：

| 嘗試 | 結果 | 原因 |
|------|------|------|
| 只用 `SPOOF_SIGNATURE` | tamper 警告仍在 | App 有 server-side SHA 驗證 |
| Hook abstract `oa0$a.a()` | hook 安裝但不觸發 | LSPlant 不攔截 subclass 的具體實現 |
| Hook concrete `oa0$a$a.a()` | **✅ 成功** | **直接 hook FailedSHA1Verification 的具體類** |

### Reflection-based Init 注入

```mermaid
graph TD
    A["直接引用 AdSweep.init()"] -->|"增加 method ref"| B["classes.dex 65537 → 溢出"]
    C["Reflection: Class.forName + getMethod + invoke"] -->|"零新 ref"| D["classes.dex 不變 ✓"]

    style B fill:#f44336,color:#fff
    style D fill:#4CAF50,color:#fff
```

CallApp 的 classes.dex 已有 65536/65536 method references，直接引用 `AdSweep.init()` 會溢出。
改用 reflection 呼叫：只引用 `java.lang.Class`、`java.lang.reflect.Method` 等 framework 類，不新增 method reference。

### apktool Obfuscated Resource 修復

```mermaid
graph TD
    A["apktool 3.x rebuild"] -->|"丟失 obfuscated filenames"| B["279 個 res/ 檔案遺失"]
    B --> C["App crash: Resource not found"]
    D["_restore_missing_entries()"] -->|"從原始 APK 補回"| E["所有 entry 完整 ✓"]

    style C fill:#f44336,color:#fff
    style E fill:#4CAF50,color:#fff
```

## On-Device Patching 設計演進

### DexPatcher 演進

```mermaid
graph TD
    A["V1: dexlib2 DexPool"] -->|"重寫整個 DEX"| B["debug_info string index 損壞"]
    B --> C["dexdump 驗證失敗"]

    D["V2: DexPool + strip debug info"] -->|"所有 method"| E["Android 上 OOM（200MB heap）"]
    E --> F["10726 classes × ImmutableMethod 太大"]

    G["V3: baksmali → patch smali → smali ✓"] -->|"文字處理"| H["記憶體友好"]
    H --> I["dexdump 驗證通過"]
    I --> J["Android 上正常運作"]

    style C fill:#f44336,color:#fff
    style F fill:#f44336,color:#fff
    style J fill:#4CAF50,color:#fff
```

**關鍵教訓：** dexlib2 DexPool 在 intern 大量 class 時會損壞 debug info 的 string index，且在 Android 有限的 heap 上 OOM。baksmali/smali 文字流程雖然慢（~50 秒 vs ~15 秒），但穩定且記憶體友好。

### ZIP STORED 問題

```mermaid
graph TD
    A["Android 11+ 要求"] --> B["resources.arsc 必須 STORED + 4-byte aligned"]
    
    C["java.util.zip.ZipOutputStream"] -->|"Android 實作忽略 STORED"| D["全部變 DEFLATED"]
    D --> E["安裝失敗 -124"]
    
    F["Apache Commons Compress"] -->|"正確支援 STORED"| G["resources.arsc STORED ✓"]
    G --> H["ApkSigner setAlignmentPreserved=false"]
    H --> I["自動 4-byte alignment ✓"]
    I --> J["安裝成功"]
    
    style E fill:#f44336,color:#fff
    style J fill:#4CAF50,color:#fff
```

### PatchEngine 記憶體優化

```mermaid
graph LR
    A["V1: Map<String, byte[]>"] -->|"所有 DEX 同時在記憶體"| B["OOM（50MB+ APK）"]
    C["V2: Map<String, File>"] -->|"DEX 存在磁碟"| D["串流寫入 ZIP"]
    D --> E["記憶體使用 < 50MB ✓"]

    style B fill:#f44336,color:#fff
    style E fill:#4CAF50,color:#fff
```

### buildPatchedApk 通用化

```mermaid
graph TD
    A["V1: 全部 STORED + 硬編碼 skip META-INF"] -->|"破壞壓縮方式"| B["資源引用失效"]
    A -->|"刪除 META-INF/services"| C["ServiceLoader 找不到 kotlinx-coroutines"]

    D["V2: 保留原始壓縮 + isSignatureFile()"] -->|"只跳過簽名檔"| E["所有 entry 原樣複製 ✓"]
    E --> F["通用，不需 app-specific 邏輯 ✓"]

    style B fill:#f44336,color:#fff
    style C fill:#f44336,color:#fff
    style F fill:#4CAF50,color:#fff
```

### Split APK 處理

Split APK（density/abi 分割）不能簡單合併到 base APK — 每個 split 有獨立的 `resources.arsc`。

```mermaid
graph TD
    A["V1: 合併 split 到 base"] -->|"resources.arsc 無法合併"| B["Resource$NotFoundException"]
    C["V2: Multi-APK install"] -->|"base + 重簽名 splits"| D["PackageInstaller.Session"]
    D --> E["一次 commit，Android 自行處理 split 載入 ✓"]

    style B fill:#f44336,color:#fff
    style E fill:#4CAF50,color:#fff
```

### 自動 Rules 下載

```mermaid
sequenceDiagram
    participant Mgr as PatchEngine (背景執行緒)
    participant GH as GitHub (adsweep-rules)

    Mgr->>GH: GET index.json
    GH-->>Mgr: {apps: {pkg: {rulesUrl: "..."}}}
    Mgr->>GH: GET apps/{pkg}/rules.json
    GH-->>Mgr: App-specific rules (簽名繞過、廣告封鎖等)
    Mgr->>Mgr: 寫入 assets/adsweep_rules_app.json
    Note over Mgr: 失敗時靜默 fallback，只用 common rules
```

## 效能考量

```mermaid
graph LR
    subgraph 注入開銷
        A["DEX: 16KB"]
        B["Native .so: ~2.3MB"]
        C["APK 大小增加: ~8MB"]
    end

    subgraph Runtime 開銷
        D["Hook 安裝: ~200ms（啟動時一次）"]
        E["每次攔截: <1ms（直接回傳）"]
        F["Layer 3 callback: <1ms（轉發 + log）"]
    end
```

## 與其他方案的比較

```mermaid
graph TB
    subgraph AdSweep
        AS1["注入到 APK 內"] --> AS2["免 root"]
        AS2 --> AS3["規則驅動"]
        AS3 --> AS4["可攔截 SDK 初始化"]
    end

    subgraph "DNS 攔截（AdGuard）"
        DNS1["VPN 隧道"] --> DNS2["免 root"]
        DNS2 --> DNS3["域名過濾"]
        DNS3 --> DNS4["不能擋 SDK 初始化"]
    end

    subgraph "Xposed/LSPosed"
        XP1["系統層 Hook"] --> XP2["需要 root"]
        XP2 --> XP3["全設備生效"]
        XP3 --> XP4["模組生態"]
    end

    subgraph "手動改 smali"
        SM1["直接改字節碼"] --> SM2["免 root"]
        SM2 --> SM3["每次都要分析"]
        SM3 --> SM4["不可複用"]
    end
```

## 開發時程

```mermaid
gantt
    title AdSweep 開發階段
    dateFormat YYYY-MM-DD
    section Phase 1-5
        Hook 引擎 + 注入腳本     :done, p1, 2026-04-06, 1d
        規則系統 + 多種 action    :done, p2, after p1, 1d
        掃描自動轉規則            :done, p3, after p2, 1d
        Layer 3 + Settings UI     :done, p4, after p3, 1d
    section Phase 6 加固
        Layer 3 重設計            :done, p6a, after p4, 1d
        Binary Manifest Patch     :done, p6b, after p4, 1d
        錯誤處理                  :done, p6c, after p4, 1d
    section 規則引擎
        條件式攔截架構            :done, re1, after p6a, 1d
        域名清單整合 99K          :done, re2, after re1, 1d
    section 自動化
        Discover 模式             :done, d1, after re2, 1d
        規則倉庫 + auto download  :done, d2, after d1, 1d
    section Manager App
        Manager 基礎架構          :done, m1, after d2, 1d
        CommandReceiver broadcast :done, m2, after m1, 1d
        On-device PATCH 完整流程  :done, m3, after m2, 1d
    section 未來
        AI 分析引擎               :f2, after m3, 14d
        更多 App 規則             :f3, after m3, 30d
```
