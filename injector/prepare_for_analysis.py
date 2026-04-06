#!/usr/bin/env python3
"""
AdSweep Analysis Preparation — Extract key smali files for AI analysis.

Scans a decompiled APK and produces a summary of ad-related classes,
ready to be analyzed by Claude Code via /analyze-app command.

Usage:
    python prepare_for_analysis.py /path/to/decompiled [-o summary.txt]
"""
import argparse
import glob
import os
import re
import sys

# Keywords that indicate ad-related code
AD_KEYWORDS = [
    "ad", "ads", "banner", "interstitial", "rewarded", "native",
    "mediation", "applovin", "admob", "ironsource", "vungle",
    "facebook.ads", "chartboost", "inmobi", "mopub", "adcolony",
    "coupang.ads", "kakao.adfit", "startapp", "bytedance",
    "gdpr", "consent", "ump", "analytics", "crashlytics",
    "firebase", "tracking", "device.id", "remote.config",
]

# Keywords to exclude (false positives)
EXCLUDE_KEYWORDS = [
    "adapter", "address", "addition", "loading", "reading",
    "thread", "shadow", "padding", "broadcast", "download",
]


def find_ad_classes(decompiled_dir: str) -> dict:
    """Find all ad-related smali files, grouped by category."""
    categories = {
        "ad_sdk": [],       # Known ad SDK classes
        "app_ad": [],       # App's own ad wrapper classes
        "analytics": [],    # Analytics/tracking
        "consent": [],      # GDPR/consent
        "signature": [],    # Signature/license checks
        "network": [],      # Network/API classes
    }

    smali_dirs = sorted(glob.glob(os.path.join(decompiled_dir, "smali*")))

    for smali_dir in smali_dirs:
        for smali_file in glob.glob(os.path.join(smali_dir, "**/*.smali"), recursive=True):
            rel_path = os.path.relpath(smali_file, smali_dir)
            class_path = rel_path.lower()

            # Skip common framework classes
            if any(class_path.startswith(p) for p in [
                "android/", "androidx/", "kotlin/", "kotlinx/",
                "com/google/android/gms/common/", "com/google/android/material/",
            ]):
                continue

            # Check if it's an ad SDK class
            if any(p in class_path for p in [
                "com/google/android/gms/ads/", "com/applovin/",
                "com/facebook/ads/", "com/ironsource/", "com/unity3d/ads/",
                "com/vungle/", "com/adcolony/", "com/chartboost/",
                "com/inmobi/", "com/mopub/", "com/kakao/adfit/",
                "com/bytedance/sdk/openadsdk/",
            ]):
                categories["ad_sdk"].append(smali_file)
                continue

            # Read file to check content
            try:
                with open(smali_file, "r", errors="ignore") as f:
                    first_lines = f.read(2000)  # Only read first 2KB
            except:
                continue

            name_lower = os.path.basename(smali_file).lower()

            # Categorize
            if any(k in name_lower for k in ["signature", "license", "verify", "check"]):
                categories["signature"].append(smali_file)
            elif any(k in name_lower for k in ["analytic", "crashlytics", "firebase", "tracking"]):
                categories["analytics"].append(smali_file)
            elif any(k in name_lower for k in ["consent", "gdpr", "ump", "privacy"]):
                categories["consent"].append(smali_file)
            elif any(k in name_lower for k in ["retrofit", "okhttp", "api", "remote", "cloud"]):
                categories["network"].append(smali_file)
            elif any(k in class_path for k in ["ad/", "/ads/", "ad.smali"]):
                # Exclude false positives
                if not any(ex in name_lower for ex in EXCLUDE_KEYWORDS):
                    categories["app_ad"].append(smali_file)

    return categories


def extract_methods(smali_file: str) -> list:
    """Extract public/static method signatures from a smali file."""
    methods = []
    try:
        with open(smali_file, "r", errors="ignore") as f:
            content = f.read()

        for match in re.finditer(r'\.method\s+(public|private|protected)\s+(?:static\s+)?(?:final\s+)?(\w+)\(([^)]*)\)(\S+)', content):
            access = match.group(1)
            name = match.group(2)
            params = match.group(3)
            ret = match.group(4)
            if name not in ("<init>", "<clinit>", "toString", "hashCode", "equals"):
                methods.append({
                    "access": access,
                    "name": name,
                    "params": params,
                    "return": ret,
                })
    except:
        pass
    return methods


def generate_summary(decompiled_dir: str) -> str:
    """Generate a text summary for AI analysis."""
    lines = []

    # App info
    yml_path = os.path.join(decompiled_dir, "apktool.yml")
    if os.path.exists(yml_path):
        with open(yml_path, "r") as f:
            for line in f:
                if "renameManifestPackage:" in line or "versionName:" in line or "versionCode:" in line:
                    lines.append(line.strip())
    lines.append("")

    # Find ad classes
    categories = find_ad_classes(decompiled_dir)

    for cat_name, files in categories.items():
        if not files:
            continue
        lines.append(f"=== {cat_name} ({len(files)} files) ===")

        # Show top 15 files with their methods
        for f in files[:15]:
            rel = os.path.relpath(f, decompiled_dir)
            class_name = rel.replace("/", ".").replace(".smali", "")
            # Remove smali_classesN prefix
            for prefix in ["smali.", "smali_classes2.", "smali_classes3.", "smali_classes4.", "smali_classes5.", "smali_classes6.", "smali_classes7."]:
                class_name = class_name.removeprefix(prefix)

            methods = extract_methods(f)
            if methods:
                lines.append(f"\n  {class_name}")
                for m in methods[:10]:
                    lines.append(f"    {m['access']} {m['return']} {m['name']}({m['params']})")
                if len(methods) > 10:
                    lines.append(f"    ... +{len(methods) - 10} more methods")

        if len(files) > 15:
            lines.append(f"  ... +{len(files) - 15} more files")
        lines.append("")

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Prepare decompiled APK for AI analysis")
    parser.add_argument("decompiled_dir", help="Path to decompiled APK directory")
    parser.add_argument("-o", "--output", help="Output summary file (default: stdout)")
    args = parser.parse_args()

    if not os.path.isdir(args.decompiled_dir):
        print(f"[!] Not a directory: {args.decompiled_dir}")
        sys.exit(1)

    print(f"[*] Analyzing {args.decompiled_dir}...")
    summary = generate_summary(args.decompiled_dir)

    if args.output:
        with open(args.output, "w") as f:
            f.write(summary)
        print(f"[+] Summary saved to {args.output}")
    else:
        print(summary)


if __name__ == "__main__":
    main()
