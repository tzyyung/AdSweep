"""
AdSweep Smart Scanner — Multi-level ad SDK detection for obfuscated apps.

Level 1: String fingerprints (const-string patterns that survive R8)
Level 2: Class inheritance (.super directives are never obfuscated)
Level 3: Android API call patterns (framework calls cannot be renamed)
Level 4: Method signature fingerprints (param types + return type matching)
"""
import os
import re
import glob
from typing import Dict, List, Set, Tuple, Optional
from dataclasses import dataclass, field


# ── Level 1: String Fingerprints ──────────────────────────────────────────────

STRING_FINGERPRINTS = {
    "google_ads": {
        "patterns": [
            r"ca-app-pub-",
            r"googleads\.g\.doubleclick\.net",
            r"pagead2\.googlesyndication\.com",
            r"google\.android\.gms\.ads",
        ],
        "sdk": "Google Ads (obfuscated)",
        "confidence": 0.9,
    },
    "facebook_ads": {
        "patterns": [
            r"audience_network",
            r"AudienceNetwork",
            r"facebook\.com/.*?/ads",
            r"an\.facebook\.com",
        ],
        "sdk": "Facebook Ads (obfuscated)",
        "confidence": 0.85,
    },
    "applovin": {
        "patterns": [
            r"applovin\.com",
            r"AppLovinSdk",
            r"MaxAdView",
        ],
        "sdk": "AppLovin (obfuscated)",
        "confidence": 0.9,
    },
    "unity_ads": {
        "patterns": [
            r"unityads\.unity3d\.com",
            r"UnityAds",
            r"unity3d\.com/ads",
        ],
        "sdk": "Unity Ads (obfuscated)",
        "confidence": 0.9,
    },
    "ironsource": {
        "patterns": [
            r"ironsource\.com",
            r"IronSource",
            r"ironsrc\.com",
        ],
        "sdk": "IronSource (obfuscated)",
        "confidence": 0.85,
    },
    "vungle": {
        "patterns": [
            r"vungle\.com",
            r"Vungle",
        ],
        "sdk": "Vungle (obfuscated)",
        "confidence": 0.85,
    },
    "inmobi": {
        "patterns": [
            r"inmobi\.com",
            r"InMobi",
        ],
        "sdk": "InMobi (obfuscated)",
        "confidence": 0.85,
    },
    "generic_ad": {
        "patterns": [
            r'adUnitId',
            r'ad_unit_id',
            r'interstitial_ad',
            r'banner_ad',
            r'rewarded_ad',
            r'native_ad',
            r'Remove Ads',
            r'remove_ads',
        ],
        "sdk": "Generic Ad Code",
        "confidence": 0.7,
    },
    "ad_domains": {
        "patterns": [
            r"doubleclick\.net",
            r"googlesyndication\.com",
            r"googleadservices\.com",
            r"moatads\.com",
            r"serving-sys\.com",
            r"mopub\.com",
            r"smaato\.net",
            r"adcolony\.com",
            r"chartboost\.com",
        ],
        "sdk": "Ad Network Domains",
        "confidence": 0.8,
    },
}


# ── Level 2: Inheritance Patterns ─────────────────────────────────────────────

AD_SUPER_CLASSES = [
    # Google Ads
    "Lcom/google/android/gms/ads/AdView;",
    "Lcom/google/android/gms/ads/BaseAdView;",
    "Lcom/google/android/gms/ads/AdActivity;",
    "Lcom/google/android/gms/ads/nativead/NativeAd;",
    "Lcom/google/android/gms/ads/nativead/NativeAd$a;",
    "Lcom/google/android/gms/ads/nativead/NativeAd$b;",
    "Lcom/google/android/gms/ads/nativead/NativeAdView;",
    "Lcom/google/android/gms/ads/mediation/MediationBannerAdapter;",
    "Lcom/google/android/gms/ads/mediation/MediationInterstitialAdapter;",
    "Lcom/google/android/gms/ads/mediation/MediationNativeAdapter;",
    # Facebook
    "Lcom/facebook/ads/AdView;",
    "Lcom/facebook/ads/NativeAd;",
    "Lcom/facebook/ads/InterstitialAd;",
    # AppLovin
    "Lcom/applovin/mediation/MaxAd;",
    "Lcom/applovin/mediation/ads/MaxAdView;",
    # Unity
    "Lcom/unity3d/ads/UnityAds;",
]

AD_INTERFACES = [
    "Lcom/google/android/gms/ads/AdListener;",
    "Lcom/google/android/gms/ads/mediation/MediationBannerAdapter;",
    "Lcom/google/android/gms/ads/mediation/MediationInterstitialAdapter;",
    "Lcom/google/android/gms/ads/mediation/MediationNativeAdapter;",
    "Lcom/google/android/gms/ads/initialization/OnInitializationCompleteListener;",
    "Lcom/facebook/ads/AdListener;",
    "Lcom/applovin/mediation/MaxAdListener;",
]


# ── Level 3: API Call Patterns ────────────────────────────────────────────────

# Classes that exhibit N+ of these API patterns are likely ad-related
AD_API_PATTERNS = {
    "webview_load": re.compile(r"Landroid/webkit/WebView;->loadUrl"),
    "webview_load_data": re.compile(r"Landroid/webkit/WebView;->loadDataWithBaseURL"),
    "viewgroup_addview": re.compile(r"Landroid/view/ViewGroup;->addView"),
    "set_visibility": re.compile(r"Landroid/view/View;->setVisibility"),
    "start_activity": re.compile(r"Landroid/content/Context;->startActivity"),
    "open_connection": re.compile(r"Ljava/net/URL;->openConnection"),
    "http_execute": re.compile(r"Lorg/apache/http/client/HttpClient;->execute"),
    "get_device_id": re.compile(r"Landroid/telephony/TelephonyManager;->getDeviceId"),
    "advertising_id": re.compile(r"AdvertisingIdClient|getAdvertisingId"),
    "window_addview": re.compile(r"Landroid/view/WindowManager;->addView"),
    "inflate_view": re.compile(r"Landroid/view/LayoutInflater;->inflate"),
    "handler_post": re.compile(r"Landroid/os/Handler;->postDelayed"),
}

# Minimum number of ad-related API patterns to flag a class
AD_API_THRESHOLD = 3

# Patterns that strongly suggest ad behavior when combined
AD_API_COMBOS = [
    # WebView + network = ad content loader
    {"webview_load", "open_connection"},
    {"webview_load_data", "open_connection"},
    # View manipulation + network = ad view
    {"viewgroup_addview", "open_connection", "set_visibility"},
    # Device fingerprinting + network = ad tracking
    {"advertising_id", "open_connection"},
    {"get_device_id", "open_connection"},
    # View insertion + visibility = dynamic ad display
    {"viewgroup_addview", "set_visibility", "inflate_view"},
]


# ── Level 4: Method Signature Fingerprints ────────────────────────────────────

@dataclass
class MethodFingerprint:
    """ReVanced-style method fingerprint for obfuscation-resistant matching."""
    name: str                          # Human-readable name
    sdk: str                           # Associated SDK
    return_type: str                   # e.g., "V", "Z", "Ljava/lang/Object;"
    access_flags: str                  # e.g., "public static"
    param_types: List[str]             # e.g., ["Landroid/content/Context;"]
    strings: List[str] = field(default_factory=list)  # Strings that must be in method body
    invokes: List[str] = field(default_factory=list)   # Invoke patterns in method body
    action: str = "BLOCK_RETURN_VOID"  # Suggested hook action
    confidence: float = 0.8

# Known method signatures that survive obfuscation
METHOD_FINGERPRINTS = [
    # MobileAds.initialize(Context, OnInitializationCompleteListener)
    MethodFingerprint(
        name="MobileAds.initialize",
        sdk="Google Ads",
        return_type="V",
        access_flags="public static",
        param_types=["Landroid/content/Context;"],
        invokes=["Lcom/google/android/gms/ads/MobileAds;->"],
        action="BLOCK_RETURN_VOID",
        confidence=0.95,
    ),
    # AdView.loadAd(AdRequest) — instance method, param is ad request
    MethodFingerprint(
        name="AdView.loadAd",
        sdk="Google Ads",
        return_type="V",
        access_flags="public",
        param_types=["Landroid/content/Context;"],
        invokes=["loadAd", "Lcom/google/android/gms/ads/"],
        action="BLOCK_RETURN_VOID",
        confidence=0.85,
    ),
    # Any method calling MobileAds (obfuscated entry point)
    # return_type="" means match any return type (e.g. Kotlin coroutines return Object)
    MethodFingerprint(
        name="Ad SDK Init Wrapper",
        sdk="Google Ads",
        return_type="",  # any return type — coroutines return Object, not void
        access_flags="",  # any access
        param_types=[],   # any params
        invokes=["Lcom/google/android/gms/ads/MobileAds;->"],
        action="BLOCK_RETURN_VOID",
        confidence=0.9,
    ),
    # NativeAd display method — adds view to container
    MethodFingerprint(
        name="Native Ad Display",
        sdk="Google Ads",
        return_type="V",
        access_flags="",
        param_types=[],
        invokes=["Lcom/google/android/gms/ads/nativead/", "addView"],
        action="BLOCK_RETURN_VOID",
        confidence=0.85,
    ),
    # Facebook Ads initialization
    MethodFingerprint(
        name="FB AudienceNetwork.init",
        sdk="Facebook Ads",
        return_type="V",
        access_flags="public static",
        param_types=["Landroid/content/Context;"],
        invokes=["AudienceNetwork"],
        action="BLOCK_RETURN_VOID",
        confidence=0.85,
    ),
]


# ── Smart Scanner Implementation ─────────────────────────────────────────────

@dataclass
class SmartFinding:
    """A finding from the smart scanner."""
    level: int          # 1-4
    level_name: str     # Human-readable level name
    sdk: str            # Detected SDK name
    class_name: str     # Full class name
    method_name: str    # Method name (if applicable)
    file_path: str      # Smali file path
    confidence: float   # 0.0-1.0
    detail: str         # What was found
    suggested_action: str = "BLOCK_RETURN_VOID"


def smart_scan(decompiled_dir: str, verbose: bool = False) -> List[SmartFinding]:
    """
    Run all 4 levels of smart scanning on decompiled smali.
    Returns a list of SmartFindings sorted by confidence.
    """
    findings: List[SmartFinding] = []
    smali_dirs = sorted(glob.glob(os.path.join(decompiled_dir, "smali*")))

    if not smali_dirs:
        return findings

    # Collect all smali files
    smali_files = []
    for sd in smali_dirs:
        smali_files.extend(glob.glob(os.path.join(sd, "**/*.smali"), recursive=True))

    if verbose:
        print(f"[*] Smart scan: {len(smali_files)} smali files in {len(smali_dirs)} dirs")

    # Run all levels
    findings.extend(_scan_level1_strings(smali_files, smali_dirs, verbose))
    findings.extend(_scan_level2_inheritance(smali_files, smali_dirs, verbose))
    findings.extend(_scan_level3_api_patterns(smali_files, smali_dirs, verbose))
    findings.extend(_scan_level4_signatures(smali_files, smali_dirs, verbose))

    # Sort by confidence descending
    findings.sort(key=lambda f: f.confidence, reverse=True)

    return findings


def _get_class_name(smali_path: str, smali_dirs: List[str]) -> str:
    """Extract class name from smali file path."""
    # Sort by longest path first to avoid 'smali' matching 'smali_classes2'
    for sd in sorted(smali_dirs, key=len, reverse=True):
        if smali_path.startswith(sd + os.sep) or smali_path.startswith(sd + "/"):
            rel = os.path.relpath(smali_path, sd)
            return rel.replace("/", ".").replace(".smali", "")
    return os.path.basename(smali_path).replace(".smali", "")


# ── Level 1 Implementation ───────────────────────────────────────────────────

def _scan_level1_strings(smali_files: List[str], smali_dirs: List[str],
                          verbose: bool) -> List[SmartFinding]:
    """Scan for string constants that survive obfuscation."""
    findings = []
    seen_classes = {}  # class -> set of matched fingerprint groups

    for path in smali_files:
        try:
            with open(path, "r", errors="ignore") as f:
                content = f.read()
        except Exception:
            continue

        # Only scan const-string lines for efficiency
        strings = re.findall(r'const-string [vp]\d+, "(.*?)"', content)
        if not strings:
            continue

        joined = "\n".join(strings)
        class_name = _get_class_name(path, smali_dirs)

        for group_name, group_info in STRING_FINGERPRINTS.items():
            for pattern in group_info["patterns"]:
                if re.search(pattern, joined, re.IGNORECASE):
                    key = (class_name, group_name)
                    if key not in seen_classes:
                        seen_classes[key] = True
                        findings.append(SmartFinding(
                            level=1,
                            level_name="String Fingerprint",
                            sdk=group_info["sdk"],
                            class_name=class_name,
                            method_name="",
                            file_path=path,
                            confidence=group_info["confidence"],
                            detail=f"String matches '{pattern}' ({group_name})",
                        ))
                    break  # One match per group per class

    if verbose:
        print(f"  [L1] String fingerprints: {len(findings)} findings")
    return findings


# ── Level 2 Implementation ───────────────────────────────────────────────────

def _scan_level2_inheritance(smali_files: List[str], smali_dirs: List[str],
                              verbose: bool) -> List[SmartFinding]:
    """Scan for classes inheriting from known ad SDK classes."""
    findings = []

    for path in smali_files:
        try:
            with open(path, "r", errors="ignore") as f:
                # Read just the first 20 lines (class header)
                header_lines = []
                for i, line in enumerate(f):
                    header_lines.append(line)
                    if i > 20:
                        break
                header = "".join(header_lines)
        except Exception:
            continue

        class_name = _get_class_name(path, smali_dirs)

        # Check .super directive
        super_match = re.search(r'\.super\s+(L[^;\s]+;)', header)
        if super_match:
            super_class = super_match.group(1)
            for ad_super in AD_SUPER_CLASSES:
                if super_class == ad_super:
                    sdk = "Google Ads"
                    if "facebook" in ad_super.lower():
                        sdk = "Facebook Ads"
                    elif "applovin" in ad_super.lower():
                        sdk = "AppLovin"
                    elif "unity" in ad_super.lower():
                        sdk = "Unity Ads"

                    findings.append(SmartFinding(
                        level=2,
                        level_name="Inheritance",
                        sdk=sdk,
                        class_name=class_name,
                        method_name="",
                        file_path=path,
                        confidence=1.0,
                        detail=f"extends {ad_super}",
                    ))
                    break

        # Check .implements directive
        for impl_match in re.finditer(r'\.implements\s+(L[^;\s]+;)', header):
            impl = impl_match.group(1)
            for ad_iface in AD_INTERFACES:
                if impl == ad_iface:
                    sdk = "Google Ads"
                    if "facebook" in ad_iface.lower():
                        sdk = "Facebook Ads"
                    elif "applovin" in ad_iface.lower():
                        sdk = "AppLovin"

                    findings.append(SmartFinding(
                        level=2,
                        level_name="Inheritance",
                        sdk=sdk,
                        class_name=class_name,
                        method_name="",
                        file_path=path,
                        confidence=0.95,
                        detail=f"implements {ad_iface}",
                    ))
                    break

    if verbose:
        print(f"  [L2] Inheritance matches: {len(findings)} findings")
    return findings


# ── Level 3 Implementation ───────────────────────────────────────────────────

def _scan_level3_api_patterns(smali_files: List[str], smali_dirs: List[str],
                               verbose: bool) -> List[SmartFinding]:
    """Detect ad-related classes by Android API call patterns."""
    findings = []

    for path in smali_files:
        try:
            with open(path, "r", errors="ignore") as f:
                content = f.read()
        except Exception:
            continue

        # Count which ad-related API patterns appear in this file
        matched_apis = set()
        for api_name, pattern in AD_API_PATTERNS.items():
            if pattern.search(content):
                matched_apis.add(api_name)

        if len(matched_apis) < 2:
            continue

        class_name = _get_class_name(path, smali_dirs)

        # Check against known ad API combinations
        for combo in AD_API_COMBOS:
            if combo.issubset(matched_apis):
                findings.append(SmartFinding(
                    level=3,
                    level_name="API Pattern",
                    sdk="Unknown (behavioral)",
                    class_name=class_name,
                    method_name="",
                    file_path=path,
                    confidence=0.7,
                    detail=f"API combo: {', '.join(sorted(combo))} (total: {', '.join(sorted(matched_apis))})",
                ))
                break  # One finding per class for combos

        # Also flag if threshold exceeded
        if len(matched_apis) >= AD_API_THRESHOLD:
            # Avoid duplicate if combo already matched
            if not any(f.class_name == class_name and f.level == 3 for f in findings):
                findings.append(SmartFinding(
                    level=3,
                    level_name="API Pattern",
                    sdk="Unknown (behavioral)",
                    class_name=class_name,
                    method_name="",
                    file_path=path,
                    confidence=0.6,
                    detail=f"High ad-API density: {', '.join(sorted(matched_apis))}",
                ))

    if verbose:
        print(f"  [L3] API pattern matches: {len(findings)} findings")
    return findings


# ── Level 4 Implementation ───────────────────────────────────────────────────

def _scan_level4_signatures(smali_files: List[str], smali_dirs: List[str],
                             verbose: bool) -> List[SmartFinding]:
    """Match method signatures against known ad SDK fingerprints."""
    findings = []

    for path in smali_files:
        try:
            with open(path, "r", errors="ignore") as f:
                content = f.read()
        except Exception:
            continue

        class_name = _get_class_name(path, smali_dirs)
        methods = _parse_methods(content)

        for method_name, method_body, access, return_type, param_types in methods:
            for fp in METHOD_FINGERPRINTS:
                if _match_fingerprint(fp, method_body, access, return_type, param_types):
                    findings.append(SmartFinding(
                        level=4,
                        level_name="Method Signature",
                        sdk=fp.sdk,
                        class_name=class_name,
                        method_name=method_name,
                        file_path=path,
                        confidence=fp.confidence,
                        detail=f"Matches fingerprint: {fp.name}",
                        suggested_action=fp.action,
                    ))

    if verbose:
        print(f"  [L4] Signature matches: {len(findings)} findings")
    return findings


def _parse_methods(content: str) -> List[Tuple[str, str, str, str, List[str]]]:
    """Parse smali file into methods: (name, body, access_flags, return_type, param_types)."""
    methods = []
    method_pattern = re.compile(
        r'\.method\s+(.*?)\s+(\S+)\(([^)]*)\)(\S+)\s*\n(.*?)\.end method',
        re.DOTALL
    )

    for m in method_pattern.finditer(content):
        access = m.group(1)        # "public static final"
        name = m.group(2)          # method name
        params_raw = m.group(3)    # "Landroid/content/Context;LEa/c;"
        return_type = m.group(4)   # "V"
        body = m.group(5)          # method body

        # Parse param types
        param_types = _parse_param_types(params_raw)

        methods.append((name, body, access, return_type, param_types))

    return methods


def _parse_param_types(params_raw: str) -> List[str]:
    """Parse smali parameter string into a list of types."""
    types = []
    i = 0
    while i < len(params_raw):
        if params_raw[i] == 'L':
            end = params_raw.index(';', i) + 1
            types.append(params_raw[i:end])
            i = end
        elif params_raw[i] == '[':
            # Array type
            start = i
            i += 1
            if i < len(params_raw) and params_raw[i] == 'L':
                end = params_raw.index(';', i) + 1
                types.append(params_raw[start:end])
                i = end
            elif i < len(params_raw):
                types.append(params_raw[start:i + 1])
                i += 1
        elif params_raw[i] in 'ZBCSIJFD':
            types.append(params_raw[i])
            i += 1
        else:
            i += 1
    return types


def _match_fingerprint(fp: MethodFingerprint, body: str, access: str,
                        return_type: str, param_types: List[str]) -> bool:
    """Check if a method matches a fingerprint."""
    # Return type must match
    if fp.return_type and fp.return_type != return_type:
        return False

    # Access flags must match (if specified)
    if fp.access_flags:
        for flag in fp.access_flags.split():
            if flag not in access:
                return False

    # Param types must match (if specified)
    if fp.param_types:
        if len(param_types) < len(fp.param_types):
            return False
        for i, expected in enumerate(fp.param_types):
            if expected and param_types[i] != expected:
                return False

    # All invoke patterns must appear in body
    if fp.invokes:
        if not all(p in body for p in fp.invokes):
            return False

    # All string patterns must appear in body
    if fp.strings:
        if not all(s in body for s in fp.strings):
            return False

    return True


# ── Rule Generation ──────────────────────────────────────────────────────────

def generate_rules_from_findings(findings: List[SmartFinding],
                                  min_confidence: float = 0.7) -> List[Dict]:
    """Generate hook rules from smart scan findings."""
    rules = []
    seen = set()

    for f in findings:
        if f.confidence < min_confidence:
            continue

        # Level 4 findings have specific methods to hook
        if f.level == 4 and f.method_name:
            key = f"{f.class_name}.{f.method_name}"
            if key in seen:
                continue
            seen.add(key)

            rules.append({
                "id": f"smart-L{f.level}-{f.class_name.split('.')[-1]}-{f.method_name}",
                "className": f.class_name,
                "methodName": f.method_name,
                "action": f.suggested_action,
                "enabled": True,
                "source": f"SMART_SCAN_L{f.level}",
                "sdkName": f.sdk,
                "confidence": f.confidence,
                "notes": f.detail,
            })

    return rules


# ── Summary Printing ─────────────────────────────────────────────────────────

def print_summary(findings: List[SmartFinding]):
    """Print a human-readable summary of smart scan findings."""
    if not findings:
        print("[*] Smart scan: no findings")
        return

    # Group by level
    by_level = {}
    for f in findings:
        by_level.setdefault(f.level, []).append(f)

    # Group by SDK
    sdks = set()
    for f in findings:
        if f.sdk != "Unknown (behavioral)":
            sdks.add(f.sdk)

    print(f"\n[+] Smart scan: {len(findings)} findings across {len(by_level)} levels")
    print(f"    SDKs detected: {', '.join(sorted(sdks)) or 'none identified'}")

    level_names = {1: "String Fingerprint", 2: "Inheritance", 3: "API Pattern", 4: "Method Signature"}
    for level in sorted(by_level.keys()):
        items = by_level[level]
        print(f"    L{level} ({level_names.get(level, '?')}): {len(items)} findings")

    # Show high-confidence hookable methods
    hookable = [f for f in findings if f.level == 4 and f.confidence >= 0.8]
    if hookable:
        print(f"\n    Hookable methods ({len(hookable)}):")
        for f in hookable:
            print(f"      {f.class_name}.{f.method_name} [{f.suggested_action}] (conf={f.confidence})")
