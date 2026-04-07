"""
Unit tests for smart_scanner.py — Multi-level ad SDK detection.

Tests each level independently with synthetic smali, then validates
against real decompiled apps (AccuWeather, QR Scanner) if available.
"""
import os
import sys
import json
import shutil
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))

from smart_scanner import (
    smart_scan, _scan_level1_strings, _scan_level2_inheritance,
    _scan_level3_api_patterns, _scan_level4_signatures,
    _parse_param_types, generate_rules_from_findings, SmartFinding,
)


class TestParamTypeParsing(unittest.TestCase):
    """Test smali parameter type parsing."""

    def test_single_object(self):
        self.assertEqual(
            _parse_param_types("Landroid/content/Context;"),
            ["Landroid/content/Context;"]
        )

    def test_multiple_objects(self):
        self.assertEqual(
            _parse_param_types("Landroid/content/Context;LEa/c;"),
            ["Landroid/content/Context;", "LEa/c;"]
        )

    def test_primitives(self):
        self.assertEqual(_parse_param_types("IZJ"), ["I", "Z", "J"])

    def test_mixed(self):
        self.assertEqual(
            _parse_param_types("Landroid/content/Context;I"),
            ["Landroid/content/Context;", "I"]
        )

    def test_array(self):
        self.assertEqual(
            _parse_param_types("[Ljava/lang/String;"),
            ["[Ljava/lang/String;"]
        )

    def test_empty(self):
        self.assertEqual(_parse_param_types(""), [])


class SmaliTestBase(unittest.TestCase):
    """Base class that creates temp smali directories for testing."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="adsweep_test_")
        self.smali_dir = os.path.join(self.tmpdir, "smali")
        os.makedirs(self.smali_dir)

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _write_smali(self, relative_path: str, content: str) -> str:
        """Write a smali file and return its full path."""
        full_path = os.path.join(self.smali_dir, relative_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w") as f:
            f.write(content)
        return full_path

    def _get_smali_files(self):
        """Get all smali files in the temp directory."""
        import glob
        return glob.glob(os.path.join(self.smali_dir, "**/*.smali"), recursive=True)


class TestLevel1Strings(SmaliTestBase):
    """Level 1: String fingerprint detection."""

    def test_google_ad_unit_id(self):
        self._write_smali("com/example/AdHelper.smali", '''
.class public Lcom/example/AdHelper;
.super Ljava/lang/Object;

.method public getAdUnitId()Ljava/lang/String;
    .locals 1
    const-string v0, "ca-app-pub-1234567890123456/1234567890"
    return-object v0
.end method
''')
        findings = _scan_level1_strings(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)
        self.assertEqual(findings[0].level, 1)
        self.assertIn("Google Ads", findings[0].sdk)

    def test_doubleclick_domain(self):
        self._write_smali("a/b/c.smali", '''
.class public La/b/c;
.super Ljava/lang/Object;

.method public loadContent()V
    .locals 1
    const-string v0, "https://googleads.g.doubleclick.net/mads/static"
    return-void
.end method
''')
        findings = _scan_level1_strings(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)

    def test_generic_ad_strings(self):
        self._write_smali("com/app/Config.smali", '''
.class public Lcom/app/Config;
.super Ljava/lang/Object;

.method public check()V
    .locals 1
    const-string v0, "adUnitId"
    return-void
.end method
''')
        findings = _scan_level1_strings(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)

    def test_no_ad_strings(self):
        self._write_smali("com/app/Utils.smali", '''
.class public Lcom/app/Utils;
.super Ljava/lang/Object;

.method public getVersion()Ljava/lang/String;
    .locals 1
    const-string v0, "1.0.0"
    return-object v0
.end method
''')
        findings = _scan_level1_strings(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 0)

    def test_facebook_ads_string(self):
        self._write_smali("x/y/z.smali", '''
.class public Lx/y/z;
.super Ljava/lang/Object;

.method init()V
    .locals 1
    const-string v0, "AudienceNetwork"
    return-void
.end method
''')
        findings = _scan_level1_strings(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)
        self.assertIn("Facebook", findings[0].sdk)


class TestLevel2Inheritance(SmaliTestBase):
    """Level 2: Class inheritance detection."""

    def test_extends_native_ad(self):
        self._write_smali("com/google/internal/ads/MyNativeAd.smali", '''
.class public Lcom/google/internal/ads/MyNativeAd;
.super Lcom/google/android/gms/ads/nativead/NativeAd;
''')
        findings = _scan_level2_inheritance(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].level, 2)
        self.assertEqual(findings[0].confidence, 1.0)
        self.assertIn("NativeAd", findings[0].detail)

    def test_implements_ad_listener(self):
        self._write_smali("a/b/Callback.smali", '''
.class public La/b/Callback;
.super Ljava/lang/Object;
.implements Lcom/google/android/gms/ads/AdListener;
''')
        findings = _scan_level2_inheritance(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].confidence, 0.95)

    def test_extends_non_ad_class(self):
        self._write_smali("com/app/MyActivity.smali", '''
.class public Lcom/app/MyActivity;
.super Landroid/app/Activity;
''')
        findings = _scan_level2_inheritance(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 0)

    def test_extends_facebook_ad(self):
        self._write_smali("a/b/FbAd.smali", '''
.class public La/b/FbAd;
.super Lcom/facebook/ads/NativeAd;
''')
        findings = _scan_level2_inheritance(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 1)
        self.assertIn("Facebook", findings[0].sdk)

    def test_multiple_inheritance(self):
        self._write_smali("a/Ad1.smali", '''
.class public La/Ad1;
.super Lcom/google/android/gms/ads/nativead/NativeAd;
''')
        self._write_smali("b/Ad2.smali", '''
.class public Lb/Ad2;
.super Lcom/google/android/gms/ads/nativead/NativeAd$b;
''')
        findings = _scan_level2_inheritance(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 2)


class TestLevel3ApiPatterns(SmaliTestBase):
    """Level 3: Android API call pattern detection."""

    def test_webview_plus_network(self):
        self._write_smali("a/AdLoader.smali", '''
.class public La/AdLoader;
.super Ljava/lang/Object;

.method public load()V
    .locals 2
    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    return-void
.end method
''')
        findings = _scan_level3_api_patterns(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)
        self.assertEqual(findings[0].level, 3)
        self.assertIn("webview_load", findings[0].detail)

    def test_view_plus_network_plus_visibility(self):
        self._write_smali("x/AdView.smali", '''
.class public Lx/AdView;
.super Landroid/widget/FrameLayout;

.method public display()V
    .locals 3
    invoke-virtual {v0, v1}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
    return-void
.end method
''')
        findings = _scan_level3_api_patterns(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)

    def test_single_api_no_match(self):
        self._write_smali("com/app/WebHelper.smali", '''
.class public Lcom/app/WebHelper;
.super Ljava/lang/Object;

.method public load()V
    .locals 1
    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    return-void
.end method
''')
        findings = _scan_level3_api_patterns(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 0)

    def test_advertising_id_plus_network(self):
        self._write_smali("a/Tracker.smali", '''
.class public La/Tracker;
.super Ljava/lang/Object;

.method public track()V
    .locals 2
    const-string v0, "AdvertisingIdClient"
    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    return-void
.end method
''')
        findings = _scan_level3_api_patterns(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)

    def test_high_api_density(self):
        self._write_smali("a/Complex.smali", '''
.class public La/Complex;
.super Ljava/lang/Object;

.method public show()V
    .locals 3
    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    invoke-virtual {v0, v1}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    return-void
.end method
''')
        findings = _scan_level3_api_patterns(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)


class TestLevel4Signatures(SmaliTestBase):
    """Level 4: Method signature fingerprint detection."""

    def test_mobile_ads_init_obfuscated(self):
        """The key test: detect MobileAds.a() as MobileAds.initialize()."""
        self._write_smali("com/app/HomeActivity.smali", '''
.class public Lcom/app/HomeActivity;
.super Landroid/app/Activity;

.method public static initAds(Landroid/content/Context;)V
    .locals 1
    new-instance v0, La/b/c;
    invoke-direct {v0}, La/b/c;-><init>()V
    invoke-static {p0, v0}, Lcom/google/android/gms/ads/MobileAds;->a(Landroid/content/Context;LEa/c;)V
    return-void
.end method
''')
        findings = _scan_level4_signatures(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)
        found_init = any("MobileAds" in f.detail for f in findings)
        self.assertTrue(found_init, "Should detect obfuscated MobileAds.initialize()")

    def test_native_ad_display(self):
        self._write_smali("a/b/AdDisplay.smali", '''
.class public La/b/AdDisplay;
.super Ljava/lang/Object;

.method public showNative()V
    .locals 2
    invoke-virtual {v0}, Lcom/google/android/gms/ads/nativead/NativeAd;->getHeadline()Ljava/lang/String;
    invoke-virtual {v0, v1}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    return-void
.end method
''')
        findings = _scan_level4_signatures(self._get_smali_files(), [self.smali_dir], False)
        self.assertTrue(len(findings) > 0)

    def test_no_ad_method(self):
        self._write_smali("com/app/Utils.smali", '''
.class public Lcom/app/Utils;
.super Ljava/lang/Object;

.method public static formatDate(Ljava/lang/String;)Ljava/lang/String;
    .locals 1
    return-object p0
.end method
''')
        findings = _scan_level4_signatures(self._get_smali_files(), [self.smali_dir], False)
        self.assertEqual(len(findings), 0)


class TestSmartScanIntegration(SmaliTestBase):
    """Integration test: run full smart_scan on synthetic smali."""

    def test_full_scan_obfuscated_app(self):
        """Simulate an obfuscated app with Google Ads: class names changed but behavior intact."""
        # Obfuscated ad init wrapper
        self._write_smali("a/b.smali", '''
.class public La/b;
.super Ljava/lang/Object;

.method public static a(Landroid/content/Context;)V
    .locals 1
    new-instance v0, La/c;
    invoke-direct {v0}, La/c;-><init>()V
    invoke-static {p0, v0}, Lcom/google/android/gms/ads/MobileAds;->a(Landroid/content/Context;LEa/c;)V
    return-void
.end method
''')
        # NativeAd subclass (inheritance preserved)
        self._write_smali("d/e.smali", '''
.class public Ld/e;
.super Lcom/google/android/gms/ads/nativead/NativeAd;

.method public a()Ljava/lang/String;
    .locals 1
    const-string v0, "headline"
    return-object v0
.end method
''')
        # Ad view with API patterns
        self._write_smali("f/g.smali", '''
.class public Lf/g;
.super Landroid/widget/FrameLayout;

.method public a()V
    .locals 3
    const-string v0, "https://googleads.g.doubleclick.net/mads/static"
    invoke-virtual {v1, v0}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    invoke-virtual {p0, v2}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    return-void
.end method
''')

        findings = smart_scan(self.tmpdir, verbose=False)

        # Should have findings from multiple levels
        levels_found = set(f.level for f in findings)
        self.assertIn(1, levels_found, "Should have Level 1 string findings")
        self.assertIn(2, levels_found, "Should have Level 2 inheritance findings")
        self.assertIn(3, levels_found, "Should have Level 3 API pattern findings")
        self.assertIn(4, levels_found, "Should have Level 4 signature findings")

        # Should detect Google Ads
        sdks = set(f.sdk for f in findings)
        google_found = any("Google" in s for s in sdks)
        self.assertTrue(google_found, f"Should detect Google Ads, found: {sdks}")

    def test_clean_app_no_findings(self):
        """An app with no ad SDK should produce minimal/no findings."""
        self._write_smali("com/myapp/MainActivity.smali", '''
.class public Lcom/myapp/MainActivity;
.super Landroid/app/Activity;

.method public onCreate(Landroid/os/Bundle;)V
    .locals 1
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
    return-void
.end method
''')
        self._write_smali("com/myapp/Utils.smali", '''
.class public Lcom/myapp/Utils;
.super Ljava/lang/Object;

.method public static formatTemp(F)Ljava/lang/String;
    .locals 1
    invoke-static {p0}, Ljava/lang/String;->valueOf(F)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
''')

        findings = smart_scan(self.tmpdir, verbose=False)
        # Should have zero or very few findings (none ad-related)
        high_conf = [f for f in findings if f.confidence >= 0.7]
        self.assertEqual(len(high_conf), 0, f"Clean app should have no high-confidence findings: {high_conf}")


class TestRuleGeneration(SmaliTestBase):
    """Test rule generation from findings."""

    def test_generate_rules_from_level4(self):
        self._write_smali("a/Init.smali", '''
.class public La/Init;
.super Ljava/lang/Object;

.method public static a(Landroid/content/Context;)V
    .locals 1
    invoke-static {p0, v0}, Lcom/google/android/gms/ads/MobileAds;->a(Landroid/content/Context;LEa/c;)V
    return-void
.end method
''')
        findings = smart_scan(self.tmpdir, verbose=False)
        rules = generate_rules_from_findings(findings, min_confidence=0.7)

        # Should generate at least one rule for the init method
        level4_rules = [r for r in rules if "L4" in r["source"]]
        self.assertTrue(len(level4_rules) > 0, "Should generate rules from Level 4 findings")

    def test_min_confidence_filter(self):
        findings = [
            SmartFinding(level=4, level_name="Method Signature", sdk="Test",
                        class_name="a.b", method_name="init", file_path="",
                        confidence=0.5, detail="test", suggested_action="BLOCK_RETURN_VOID"),
        ]
        rules = generate_rules_from_findings(findings, min_confidence=0.7)
        self.assertEqual(len(rules), 0, "Low confidence findings should be filtered out")


class TestRealApps(unittest.TestCase):
    """Test against real decompiled apps (skip if not available)."""

    ACCUWEATHER_DIR = os.path.join(os.path.dirname(__file__),
                                    "../apks/work_accuweather/decompiled")
    QR_SCANNER_DIR = os.path.join(os.path.dirname(__file__),
                                   "../apks/work_gamma/decompiled")

    @unittest.skipUnless(os.path.isdir(ACCUWEATHER_DIR),
                         "AccuWeather decompiled dir not available")
    def test_accuweather_detection(self):
        """AccuWeather uses obfuscated Google Ads — smart scan should detect it."""
        findings = smart_scan(self.ACCUWEATHER_DIR, verbose=True)

        # Must detect Google Ads via multiple levels
        levels = set(f.level for f in findings)
        self.assertIn(1, levels, "L1: Should find ad domain strings")
        self.assertIn(2, levels, "L2: Should find NativeAd inheritance")
        self.assertIn(4, levels, "L4: Should find obfuscated MobileAds.init")

        # Must find NativeAd subclass
        l2 = [f for f in findings if f.level == 2]
        native_ad = any("NativeAd" in f.detail for f in l2)
        self.assertTrue(native_ad, "Should detect NativeAd subclass via inheritance")

        # Must find MobileAds init wrapper (fingerprint name contains "Init")
        l4 = [f for f in findings if f.level == 4]
        init_found = any("Init" in f.detail or "MobileAds" in f.detail for f in l4)
        self.assertTrue(init_found, f"Should detect obfuscated MobileAds.initialize via L4, got: {[f.detail for f in l4]}")

        # Print summary for manual review
        from smart_scanner import print_summary
        print_summary(findings)

    @unittest.skipUnless(os.path.isdir(QR_SCANNER_DIR),
                         "QR Scanner decompiled dir not available")
    def test_qr_scanner_detection(self):
        """QR Scanner uses standard AdMob — should be detected by all levels."""
        findings = smart_scan(self.QR_SCANNER_DIR, verbose=True)

        self.assertTrue(len(findings) > 0, "Should find ad SDK in QR Scanner")

        # Should detect multiple SDKs (AdMob, Facebook, Vungle)
        sdks = set(f.sdk for f in findings)
        google_found = any("Google" in s or "Ad" in s for s in sdks)
        self.assertTrue(google_found, f"Should detect Google Ads, found: {sdks}")

        from smart_scanner import print_summary
        print_summary(findings)


if __name__ == "__main__":
    unittest.main(verbosity=2)
