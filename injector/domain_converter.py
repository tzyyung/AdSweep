#!/usr/bin/env python3
"""
AdSweep Domain Converter — Download and convert ad domain lists.

Sources: AdGuard Base Filter, EasyList, EasyPrivacy, Peter Lowe's List
Output: adsweep_domains.txt (one domain per line, sorted, deduplicated)
"""
import re
import sys
import urllib.request
from typing import Set

SOURCES = {
    "AdGuard Base": "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/BaseFilter/sections/adservers.txt",
    "EasyList": "https://easylist.to/easylist/easylist.txt",
    "EasyPrivacy": "https://easylist.to/easylist/easyprivacy.txt",
    "Peter Lowe": "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0",
}

# Pattern: ||domain.com^ or ||domain.com/path^
ADGUARD_DOMAIN_PATTERN = re.compile(r'^\|\|([a-zA-Z0-9][\w\-\.]*\.[a-zA-Z]{2,})\^?\s*$')


def fetch_url(url: str) -> str:
    """Download URL content."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "AdSweep/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8", errors="ignore")
    except Exception as e:
        print(f"  [!] Failed to fetch {url}: {e}")
        return ""


def extract_domains(content: str) -> Set[str]:
    """Extract domains from filter list content (AdGuard/EasyList format)."""
    domains = set()
    for line in content.splitlines():
        line = line.strip()
        # Skip comments and whitelist rules
        if not line or line.startswith("!") or line.startswith("[") or line.startswith("@@"):
            continue

        # AdGuard/EasyList: ||domain.com^
        match = ADGUARD_DOMAIN_PATTERN.match(line)
        if match:
            domains.add(match.group(1).lower())
            continue

        # Hosts file format: 0.0.0.0 domain.com or 127.0.0.1 domain.com
        if line.startswith(("0.0.0.0 ", "127.0.0.1 ")):
            parts = line.split()
            if len(parts) >= 2:
                domain = parts[1].strip().lower()
                if "." in domain and not domain.startswith("#"):
                    domains.add(domain)

    return domains


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else "adsweep_domains.txt"

    all_domains = set()

    for name, url in SOURCES.items():
        print(f"[*] Downloading {name}...")
        content = fetch_url(url)
        if content:
            domains = extract_domains(content)
            print(f"  [+] {name}: {len(domains)} domains")
            all_domains.update(domains)

    # Remove common false positives
    false_positives = {"localhost", "local", "broadcasthost", "ip6-localhost", "ip6-loopback"}
    all_domains -= false_positives

    # Sort and write
    sorted_domains = sorted(all_domains)
    with open(output, "w") as f:
        f.write(f"# AdSweep domain blocklist\n")
        f.write(f"# Sources: {', '.join(SOURCES.keys())}\n")
        f.write(f"# Total: {len(sorted_domains)} domains\n")
        f.write(f"#\n")
        for domain in sorted_domains:
            f.write(domain + "\n")

    print(f"\n[+] Written {len(sorted_domains)} unique domains to {output}")


if __name__ == "__main__":
    main()
