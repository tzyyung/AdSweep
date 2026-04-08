# Patch and Test App

注入 AdSweep 到 APK 並在裝置上驗證。

## Input

`$ARGUMENTS` 格式：`<apk_path> <rules_name>`
例如：`apks/com.example.apk callapp`

如果只給 APK 路徑，從檔名推斷 rules 名稱。

## Step 1: 注入

```bash
cd /Users/anson/incrte/AdSweep/injector
python3 inject.py --apk <apk_path> --rules rules/<rules_name>.json
```

如果注入失敗，分析錯誤並修復：
- **65536 method limit**: 確認 patcher.py 使用 reflection-based init
- **apktool rebuild 失敗**: 檢查 smali 語法
- **簽名錯誤**: 檢查 build-tools 版本

## Step 2: 安裝

```bash
adb install -r <patched_apk_path>
```

如果是 split APK，用 Manager App 流程：
```bash
N="-n com.adsweep.manager/.CommandReceiver"
adb shell am broadcast -a com.adsweep.manager.CMD_SELECT $N --es package <package>
adb shell am broadcast -a com.adsweep.manager.CMD_PATCH $N
adb shell am broadcast -a com.adsweep.manager.CMD_UNINSTALL $N
adb shell am broadcast -a com.adsweep.manager.CMD_INSTALL $N
```

## Step 3: 驗證

啟動 App 並監控 logcat：
```bash
adb logcat -s AdSweep:* | head -50
```

檢查項目：
- [ ] App 正常啟動，零 crash
- [ ] Hook 數量正確（`Initialization complete: N/M hooks installed`）
- [ ] 域名攔截載入（`domains: 99xxx`）
- [ ] 廣告不再顯示
- [ ] 簽名驗證通過（無 "tampered" 警告）
- [ ] App 核心功能正常運作

## Step 4: 故障排除

### Crash
1. 讀 logcat 完整 stack trace
2. 常見原因：
   - NullPointerException → 可能有 common rule 不該攔截此 App 的請求（加 disabled override rule）
   - ClassNotFoundException → 混淆後的 class name 不對
   - Hook 沒觸發 → 可能 hook 了 abstract method（改 hook concrete class）

### 簽名驗證失敗
1. 確認 `assets/adsweep_original_sig.bin` 存在於 patched APK
2. 確認 common rule `universal-signature-spoof` 有被載入
3. 如果有 app-specific 驗證，需要額外的 rule

## Step 5: 報告結果

回報：
- 安裝是否成功
- Hook 安裝數量
- 是否有 crash 或異常
- 廣告攔截效果
- 建議的下一步（`/update-docs`）
