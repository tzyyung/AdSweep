# Update Documentation for Analyzed App

更新 README 和 DESIGN.md，加入新分析的 App 案例，然後 commit。

## Input

`$ARGUMENTS`：App 名稱（例如 `CallApp`）

如果未提供，從最近的 git changes 或 rules/ 目錄推斷。

## Step 1: 收集資訊

從以下來源收集 App 資訊：
- `injector/rules/<app>.json` — 規則數量和類型
- 最近的 logcat 或對話記錄 — hook 數量、攔截效果
- 之前分析的結果

## Step 2: 更新 README.md

在 `## 實際效果` 區塊加入新 App 的 showcase：

```markdown
### AppName（簡短描述 — 技術亮點）

| Before | After (AdSweep) |
|:------:|:---------------:|
| ![Before](docs/showcase/<app>_before.png) | ![After](docs/showcase/<app>_after.png) |
| 用戶可見的問題描述 | 修復後的效果 |
| 技術方法簡述 | N 條 rules · M hooks |
```

**注意**：README showcase 只寫用戶看得懂的描述，不要寫技術細節（65536 method limit、apktool 遺失檔案等不要出現在 README）。

## Step 3: 更新 doc/DESIGN.md

在 `## App 案例分析` 區塊加入詳細技術分析表：

```markdown
### AppName

| 項目 | 說明 |
|------|------|
| Package | com.example.app |
| Version | x.y.z |
| 混淆 | R8 / ProGuard / 無 |
| 加固 | 無 / ijiami / 360 |
| 廣告 SDK | AdMob, ... |
| 攔截方式 | SDK Hook / WebView / 域名 |
| 特殊處理 | 簽名繞過 / 65K fix / ... |
| 規則數 | N 條 app-specific + M 條 common |
| Hook 數 | 總共 N hooks |
| 結果 | 零 crash · 廣告完全移除 |
```

技術細節（遇到的問題、解法、架構發現）放在這裡。

## Step 4: 更新實測結果

README `## 實測結果` 區塊，加入新 App 的測試結果摘要。

## Step 5: Commit

```bash
git add README.md doc/DESIGN.md injector/rules/<app>.json
git commit -m "Add <AppName> case study and rules

- N app-specific rules for <brief description>
- Update README showcase and DESIGN.md analysis

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

詢問用戶是否要 push。
