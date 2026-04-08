# Analyze App for AdSweep Rules

分析反編譯的 APK，產出 AdSweep hook 規則。

## Input

用戶提供 decompiled APK 目錄路徑（from `apktool d -r`）。
如未提供，使用: `$ARGUMENTS`

## Step 1: 識別 App

讀取 `apktool.yml` 取得 package name 和 version。

## Step 2: Smart Scan

```bash
cd "$(git rev-parse --show-toplevel)/injector" && python3 -c "
from scanner import scan, generate_suggested_rules
report = scan('$ARGUMENTS')
suggested = generate_suggested_rules('$ARGUMENTS', report)
print(f'SDKs: {len(report[\"found_sdks\"])}')
for sdk in report['found_sdks']:
    print(f'  {sdk[\"sdk\"]}: {sdk[\"package\"]} ({sdk[\"smali_files\"]} files)')
print(f'Suggested rules: {len(suggested)}')
for r in suggested[:20]:
    print(f'  {r[\"className\"].split(\".\")[-1]}.{r[\"methodName\"]}')
"
```

## Step 3: App-Specific 廣告代碼

```bash
grep -rn "ad\|Ad\|AD" $ARGUMENTS/smali*/com/ --include="*.smali" -l | grep -iv "adapter\|add\|addr\|load\|read\|thread\|shadow\|pad" | head -20
```

## Step 4: 分析關鍵類別

每個廣告相關的 class：
1. 讀 smali，找 load/show/init ads 的方法
2. 確認 method signature（return type、parameters）
3. 選擇正確的 action（BLOCK_RETURN_VOID / BLOCK_RETURN_TRUE / BLOCK_RETURN_FALSE）

**重要**：
- Hook 最底層方法，不要 hook wrapper
- 不要 hook 接收 callback 的方法（會中斷流程）
- 檢查 parent class，LSPlant 無法攔截 abstract method 的 subclass 實現 → 必須 hook concrete class
- 如果 App 需要網路功能，不要攔截 URL.openConnection（加一條 disabled rule 覆蓋 common rule）

## Step 5: 簽名/授權驗證

```bash
grep -rn "signature\|PackageManager\|getPackageInfo\|checkSignature\|tamper\|integrity" $ARGUMENTS/smali*/ --include="*.smali" -l | head -10
```

通用 SPOOF_SIGNATURE 已在 common rules 中，只需額外處理 app-specific 驗證。

## Step 6: Analytics/Tracking

```bash
grep -rn "analytics\|Analytics\|Crashlytics\|firebase\|Firebase" $ARGUMENTS/smali*/ --include="*.smali" -l | head -10
```

## Step 7: GDPR/Consent

```bash
grep -rn "consent\|Consent\|GDPR\|gdpr\|UserMessagingPlatform" $ARGUMENTS/smali*/ --include="*.smali" -l | head -10
```

## Step 8: 加固偵測

```bash
# 偵測是否有加固/加殼
grep -rn "ijiami\|bangcle\|360\|qihoo\|secneo\|tencent.*legu\|baidu.*protect\|DexProtector\|libart-protect" $ARGUMENTS/lib/ $ARGUMENTS/assets/ --include="*.so" -l 2>/dev/null | head -5
ls $ARGUMENTS/lib/*/lib*.so 2>/dev/null | head -20
```

如果偵測到加固，停止分析並報告。加固 APK 無法靜態分析。

## Step 9: 產出 Rules JSON

存到 `injector/rules/<app_short_name>.json`，格式：

```json
{
  "version": 1,
  "rules": [
    {
      "id": "<app>-<purpose>",
      "className": "com.example.ClassName",
      "methodName": "methodName",
      "paramTypes": ["param.Type"],
      "action": "BLOCK_RETURN_VOID",
      "enabled": true,
      "source": "APP_SPECIFIC",
      "sdkName": "SDK or Feature Name",
      "description": "Human-readable description of what this rule does",
      "notes": "Technical details for developers"
    }
  ]
}
```

**必填欄位**：id, className, methodName, action, enabled, source, sdkName, description
**注意**：source 一律用 `APP_SPECIFIC`（不是 MANUAL）

## Step 10: 報告

輸出摘要：
- App 名稱 + package + version
- 發現的 SDK 數量
- 產出的規則數量（列出每條）
- 是否有加固
- 是否需要額外的 app-specific 簽名繞過
- 建議的下一步（`/patch-and-test`）
