# AdSweep — Claude Code 指南

## 專案簡介

通用 Android 廣告攔截模組。注入任意 APK，hook 廣告 SDK 方法 + 攔截廣告域名 + WebView userscript 引擎。

## Slash Commands（App 分析流程）

完整分析一個 App 的流程：

1. `/analyze-app <decompiled_dir>` — 掃描 APK、產出 rules JSON
2. `/patch-and-test <apk_path> <rules_name>` — 注入、安裝、驗證
3. `/update-docs <app_name>` — 更新文件、commit

## 專案結構

```
core/                  — Android library（注入到目標 App）
  src/main/java/com/adsweep/
    hook/              — HookManager, HookEngine (LSPlant JNI)
    engine/            — RuleParser, DomainMatcher, actions/
    rules/             — Rule model, RuleStore
    webview/           — UserScriptEngine (Greasemonkey)
  src/main/assets/     — adsweep_rules_common.json, adsweep_domains.txt
injector/              — Python 注入工具
  inject.py            — 主 pipeline
  patcher.py           — smali 注入（reflection-based init）
  packager.py          — 重建 APK + 簽名提取
  scanner.py           — SDK 偵測 + 規則建議
  rules/               — App-specific 規則 JSON
manager/               — On-device Manager App
prebuilt/              — 預編譯的 classes.dex + assets
doc/                   — 技術文件
```

## 關鍵技術約束

- **反射式注入**：patcher.py 用 `Class.forName().getMethod().invoke()` 注入 init call，避免 65536 method reference limit
- **LSPlant 限制**：無法攔截 abstract method 的 subclass 實現，必須 hook concrete class
- **加固 APK**：ijiami、360 jiagu 等加固 APK 無法靜態分析，直接跳過
- **Disabled rule 覆蓋**：app-specific 的 disabled rule 會阻止同 method 的 common rule 生效（例如某些 App 需要 URL.openConnection）
- **SPOOF_SIGNATURE**：走 RuleBasedCallback（需要 callOriginal），解析 concrete PackageManager class 用 `context.getPackageManager().getClass()`

## 規則 JSON 慣例

```json
{
  "id": "<app>-<purpose>",
  "className": "com.example.Class",
  "methodName": "method",
  "paramTypes": ["param.Type"],
  "action": "BLOCK_RETURN_VOID",
  "enabled": true,
  "source": "APP_SPECIFIC",
  "sdkName": "SDK or Feature Name",
  "description": "Human-readable 說明這條規則做什麼"
}
```

- `source` 一律用 `APP_SPECIFIC`（不是 MANUAL）
- `description` 必填，寫人看得懂的說明
- `id` 格式：`<app短名>-<用途>`

## 建置

```bash
# Core library → DEX
./gradlew :core:assembleDebug
# d8 轉換 → prebuilt/classes.dex（inject.py 會用到）

# 注入 APK
cd injector && python3 inject.py --apk target.apk --rules rules/app.json

# Manager App
./gradlew :manager:assembleDebug
```

## 測試

- 改 Java/規則後先跑 `./gradlew :core:assembleDebug` 確認編譯
- 改 patcher/packager 後用 PC 端 inject.py 測試，不要直接上手機
- Logcat tag：`AdSweep.*`
