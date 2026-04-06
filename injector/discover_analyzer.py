#!/usr/bin/env python3
"""
AdSweep Discovery Analyzer — Analyze discovery logs and produce verified rules.

Usage:
    # Pull log from device
    adb shell run-as <package> cat files/adsweep/discovery_log.txt > discovery_log.txt

    # Analyze and produce rules
    python3 discover_analyzer.py discovery_log.txt -o rules_discovered.json

    # Then inject with the discovered rules
    python3 inject.py --apk target.apk --rules rules_discovered.json
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from typing import Dict, List

# Methods that are likely ad-related based on call frequency and caller patterns
AD_CALLER_KEYWORDS = [
    "ad", "ads", "banner", "interstitial", "rewarded", "native",
    "mediation", "applovin", "admob", "facebook.ads", "ironsource",
    "vungle", "chartboost", "inmobi", "mopub", "adcolony",
    "kakao.adfit", "coupang.ads", "startapp", "bytedance",
    "google.android.gms.ads", "doubleclick", "googlesyndication",
]

# Methods that should NOT be blocked (false positives)
SAFE_METHODS = [
    "toString", "hashCode", "equals", "clone", "getClass",
    "onCreate", "onResume", "onPause", "onDestroy",
    "onAttachedToWindow", "onDetachedFromWindow",
]


def parse_log(log_path: str) -> List[Dict]:
    """Parse discovery log file into structured entries."""
    entries = []
    with open(log_path, "r") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|", 5)
            if len(parts) < 5:
                continue
            entries.append({
                "timestamp": int(parts[0]),
                "className": parts[1],
                "methodName": parts[2],
                "argCount": int(parts[3]),
                "argTypes": parts[4],
                "callStack": parts[5] if len(parts) > 5 else "",
            })
    return entries


def analyze(entries: List[Dict]) -> List[Dict]:
    """Analyze discovery log entries and produce suggested rules."""
    # Count method call frequency
    method_counts = Counter()
    method_callers = defaultdict(set)

    for entry in entries:
        key = f"{entry['className']}.{entry['methodName']}"
        method_counts[key] += 1

        # Track callers
        for caller in entry.get("callStack", "").split(" < "):
            caller = caller.strip()
            if caller:
                method_callers[key].add(caller)

    rules = []
    for method_key, count in method_counts.most_common():
        class_name, method_name = method_key.rsplit(".", 1)

        # Skip safe methods
        if method_name in SAFE_METHODS:
            continue

        # Score: how likely is this an ad method?
        score = 0
        reasons = []

        # High call frequency suggests ad refresh loop
        if count >= 10:
            score += 30
            reasons.append(f"high frequency ({count}x)")
        elif count >= 3:
            score += 15
            reasons.append(f"medium frequency ({count}x)")

        # Class/method name contains ad keywords
        lower_key = method_key.lower()
        for kw in AD_CALLER_KEYWORDS:
            if kw in lower_key:
                score += 40
                reasons.append(f"name matches '{kw}'")
                break

        # Callers contain ad keywords
        callers_str = " ".join(method_callers[method_key]).lower()
        for kw in AD_CALLER_KEYWORDS:
            if kw in callers_str:
                score += 20
                reasons.append(f"called from ad context")
                break

        # Method name patterns
        if any(p in method_name.lower() for p in ["load", "show", "fetch", "request", "init"]):
            score += 10
            reasons.append(f"ad-like method name")

        if score >= 30:
            rules.append({
                "id": f"discover-{class_name.split('.')[-1]}-{method_name}",
                "className": class_name,
                "methodName": method_name,
                "action": "BLOCK_RETURN_VOID",
                "enabled": True,
                "source": "LAYER2_SCAN",
                "sdkName": "Discovered",
                "notes": f"score={score}, calls={count}, {'; '.join(reasons)}",
                "_score": score,
                "_count": count,
            })

    # Sort by score descending
    rules.sort(key=lambda r: r["_score"], reverse=True)

    # Remove internal scoring fields
    for rule in rules:
        del rule["_score"]
        del rule["_count"]

    return rules


def main():
    parser = argparse.ArgumentParser(
        description="Analyze AdSweep discovery logs and produce verified rules"
    )
    parser.add_argument("logfile", help="Path to discovery_log.txt")
    parser.add_argument("-o", "--output", default="rules_discovered.json",
                        help="Output rules file (default: rules_discovered.json)")
    parser.add_argument("--min-score", type=int, default=30,
                        help="Minimum score to include (default: 30)")

    args = parser.parse_args()

    print(f"[*] Parsing {args.logfile}...")
    entries = parse_log(args.logfile)
    print(f"[+] {len(entries)} log entries")

    print("[*] Analyzing...")
    rules = analyze(entries)
    print(f"[+] {len(rules)} rules discovered")

    if rules:
        print("\nTop discoveries:")
        for r in rules[:15]:
            print(f"  {r['className'].split('.')[-1]:30s} .{r['methodName']:20s} — {r['notes']}")
        if len(rules) > 15:
            print(f"  ... and {len(rules) - 15} more")

    # Save
    output = {"version": 1, "rules": rules}
    with open(args.output, "w") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    print(f"\n[+] Rules saved to {args.output}")
    print(f"[*] Review the rules, then inject:")
    print(f"    python3 inject.py --apk target.apk --rules {args.output}")


if __name__ == "__main__":
    main()
