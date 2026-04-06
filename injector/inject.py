#!/usr/bin/env python3
"""
AdSweep Injector — One-command APK ad-blocker injection.

Usage:
    python inject.py --apk target.apk [--output patched.apk] [--skip-scan] [--keystore path]

This script:
1. Decompiles the APK with apktool
2. Runs Layer 2 static scan for ad SDKs (optional)
3. Patches Application.onCreate() to load AdSweep
4. Copies hook engine (DEX + native libs) into the APK
5. Repackages, aligns, and signs the APK
"""
import argparse
import os
import shutil
import sys
import tempfile

from config import validate_tools
from decompiler import decompile
from scanner import scan, save_report, generate_suggested_rules, save_suggested_rules
from patcher import patch
from packager import package_apk


def main():
    parser = argparse.ArgumentParser(
        description="AdSweep — Inject universal ad blocker into any APK"
    )
    parser.add_argument("--apk", required=True, help="Path to the target APK")
    parser.add_argument("--output", help="Output path for patched APK (default: <name>_adsweep.apk)")
    parser.add_argument("--skip-scan", action="store_true", help="Skip Layer 2 static scan")
    parser.add_argument("--rules", help="Path to app-specific rules JSON file")
    parser.add_argument("--keystore", help="Path to signing keystore")
    parser.add_argument("--ks-pass", help="Keystore password")
    parser.add_argument("--key-alias", help="Key alias")
    parser.add_argument("--key-pass", help="Key password")
    parser.add_argument("--work-dir", help="Working directory (default: temp dir)")
    parser.add_argument("--keep-work", action="store_true", help="Keep working directory after completion")

    args = parser.parse_args()

    # Validate
    if not os.path.isfile(args.apk):
        print(f"[!] APK not found: {args.apk}")
        sys.exit(1)

    if not validate_tools():
        sys.exit(1)

    # Set up paths
    apk_basename = os.path.splitext(os.path.basename(args.apk))[0]
    output_apk = args.output or f"{apk_basename}_adsweep.apk"
    work_dir = args.work_dir or tempfile.mkdtemp(prefix="adsweep_")
    decompiled_dir = os.path.join(work_dir, "decompiled")

    print(f"{'=' * 50}")
    print(f"  AdSweep Injector")
    print(f"  Input:  {args.apk}")
    print(f"  Output: {output_apk}")
    print(f"  Work:   {work_dir}")
    print(f"{'=' * 50}\n")

    try:
        # Step 1: Decompile
        if not decompile(args.apk, decompiled_dir):
            sys.exit(1)

        # Step 2: Scan (optional)
        if not args.skip_scan:
            report = scan(decompiled_dir)
            report_path = os.path.join(work_dir, "scan_report.json")
            save_report(report, report_path)

            if report["found_sdks"]:
                print(f"\n[*] Found {len(report['found_sdks'])} ad SDKs:")
                for sdk in report["found_sdks"]:
                    print(f"    - {sdk['sdk']} ({sdk['smali_files']} files)")

            # Generate suggested rules from scan
            suggested = generate_suggested_rules(decompiled_dir, report)
            if suggested:
                suggested_path = os.path.join(work_dir, "suggested_rules.json")
                save_suggested_rules(suggested, suggested_path)
                print(f"[*] Use these as app rules: --rules {suggested_path}")
            print()

        # Step 2b: Copy app-specific rules if provided
        if args.rules:
            import shutil as _shutil
            assets_dir = os.path.join(decompiled_dir, "assets")
            os.makedirs(assets_dir, exist_ok=True)
            _shutil.copy2(args.rules, os.path.join(assets_dir, "adsweep_rules_app.json"))
            print(f"[+] Copied app rules: {args.rules}")

        # Step 3: Patch
        if not patch(decompiled_dir):
            sys.exit(1)

        # Step 4: Package
        if not package_apk(decompiled_dir, output_apk,
                           keystore=args.keystore,
                           ks_pass=args.ks_pass,
                           key_alias=args.key_alias,
                           key_pass=args.key_pass):
            sys.exit(1)

        print(f"\n{'=' * 50}")
        print(f"  AdSweep injection complete!")
        print(f"  Output: {output_apk}")
        print(f"{'=' * 50}")

    finally:
        if not args.keep_work and not args.work_dir:
            shutil.rmtree(work_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
