"""
AdSweep Injector - Layer 2 static heuristic scanner.

Scans decompiled smali files for known ad SDK signatures and suspicious patterns.
"""
import os
import re
import json
import glob
from typing import List, Dict

# Known ad SDK package patterns and their associated rules
KNOWN_SDK_PATTERNS = {
    "AdMob": {
        "packages": ["com/google/android/gms/ads"],
        "confidence": 1.0,
    },
    "AppLovin": {
        "packages": ["com/applovin/sdk", "com/applovin/mediation"],
        "confidence": 1.0,
    },
    "Facebook Audience Network": {
        "packages": ["com/facebook/ads"],
        "confidence": 1.0,
    },
    "IronSource": {
        "packages": ["com/ironsource/mediationsdk"],
        "confidence": 1.0,
    },
    "Unity Ads": {
        "packages": ["com/unity3d/ads"],
        "confidence": 1.0,
    },
    "Vungle": {
        "packages": ["com/vungle/ads", "com/vungle/warren"],
        "confidence": 1.0,
    },
    "AdColony": {
        "packages": ["com/adcolony/sdk"],
        "confidence": 1.0,
    },
    "InMobi": {
        "packages": ["com/inmobi/sdk", "com/inmobi/ads"],
        "confidence": 1.0,
    },
    "Chartboost": {
        "packages": ["com/chartboost/sdk"],
        "confidence": 1.0,
    },
    "MoPub": {
        "packages": ["com/mopub"],
        "confidence": 1.0,
    },
    "Kakao AdFit": {
        "packages": ["com/kakao/adfit"],
        "confidence": 1.0,
    },
    "Coupang Ads": {
        "packages": ["com/coupang/ads"],
        "confidence": 1.0,
    },
    "StartApp": {
        "packages": ["com/startapp/sdk"],
        "confidence": 1.0,
    },
    "Pangle (ByteDance)": {
        "packages": ["com/bytedance/sdk/openadsdk"],
        "confidence": 1.0,
    },
    "Amazon Device Ads": {
        "packages": ["com/amazon/device/ads"],
        "confidence": 1.0,
    },
    "Flurry": {
        "packages": ["com/flurry/android"],
        "confidence": 1.0,
    },
    "Vpon": {
        "packages": ["com/vpon/ads"],
        "confidence": 1.0,
    },
    "Nend": {
        "packages": ["net/nend/android"],
        "confidence": 1.0,
    },
    "Yandex Mobile Ads": {
        "packages": ["com/yandex/mobile/ads"],
        "confidence": 1.0,
    },
    "MobFox": {
        "packages": ["com/mobfox/sdk"],
        "confidence": 1.0,
    },
    "Millennial Media": {
        "packages": ["com/millennialmedia"],
        "confidence": 1.0,
    },
    "Tapjoy": {
        "packages": ["com/tapjoy"],
        "confidence": 1.0,
    },
    "Fyber": {
        "packages": ["com/fyber"],
        "confidence": 1.0,
    },
    "Smaato": {
        "packages": ["com/smaato/sdk"],
        "confidence": 1.0,
    },
    "Digital Turbine": {
        "packages": ["com/digitalturbine"],
        "confidence": 1.0,
    },
    "Ogury": {
        "packages": ["com/ogury"],
        "confidence": 1.0,
    },
    "Mintegral": {
        "packages": ["com/mbridge/msdk"],
        "confidence": 1.0,
    },
}

# Heuristic patterns for unknown ad code
HEURISTIC_PATTERNS = [
    {
        "name": "ad_inheritance",
        "description": "Class extends a known ad base class",
        "regex": r"\.super\s+L[^;]*(?:AdView|BannerAd|InterstitialAd|NativeAd|RewardedAd|AdListener);",
        "confidence": 0.8,
    },
    {
        "name": "ad_string_admob_id",
        "description": "Contains AdMob app/unit ID format",
        "regex": r'const-string [vp]\d+, "ca-app-pub-\d+[~/]\d+"',
        "confidence": 0.9,
    },
    {
        "name": "ad_string_generic",
        "description": "Contains ad-related string constants",
        "regex": r'const-string [vp]\d+, ".*?(?:adunit|ad_unit|adUnitId|interstitial_ad|banner_ad|rewarded_ad).*?"',
        "confidence": 0.6,
    },
    {
        "name": "webview_ad_combo",
        "description": "WebView.loadUrl + timer in same class (common ad pattern)",
        "regex": None,  # Multi-pattern, handled separately
        "multi_patterns": [
            r"invoke-virtual.*Landroid/webkit/WebView;->loadUrl",
            r"Landroid/os/CountDownTimer",
        ],
        "confidence": 0.7,
    },
]


def scan(decompiled_dir: str) -> Dict:
    """
    Scan a decompiled APK directory for ad-related code.
    Returns a scan report dict.
    """
    print("[*] Running Layer 2 static scan...")

    report = {
        "found_sdks": [],
        "heuristic_findings": [],
        "summary": {},
    }

    smali_dirs = sorted(glob.glob(os.path.join(decompiled_dir, "smali*")))

    # Phase 1: Check for known SDK packages
    for sdk_name, sdk_info in KNOWN_SDK_PATTERNS.items():
        for package_path in sdk_info["packages"]:
            for smali_dir in smali_dirs:
                full_path = os.path.join(smali_dir, package_path)
                if os.path.isdir(full_path):
                    smali_count = len(glob.glob(os.path.join(full_path, "**/*.smali"), recursive=True))
                    report["found_sdks"].append({
                        "sdk": sdk_name,
                        "package": package_path.replace("/", "."),
                        "path": full_path,
                        "smali_files": smali_count,
                        "confidence": sdk_info["confidence"],
                    })
                    print(f"  [SDK] {sdk_name}: {package_path} ({smali_count} files)")
                    break  # Found this SDK, no need to check other packages

    # Phase 2: Heuristic scan of all smali files
    for smali_dir in smali_dirs:
        for smali_file in glob.glob(os.path.join(smali_dir, "**/*.smali"), recursive=True):
            scan_file_heuristics(smali_file, smali_dir, report)

    # Summary
    report["summary"] = {
        "known_sdks_found": len(report["found_sdks"]),
        "heuristic_findings": len(report["heuristic_findings"]),
        "sdk_names": [s["sdk"] for s in report["found_sdks"]],
    }

    print(f"\n[+] Scan complete: {report['summary']['known_sdks_found']} known SDKs, "
          f"{report['summary']['heuristic_findings']} heuristic findings")

    return report


def scan_file_heuristics(smali_path: str, smali_root: str, report: Dict):
    """Apply heuristic patterns to a single smali file."""
    try:
        with open(smali_path, "r", errors="ignore") as f:
            content = f.read()
    except Exception:
        return

    relative_path = os.path.relpath(smali_path, smali_root)
    class_name = relative_path.replace("/", ".").replace(".smali", "")

    for pattern in HEURISTIC_PATTERNS:
        if pattern.get("multi_patterns"):
            # All patterns must match in the same file
            if all(re.search(p, content) for p in pattern["multi_patterns"]):
                report["heuristic_findings"].append({
                    "pattern": pattern["name"],
                    "description": pattern["description"],
                    "class": class_name,
                    "file": smali_path,
                    "confidence": pattern["confidence"],
                })
        elif pattern["regex"]:
            match = re.search(pattern["regex"], content)
            if match:
                report["heuristic_findings"].append({
                    "pattern": pattern["name"],
                    "description": pattern["description"],
                    "class": class_name,
                    "file": smali_path,
                    "matched": match.group(0)[:100],
                    "confidence": pattern["confidence"],
                })


def generate_suggested_rules(decompiled_dir: str, report: Dict) -> List[Dict]:
    """
    Generate suggested hook rules from scan results.
    Scans found SDK packages for common ad-loading method patterns.
    """
    suggested = []
    smali_dirs = sorted(glob.glob(os.path.join(decompiled_dir, "smali*")))

    # Method name patterns that are likely ad-loading entry points
    AD_METHOD_PATTERNS = [
        (r"\.method\s+public\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(\w*(?:load|show|init|start|fetch|request|display|present)\w*)\(", "BLOCK_RETURN_VOID"),
        (r"\.method\s+public\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(\w*(?:canShow|isReady|isAvailable|isEnabled|hasInterstitial|hasRewardedVideo|isLoaded)\w*)\(", "BLOCK_RETURN_FALSE"),
    ]

    for sdk_info in report["found_sdks"]:
        package_path = sdk_info["package"].replace(".", "/")

        for smali_dir in smali_dirs:
            sdk_dir = os.path.join(smali_dir, package_path)
            if not os.path.isdir(sdk_dir):
                continue

            # Scan top-level smali files in SDK package for ad methods
            for smali_file in glob.glob(os.path.join(sdk_dir, "*.smali")):
                class_name_path = os.path.relpath(smali_file, smali_dir)
                class_name = class_name_path.replace("/", ".").replace(".smali", "")

                try:
                    with open(smali_file, "r", errors="ignore") as f:
                        content = f.read()
                except Exception:
                    continue

                for pattern, default_action in AD_METHOD_PATTERNS:
                    for match in re.finditer(pattern, content):
                        method_name = match.group(1)
                        # Skip constructors, getters, setters, listeners (only for VOID actions)
                        if default_action == "BLOCK_RETURN_VOID":
                            if method_name.startswith(("get", "set", "is", "has", "on", "add", "remove")):
                                continue
                        if method_name in ("toString", "hashCode", "equals", "clone"):
                            continue

                        rule_id = f"scan-{sdk_info['sdk'].lower().replace(' ', '-')}-{class_name.split('.')[-1]}-{method_name}"
                        suggested.append({
                            "id": rule_id,
                            "className": class_name,
                            "methodName": method_name,
                            "action": default_action,
                            "enabled": True,
                            "source": "LAYER2_SCAN",
                            "sdkName": sdk_info["sdk"],
                            "confidence": sdk_info["confidence"],
                        })

    # Deduplicate
    seen = set()
    unique = []
    for rule in suggested:
        key = f"{rule['className']}.{rule['methodName']}"
        if key not in seen:
            seen.add(key)
            unique.append(rule)

    return unique


def save_suggested_rules(rules: List[Dict], output_path: str):
    """Save suggested rules as a JSON file ready to use with --rules."""
    data = {"version": 1, "rules": rules}
    with open(output_path, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"[+] Suggested rules saved to {output_path} ({len(rules)} rules)")


def save_report(report: Dict, output_path: str):
    """Save scan report to JSON file."""
    with open(output_path, "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"[+] Scan report saved to {output_path}")
