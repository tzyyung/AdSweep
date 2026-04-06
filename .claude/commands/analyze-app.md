# Analyze App for AdSweep Rules

You are an Android reverse engineering expert. Analyze the decompiled APK and produce AdSweep hook rules.

## Input

The user will provide a path to a decompiled APK directory (from `apktool d -r`).

If no path is given, use: `$ARGUMENTS`

## Analysis Steps

### Step 1: Identify the App

Read `apktool.yml` to get the package name and version.

### Step 2: Run Layer 2 Scanner

```bash
cd /Users/anson/incrte/AdSweep/injector && python3 -c "
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

### Step 3: Find App-Specific Ad Code

Search for the App's own ad wrapper classes:

```bash
grep -rn "ad\|Ad\|AD" $ARGUMENTS/smali*/com/ --include="*.smali" -l | grep -iv "adapter\|add\|addr\|load\|read\|thread\|shadow\|pad" | head -20
```

### Step 4: Analyze Key Classes

For each ad-related class found:
1. Read the smali file
2. Identify methods that load/show/init ads
3. Check method signatures (return type, parameters)
4. Determine the correct action (BLOCK_RETURN_VOID, BLOCK_RETURN_TRUE, etc.)

### Step 5: Find Signature/License Checks

```bash
grep -rn "signature\|PackageManager\|getPackageInfo\|checkSignature" $ARGUMENTS/smali*/ --include="*.smali" -l | head -10
```

### Step 6: Find Analytics/Tracking

```bash
grep -rn "analytics\|Analytics\|Crashlytics\|firebase\|Firebase" $ARGUMENTS/smali*/ --include="*.smali" -l | head -10
```

### Step 7: Find GDPR/Consent

```bash
grep -rn "consent\|Consent\|GDPR\|gdpr\|UserMessagingPlatform" $ARGUMENTS/smali*/ --include="*.smali" -l | head -10
```

### Step 8: Produce Rules

Output a complete `rules.json` file with:
- All discovered ad SDK methods (from Step 2-4)
- Signature/license bypass rules (from Step 5)
- Analytics blocking rules (from Step 6)
- GDPR/consent bypass rules (from Step 7)

Follow these guidelines from doc/RULES.md:
- Hook the lowest-level method (not wrappers that call callbacks)
- Don't hook methods that receive callbacks (will break the flow)
- Check parent classes for method definitions
- Use correct return types (void→BLOCK_RETURN_VOID, boolean→BLOCK_RETURN_TRUE/FALSE)

### Step 9: Save and Report

Save the rules to `injector/rules/<package_name>.json` and update the analysis report.

## Output Format

```json
{
  "version": 1,
  "rules": [
    {
      "id": "<app>-<purpose>",
      "className": "com.example.ClassName",
      "methodName": "methodName",
      "action": "BLOCK_RETURN_VOID",
      "enabled": true,
      "source": "MANUAL",
      "sdkName": "Description",
      "notes": "Why this rule is needed"
    }
  ]
}
```
