"""
AdSweep Rule Fetcher — Download rules and domain blocklist from the community rule repository.

Supports:
  --rules-url auto     → auto-detect package name, fetch from default repo
  --rules-url <url>    → fetch from custom repo URL
"""
import json
import os
import tempfile
import urllib.request
import zipfile

# Default rule repository (GitHub raw URL)
DEFAULT_REPO = "https://raw.githubusercontent.com/tzyyung/adsweep-rules/main"


def fetch_rules_for_package(package_name: str, repo_url: str = None) -> str:
    """
    Fetch rules for a specific package from the rule repository.
    Returns path to downloaded rules JSON, or None if not found.
    """
    repo = repo_url or DEFAULT_REPO

    # Try to download index.json first
    index = _fetch_json(f"{repo}/index.json")
    if index and "apps" in index:
        app_info = index["apps"].get(package_name)
        if app_info and "rulesUrl" in app_info:
            rules_url = f"{repo}/{app_info['rulesUrl']}"
            print(f"[+] Found rules for {package_name}: {app_info.get('name', package_name)}")
            print(f"    Version: {app_info.get('testedVersion', 'unknown')}")
            print(f"    Status: {app_info.get('status', 'unknown')}")
            print(f"    Hooks: {app_info.get('hookCount', '?')}")
            return _download_to_temp(rules_url)
        else:
            print(f"[*] No rules found for {package_name} in repository")
            return None

    # Fallback: try direct path
    rules_url = f"{repo}/apps/{package_name}/rules.json"
    return _download_to_temp(rules_url)


def fetch_domains(repo_url: str = None) -> str:
    """
    Fetch the latest domain blocklist from the rule repository.
    Returns path to downloaded domains file, or None if not available.
    """
    repo = repo_url or DEFAULT_REPO

    index = _fetch_json(f"{repo}/index.json")
    if index and "domains" in index:
        domains_info = index["domains"]
        domains_url = f"{repo}/{domains_info.get('url', 'domains/adsweep_domains.txt')}"
        count = domains_info.get("count", "?")
        print(f"[*] Downloading domain blocklist ({count} domains)...")
        return _download_to_temp_raw(domains_url, suffix=".txt")

    # Fallback: try direct path
    domains_url = f"{repo}/domains/adsweep_domains.txt"
    return _download_to_temp_raw(domains_url, suffix=".txt")


def get_package_name_from_apk(decompiled_dir: str) -> str:
    """Extract package name from decompiled APK (binary or text manifest)."""
    manifest_path = os.path.join(decompiled_dir, "AndroidManifest.xml")
    if not os.path.exists(manifest_path):
        return None

    # Try text XML first
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(manifest_path)
        return tree.getroot().get("package")
    except Exception:
        pass

    # Try binary manifest via androguard
    try:
        from androguard.core.axml import AXMLPrinter
        import xml.etree.ElementTree as ET
        with open(manifest_path, "rb") as f:
            data = f.read()
        axml = AXMLPrinter(data)
        xml_text = axml.get_xml().decode()
        root = ET.fromstring(xml_text)
        return root.get("package")
    except Exception:
        pass

    # Fallback: parse apktool.yml
    yml_path = os.path.join(decompiled_dir, "apktool.yml")
    if os.path.exists(yml_path):
        with open(yml_path, "r") as f:
            for line in f:
                if "renameManifestPackage:" in line:
                    return line.split(":")[-1].strip()

    return None


def _fetch_json(url: str):
    """Download and parse a JSON URL. Returns None on error."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "AdSweep/1.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except Exception:
        return None


def _download_to_temp(url: str) -> str:
    """Download a JSON file to a temp path. Returns path or None."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "AdSweep/1.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = resp.read()
            # Validate it's valid JSON
            json.loads(data)
            tmp = tempfile.NamedTemporaryFile(suffix=".json", delete=False, prefix="adsweep_rules_")
            tmp.write(data)
            tmp.close()
            return tmp.name
    except Exception as e:
        return None


def _download_to_temp_raw(url: str, suffix: str = ".txt") -> str:
    """Download a raw file to a temp path. Returns path or None."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "AdSweep/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
            if len(data) < 100:
                return None
            tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False, prefix="adsweep_domains_")
            tmp.write(data)
            tmp.close()
            return tmp.name
    except Exception:
        return None
