# AdSweep 規則系統

## 規則類型

### 通用規則 (`adsweep_rules_common.json`)

內建在 `assets/` 裡，涵蓋 14 個常見廣告 SDK + Google UMP，共 25 條規則。所有注入的 App 都會載入。

### App 專屬規則 (`adsweep_rules_app.json`)

針對特定 App 的規則，透過 `--rules` 參數在注入時提供：

```bash
python inject.py --apk app.apk --rules rules/my_app.json
```

### 自動建議規則

注入時 Layer 2 掃描會自動產生 `suggested_rules.json`，可直接作為 App 規則使用：

```bash
# 第一次注入，查看建議規則
python inject.py --apk app.apk --keep-work
cat work/suggested_rules.json

# 確認後作為 App 規則使用
python inject.py --apk app.apk --rules work/suggested_rules.json
```

### 規則合併邏輯

```
生效規則 = (通用規則 ∪ App 專屬規則) - 白名單
```

App 專屬規則優先於通用規則（相同 class + method 時）。

## 規則格式

```json
{
  "version": 1,
  "rules": [
    {
      "id": "unique-rule-id",
      "className": "com.example.ads.AdManager",
      "methodName": "loadAd",
      "paramTypes": ["android.content.Context", "java.lang.String"],
      "action": "BLOCK_RETURN_VOID",
      "enabled": true,
      "source": "BUILTIN",
      "sdkName": "Example SDK",
      "notes": "Optional description"
    }
  ]
}
```

### 欄位說明

| 欄位 | 必填 | 說明 |
|------|------|------|
| `id` | 是 | 唯一識別碼 |
| `className` | 是 | 完整 Java class 名稱 |
| `methodName` | 是 | 方法名稱 |
| `paramTypes` | 否 | 方法參數型別陣列。省略時匹配同名的第一個方法 |
| `action` | 是 | 攔截行為（見下表） |
| `enabled` | 否 | 是否啟用（預設 true） |
| `source` | 否 | 來源：BUILTIN / MANUAL / LAYER2_SCAN / LAYER3_USER |
| `sdkName` | 否 | 人類可讀的 SDK 名稱（UI 用） |
| `notes` | 否 | 備註 |

### Action 類型

| Action | 回傳值 | 適用場景 |
|--------|--------|---------|
| `BLOCK_RETURN_VOID` | null | void 方法（廣告初始化、載入） |
| `BLOCK_RETURN_NULL` | null | 回傳 Object 的方法 |
| `BLOCK_RETURN_TRUE` | true | 繞過檢查（如 premium 判斷、consent 已取得） |
| `BLOCK_RETURN_FALSE` | false | 繞過檢查（如簽名驗證） |
| `BLOCK_RETURN_ZERO` | 0 | 回傳數值的方法 |
| `BLOCK_RETURN_EMPTY_STRING` | "" | 回傳字串的方法（如裝置 ID） |

## 寫規則的注意事項

### Hook 是全局的

Hook 一個方法後，**所有呼叫點**都會被攔截。如果一個方法在不同位置有不同用途（例如 `Globals.e()` 在 Intro 和 Main 裡的分支方向不同），直接 Hook 可能導致異常。

解決方式：Hook 更底層的方法。例如 `e()` 內部呼叫 `k()` 再取反，Hook `k()` 讓 `e()` 的邏輯自然正確。

### 不要 Hook callback 觸發方法

如果方法 A 接收一個 callback 參數，noop A 會導致 callback 永遠不觸發，後續流程卡住。

錯誤：Hook `GDPRConsent.n(activity, listener)` → listener 永遠不回調 → App 卡住
正確：Hook `GDPRConsent.l()` 回傳 true → 上層跳過整個 consent 流程

### paramTypes 省略時的行為

如果不指定 `paramTypes`，HookManager 會匹配**同名的第一個方法**。如果同名方法有多個重載，建議指定 paramTypes 避免 Hook 錯方法。

## 自訂規則教學

### 1. 找到目標方法

用 apktool 反編譯 APK，搜尋目標 class 的 smali 檔案：

```bash
apktool d -r target.apk -o decompiled
grep -r "loadAd\|showAd\|initAd" decompiled/smali*/
```

### 2. 確認方法簽名

查看 smali 檔案中的 `.method` 宣告：

```smali
.method public loadAd(Lcom/example/AdRequest;)V   ← void 方法
.method public static isValid()Z                    ← 回傳 boolean
.method public getId()Ljava/lang/String;            ← 回傳 String
```

注意：方法可能在父類別。例如 `AdView.loadAd` 實際定義在 `BaseAdView`。
檢查 `.super` 宣告來找父類別。

### 3. 寫規則

```json
{
  "id": "my-custom-rule",
  "className": "com.example.MyClass",
  "methodName": "loadAd",
  "paramTypes": ["com.example.AdRequest"],
  "action": "BLOCK_RETURN_VOID",
  "enabled": true,
  "source": "MANUAL",
  "sdkName": "Custom"
}
```

### 4. 注入

```bash
python inject.py --apk target.apk --rules my_rules.json
```

## 範例：Money Manager 規則

見 `injector/rules/money_manager.json`：

| 規則 | Class | Method | Action | 用途 |
|------|-------|--------|--------|------|
| 簽名驗證 | Main | C2 | BLOCK_RETURN_FALSE | 跳過簽名檢查 |
| Premium | Globals | k | BLOCK_RETURN_TRUE | isPremium 回傳 true，e()=!k()=false 跳過購買 |
| License | Globals | j | BLOCK_RETURN_FALSE | 跳過授權檢查 |
| Analytics | RbAnalyticAgent | a | BLOCK_RETURN_VOID | 封堵追蹤 |
| Device ID | DeviceAdId | b | BLOCK_RETURN_EMPTY_STRING | 不回傳裝置 ID |
| Remote API | RbRemoteRequest | i | BLOCK_RETURN_VOID | 封堵雲端請求 |
| GDPR | GDPRConsent | l | BLOCK_RETURN_TRUE | 跳過 consent 彈窗（回傳已取得 consent） |
| UMP Load | UserMessagingPlatform | loadAndShowConsentFormIfRequired | BLOCK_RETURN_VOID | 封堵 Google UMP |
| UMP Form | UserMessagingPlatform | loadConsentForm | BLOCK_RETURN_VOID | 封堵 Google UMP |

## 通用規則清單

### AdMob
- `BaseAdView.loadAd` — Banner 廣告載入（注意：不是 AdView，方法在父類別）
- `InterstitialAd.load` — 插頁廣告載入
- `RewardedAd.load` — 獎勵廣告載入
- `AdLoader.loadAd` — Native 廣告載入
- `MobileAds.initialize` — SDK 初始化

### AppLovin
- `AppLovinSdk.initialize` — SDK 初始化
- `MaxAdView.loadAd` — Banner 載入
- `MaxInterstitialAd.loadAd` — 插頁載入

### Facebook Audience Network
- `AdView.loadAd` — Banner 載入
- `InterstitialAd.loadAd` — 插頁載入

### IronSource
- `IronSource.loadISDemandOnlyInterstitial` — 插頁載入
- `IronSource.loadISDemandOnlyRewardedVideo` — 獎勵載入

### Kakao AdFit
- `BannerAdView.loadAd` — Banner 載入

### Google UMP
- `UserMessagingPlatform.loadAndShowConsentFormIfRequired` — GDPR consent
- `UserMessagingPlatform.loadConsentForm` — consent 表單

### 其他（App 沒有時自動跳過）
Unity Ads, Vungle, AdColony, InMobi, Chartboost, MoPub, Coupang Ads, StartApp, Pangle
