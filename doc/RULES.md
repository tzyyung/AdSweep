# AdSweep 規則系統

## 規則類型

### 通用規則 (`adsweep_rules_common.json`)

內建在 `assets/` 裡，涵蓋 14 個常見廣告 SDK、23 條規則。所有注入的 App 都會載入。

### App 專屬規則 (`adsweep_rules_app.json`)

針對特定 App 的規則，透過 `--rules` 參數在注入時提供：

```bash
python inject.py --apk app.apk --rules rules/my_app.json
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
| `BLOCK_RETURN_TRUE` | true | 繞過檢查（如 premium 判斷） |
| `BLOCK_RETURN_FALSE` | false | 繞過檢查（如簽名驗證） |
| `BLOCK_RETURN_ZERO` | 0 | 回傳數值的方法 |
| `BLOCK_RETURN_EMPTY_STRING` | "" | 回傳字串的方法（如裝置 ID） |

## 自訂規則教學

### 1. 找到目標方法

用 apktool 反編譯 APK，搜尋目標 class 的 smali 檔案：

```bash
apktool d target.apk -o decompiled
grep -r "loadAd\|showAd\|initAd" decompiled/smali*/
```

### 2. 確認方法簽名

查看 smali 檔案中的 `.method` 宣告：

```smali
.method public loadAd(Lcom/example/AdRequest;)V   ← void 方法
.method public static isValid()Z                    ← 回傳 boolean
.method public getId()Ljava/lang/String;            ← 回傳 String
```

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
| Premium | Globals | e | BLOCK_RETURN_TRUE | 讓 Intro 正常初始化 |
| License | Globals | j | BLOCK_RETURN_FALSE | 跳過授權檢查 |
| Analytics | RbAnalyticAgent | a | BLOCK_RETURN_VOID | 封堵追蹤 |
| Device ID | DeviceAdId | b | BLOCK_RETURN_EMPTY_STRING | 不回傳裝置 ID |
| Remote API | RbRemoteRequest | i | BLOCK_RETURN_VOID | 封堵雲端請求 |

## 通用規則清單

### AdMob
- `BaseAdView.loadAd` — Banner 廣告載入
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

### 其他（App 沒有時自動跳過）
Unity Ads, Vungle, AdColony, InMobi, Chartboost, MoPub, Coupang Ads, StartApp, Pangle
