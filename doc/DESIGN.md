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
```

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
    section 未來
        Android Manager App       :f1, after d2, 7d
        AI 分析引擎               :f2, after d2, 14d
        更多 App 規則             :f3, after d2, 30d
```
