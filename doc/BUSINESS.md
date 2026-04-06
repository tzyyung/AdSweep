# AdSweep 商業分析與策略

## SWOT 分析

```mermaid
quadrantChart
    title AdSweep SWOT
    x-axis "負面" --> "正面"
    y-axis "外部" --> "內部"
    quadrant-1 Strengths
    quadrant-2 Weaknesses
    quadrant-3 Threats
    quadrant-4 Opportunities
    JSON規則門檻低: [0.7, 0.8]
    不需root: [0.8, 0.7]
    通用性強: [0.6, 0.9]
    自動偵測SDK: [0.75, 0.65]
    規則庫小: [0.3, 0.7]
    需要CLI: [0.2, 0.8]
    法律灰色: [0.2, 0.3]
    Android封堵: [0.15, 0.2]
    App加固: [0.3, 0.15]
    廣告攔截市場大: [0.8, 0.3]
    AI分析趨勢: [0.7, 0.2]
    社群經濟: [0.85, 0.15]
```

### Strengths（優勢）

| 優勢 | 說明 |
|------|------|
| **JSON 規則門檻低** | 比 ReVanced（Kotlin）和 Xposed（Java module）簡單很多，社群貢獻門檻最低 |
| **免 root** | 比 LSPosed/Magisk 的門檻低，覆蓋更多設備 |
| **通用性** | 不限特定 App（ReVanced 只做 YouTube 等少數 App） |
| **自動偵測** | Layer 2 掃描 + 建議規則，半自動化 |
| **規則與工具分離** | 規則是資料（JSON），不是程式碼，容易維護和分享 |
| **多層防禦** | SDK Hook + URL 攔截 + 行為偵測，比單一方案更全面 |

### Weaknesses（劣勢）

| 劣勢 | 說明 | 建議 |
|------|------|------|
| **SDK 規則庫小** | 目前只有 Money Manager 一個 App 的完整 SDK 規則 | 1. 整合現有域名清單（5 萬+ 域名，立即可用）<br>2. 逐步建立 Top 50 App 的 SDK 規則 |
| **需要 CLI** | 一般人不會用命令列 | 做 Web 平台或 Android Manager App |
| **每次 App 更新要重新注入** | 不像 DNS 方案一次設定永久生效 | 未來做自動化更新檢測 |
| **規則產出依賴逆向分析** | 即使有建議規則，App 專屬規則仍需人工 | AI 分析引擎（Phase 7） |
| **Layer 3 尚未穩定** | 行為偵測重設計後覆蓋面有限 | 逐步增加偵測點 |

### Opportunities（機會）

| 機會 | 說明 | 建議 |
|------|------|------|
| **廣告攔截市場持續成長** | 全球行動廣告攔截用戶超過 6 億 | 切入免 root 市場 |
| **AI 分析 smali** | LLM 能力持續提升，自動逆向分析可行 | 建立 AI → 規則的 pipeline |
| **AdGuard 域名清單整合** | 幾十萬條現成規則 | 實作規則引擎後直接用 |
| **ReVanced 社群模式驗證可行** | fork + 社群維護 patch 的模式已成功 | 複製其社群運營模式 |
| **企業隱私需求** | 公司可能需要去除 App 追蹤 | 企業版定位 |
| **新興市場** | 東南亞/印度用戶對廣告容忍度低，付費意願對工具型產品有 | 針對高廣告密度市場推廣 |

### Threats（威脅）

| 威脅 | 嚴重性 | 說明 | 對策 |
|------|--------|------|------|
| **法律風險** | 高 | 修改 APK 可能違反 DMCA/著作權 | 定位為「隱私工具」而非「破解工具」；伺服器放在法律友善的國家 |
| **Android 封堵 Hook** | 中 | Google 可能在新版 Android 限制 ART Hook | LSPlant 團隊持續跟進；最壞情況回退到 smali patch |
| **App 加固** | 中 | 360 加固、騰訊樂固等殼保護 | 先做未加固 App，加固 App 需要額外脫殼步驟 |
| **Google Play Protect** | 中 | 可能標記修改版 APK | 用戶需手動允許；文件中說明 |
| **競爭者跟進** | 低 | Lucky Patcher 等可能模仿 JSON 規則模式 | 靠規則庫和 AI 分析的先發優勢 |
| **App 開發者對抗** | 低 | 增加混淆強度、server-side ad rendering | 大部分 App 不會為了對抗而改架構 |

## 競爭者分析

```mermaid
graph TB
    subgraph "APK 修改類"
        R["ReVanced<br>⭐ 開源, 社群強<br>❌ 只支援少數 App"]
        L["Lucky Patcher<br>⭐ 內建資料庫<br>❌ 閉源, 信任問題"]
        LP["LSPatch<br>⭐ Xposed 生態<br>❌ 需要寫 Java 模組"]
    end

    subgraph "網路攔截類"
        AG["AdGuard<br>⭐ 30萬+規則<br>❌ 不能擋 SDK 初始化"]
        BL["Blokada<br>⭐ 開源, 免費<br>❌ 只擋域名"]
        PH["Pi-hole<br>⭐ 全網路<br>❌ 需要硬體/伺服器"]
    end

    subgraph "AdSweep 定位"
        AS["AdSweep<br>⭐ JSON 規則, 免 root, 通用<br>⭐ SDK + URL 雙層攔截<br>❌ 規則庫小, CLI only"]
    end

    AS -.->|"互補：整合域名清單"| AG
    AS -.->|"競爭：同為 APK patch"| R
    AS -.->|"差異化：JSON vs Kotlin"| LP
```

### 詳細對比

| 維度 | AdSweep | ReVanced | Lucky Patcher | AdGuard | LSPatch |
|------|---------|----------|---------------|---------|---------|
| **需要 root** | 否 | 否 | 否 | 否 | 否 |
| **修改 APK** | 是 | 是 | 是 | 否 | 是 |
| **規則格式** | JSON | Kotlin | 二進位 | 文字 | Java module |
| **貢獻門檻** | 低 | 中 | 高 | 最低 | 中 |
| **通用性** | 任何 App | 特定 App | 任何 App | 任何 App | 任何 App |
| **攔截層級** | SDK + URL | SDK | 各種 | URL only | SDK |
| **開源** | 是 | 是 | 否 | 部分 | 是 |
| **社群規模** | 無 | 大 | 中 | 大 | 中 |
| **商業模式** | 待定 | 捐款 | 免費+內購 | App 付費 | 無 |

### 競爭定位

```mermaid
graph LR
    subgraph "易用性 ↑"
        AG2["AdGuard<br>（最易用）"]
        LP2["Lucky Patcher"]
    end

    subgraph "技術深度 ↑"
        RV2["ReVanced"]
        AS2["AdSweep<br>（目標位置）"]
        XP2["LSPatch/Xposed"]
    end

    AG2 -->|"AdSweep 目標：<br>技術深度 + 易用性"| AS2
```

AdSweep 的差異化：
1. **比 AdGuard 深** — 能攔截 SDK 初始化，不只是 URL
2. **比 ReVanced 廣** — 不限特定 App
3. **比 LSPatch 簡單** — JSON 規則，不用寫 Java
4. **比 Lucky Patcher 透明** — 開源，可審計

## 產品形態建議

### 推薦：開源核心 + SaaS 平台

```mermaid
graph TB
    subgraph "免費層（開源）"
        F1["inject.py CLI"]
        F2["Hook 引擎"]
        F3["通用規則（25 條）"]
        F4["規則格式規範"]
        F5["社群規則倉庫"]
    end

    subgraph "付費層（SaaS）"
        P1["Web 平台<br>上傳 APK → 下載 patched APK"]
        P2["AI 規則產出<br>自動分析 App → 產出完整規則"]
        P3["Android Manager App<br>手機上一鍵注入"]
        P4["優先規則更新<br>新 App 規則優先取得"]
        P5["企業版<br>批量管理 + API"]
    end

    F1 --> |"技術用戶<br>免費"| U1["開發者 / 逆向工程師"]
    P1 --> |"一般用戶<br>$2-5/月"| U2["普通手機用戶"]
    P2 --> |"進階用戶<br>$10/月"| U3["想自訂規則的人"]
    P5 --> |"企業<br>$50-200/月"| U4["公司 IT 部門"]
```

### 定價策略

| Tier | 價格 | 包含 |
|------|------|------|
| **Free** | $0 | CLI 工具 + 通用規則 + 社群規則 |
| **Basic** | $3/月 | Web 平台注入 + 所有 App 規則 |
| **Pro** | $10/月 | AI 分析 + 自訂規則產出 + 優先支援 |
| **Enterprise** | $50-200/月 | 批量管理 + API + 私有規則庫 |

### 為什麼這樣定價

- **Free 要夠用** — 技術用戶用免費版就能完成所有事，他們是社群的核心
- **Basic 賣便利性** — 一般人願意為「不用裝 Python」付費
- **Pro 賣 AI** — 自動分析 smali 產出規則，這是真正的技術護城河
- **Enterprise 賣合規** — 公司需要去除 App 追蹤，願意付高價

## 營收預估

```mermaid
pie title 預估營收比例（成熟期）
    "Basic 訂閱" : 40
    "Pro 訂閱" : 30
    "Enterprise" : 20
    "捐款/贊助" : 10
```

## 法律風險分析

### 各法域對比

| 法域 | 逆向工程 | APK 修改 | 風險等級 | 備註 |
|------|---------|---------|---------|------|
| **美國** | DMCA 1201 例外：安全研究 | 灰色地帶 | 中 | 需包裝為「隱私/安全工具」 |
| **歐盟** | 軟體指令允許互通性逆向 | 相對寬鬆 | 低 | GDPR 支持隱私工具 |
| **台灣** | 著作權法第59條：合理使用 | 灰色地帶 | 中 | 「個人使用」可辯護 |
| **中國** | 反不正當競爭法 | 風險較高 | 高 | 商業行為可能觸法 |
| **日本** | 不正競爭防止法 | 灰色地帶 | 中 | 技術中立原則 |

### 風險緩解

```mermaid
graph TD
    A["法律風險"] --> B["定位為隱私工具"]
    A --> C["不內建任何 App 的 APK"]
    A --> D["用戶自行提供 APK"]
    A --> E["開源 + 社群維護規則"]
    A --> F["伺服器在法律友善國家"]
    A --> G["不提供付費破解功能"]

    B --> H["合法性最大化"]
    C --> H
    D --> H
    E --> H
    F --> H
    G --> H
```

**關鍵原則：**
1. **AdSweep 本身不分發任何 APK** — 只提供工具和規則
2. **定位為隱私保護** — 去除追蹤、封堵資料收集，不是「破解」
3. **規則由社群維護** — 平台不為規則內容負責（類似 GitHub 託管程式碼）
4. **不做付費功能的破解** — 只做廣告移除和隱私保護

## 成長策略

### Phase 1：冷啟動（0-1000 用戶）

```mermaid
graph LR
    A["自己分析 Top 20 App"] --> B["發佈規則到 GitHub"]
    B --> C["寫技術文章/教學"]
    C --> D["XDA / Reddit / PTT 推廣"]
    D --> E["吸引第一批技術用戶"]
    E --> F["技術用戶貢獻更多規則"]
```

- 自己分析 Top 20 常見有廣告的 App（新聞、工具、遊戲）
- 在 XDA Developers、Reddit r/Android、PTT 等社群發佈
- 寫詳細的教學文章，降低使用門檻

### Phase 2：社群成長（1000-10000 用戶）

```mermaid
graph LR
    A["規則庫達 100+ App"] --> B["做 Web 平台 MVP"]
    B --> C["一般用戶湧入"]
    C --> D["Layer 3 回報產出更多規則"]
    D --> A
```

- 規則庫覆蓋 100+ 常見 App
- Web 平台上線（Basic tier）
- Layer 3 用戶回報機制驅動規則增長

### Phase 3：商業化（10000+ 用戶）

```mermaid
graph LR
    A["AI 分析引擎上線"] --> B["Pro tier"]
    B --> C["企業客戶"]
    C --> D["穩定營收"]
    D --> E["全職維護團隊"]
```

- AI 分析引擎上線
- 企業版推出
- 組建全職團隊

## 規則貢獻者激勵

```mermaid
graph TD
    A["貢獻者提交規則"] --> B{"規則被多少用戶使用?"}
    B -->|"100+ 用戶"| C["顯示在 Contributors 頁面"]
    B -->|"1000+ 用戶"| D["Pro tier 免費"]
    B -->|"10000+ 用戶"| E["營收分潤（5-10%）"]
    C --> F["社群認可"]
    D --> F
    E --> F
```

## 規則整合策略 — 借力現有生態

AdSweep 不需要從零建立規則庫。大量現有規則可以直接或轉譯後使用。

### 規則來源地圖

```mermaid
graph TB
    subgraph "域名清單（直接整合，5 萬+ 域名）"
        D1["AdGuard Base Filter<br>~60,000 rules"]
        D2["EasyList<br>~90,000 rules"]
        D3["EasyPrivacy<br>~30,000 rules"]
        D4["Peter Lowe's List<br>~3,000 domains"]
        D5["Steven Black hosts<br>~80,000 domains"]
    end

    subgraph "轉譯"
        T1["提取域名"]
        T2["去重排序"]
        T3["~50,000 個不重複域名"]
    end

    subgraph "AdSweep"
        A1["URL_MATCHES 規則<br>Hook OkHttp/URL/WebView"]
        A2["SDK Hook 規則<br>25 條通用 + App 專屬"]
        A3["Layer 3 行為偵測"]
    end

    D1 --> T1
    D2 --> T1
    D3 --> T1
    D4 --> T1
    D5 --> T1
    T1 --> T2
    T2 --> T3
    T3 --> A1

    A1 --> R["全面攔截"]
    A2 --> R
    A3 --> R
```

### 整合對照表

| 來源 | 規則數量 | 轉譯方式 | 難度 | 前提 |
|------|---------|---------|------|------|
| AdGuard Base Filter | ~60,000 | 提取域名 → URL_MATCHES | 低 | 規則引擎 |
| EasyList | ~90,000 | 提取域名 → URL_MATCHES | 低 | 規則引擎 |
| EasyPrivacy | ~30,000 | 提取域名 → URL_MATCHES | 低 | 規則引擎 |
| Peter Lowe's List | ~3,000 | 域名直接用 | 最低 | 規則引擎 |
| Steven Black hosts | ~80,000 | 域名直接用 | 最低 | 規則引擎 |
| ReVanced patches | ~200 | 分析 Kotlin → 翻譯 JSON | 高 | 人工分析 |
| Xposed 模組 | 數百個 | 分析 Java → 翻譯 JSON | 高 | 人工分析 |

### 整合後的規則覆蓋

```mermaid
pie title 規則覆蓋來源（整合後）
    "域名清單（AdGuard/EasyList）" : 50000
    "通用 SDK 規則（內建）" : 25
    "App 專屬規則（社群）" : 200
    "Layer 3 用戶回報" : 100
```

**關鍵洞察：規則引擎是槓桿。** 只要實作 `URL_MATCHES`，AdSweep 的規則量就從 25 條跳到 5 萬+ 條。這不是從零累積，而是站在 AdGuard/EasyList 十幾年的成果上。

### 兩層攔截的互補

```mermaid
graph TD
    subgraph "域名層（URL_MATCHES）"
        U1["攔截所有廣告網路請求"]
        U2["覆蓋廣：5 萬+ 域名"]
        U3["但不能阻止 SDK 初始化"]
    end

    subgraph "SDK 層（現有 Hook）"
        S1["攔截 SDK 方法呼叫"]
        S2["覆蓋精：25 條精確規則"]
        S3["能阻止 SDK 初始化、UI 佔用"]
    end

    U1 --> R["雙層攔截 = 最全面"]
    S1 --> R

    U3 -.->|"互補"| S1
    S2 -.->|"互補"| U1
```

域名層擋住**網路請求**（廣告素材載入），SDK 層擋住**本地行為**（SDK 初始化、UI 佔用、追蹤）。兩層加起來比任何單一方案都全面。

## 技術護城河

```mermaid
graph TB
    subgraph "容易被複製"
        E1["inject.py 工具"]
        E2["Hook 引擎（LSPlant）"]
        E3["JSON 規則格式"]
    end

    subgraph "難以被複製"
        H1["規則資料庫<br>（需要持續逆向分析）"]
        H2["AI 分析引擎<br>（smali → 規則）"]
        H3["社群規模<br>（用戶 + 貢獻者）"]
        H4["品牌信任<br>（開源 + 透明）"]
    end

    E1 -.->|"開源反而是優勢<br>（建立信任）"| H4
    H1 --> |"Network Effect"| H3
    H3 --> |"更多貢獻"| H1
```

**核心護城河不是程式碼，是規則庫 + AI + 社群。**

## 平台擴展可能性

| 方向 | 可行性 | 優先順序 | 說明 |
|------|--------|---------|------|
| **Android Manager App** | 高 | P1 | 類似 ReVanced Manager，手機上操作 |
| **Web 平台** | 高 | P1 | 上傳 APK → 下載 patched APK |
| **桌面 GUI** | 中 | P2 | Electron/Tauri 包裝 |
| **瀏覽器擴充** | 低 | P3 | 網頁廣告已有 uBlock，不需要 |
| **iOS** | 極低 | - | 完全不同技術棧，iOS 不允許 sideload |
| **企業 MDM 整合** | 中 | P3 | 公司統一管理 |

## 行動項目

### 短期（1-3 個月）

1. 建立 `adsweep-rules` GitHub repo，放入 10+ App 規則
2. 實作規則引擎（ConditionalCallback + URL_MATCHES）
3. 整合 AdGuard 域名清單
4. 寫技術文章，在社群推廣

### 中期（3-6 個月）

5. 開發 Web 平台 MVP
6. 實作 `--discover` 自動規則產出
7. AI 分析 POC（Claude API 分析 smali）
8. 規則庫擴展到 50+ App

### 長期（6-12 個月）

9. Android Manager App
10. AI 分析引擎正式版
11. 企業版
12. 營收模式驗證
