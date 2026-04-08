"""
AdSweep Injector - Repackage, align, and sign the patched APK.
"""
import subprocess
import os
import zipfile

from config import ZIPALIGN, APKSIGNER, DEFAULT_KEYSTORE, DEFAULT_KS_PASS, DEFAULT_KEY_ALIAS, DEFAULT_KEY_PASS
from manifest_patcher import patch_manifest_in_apk


def package_apk(decompiled_dir: str, output_apk: str,
                keystore: str = None, ks_pass: str = None,
                key_alias: str = None, key_pass: str = None,
                original_apk: str = None) -> bool:
    """Recompile, align, and sign the APK."""

    keystore = keystore or DEFAULT_KEYSTORE
    ks_pass = ks_pass or DEFAULT_KS_PASS
    key_alias = key_alias or DEFAULT_KEY_ALIAS
    key_pass = key_pass or DEFAULT_KEY_PASS

    base_dir = os.path.dirname(output_apk) or "."
    unsigned_apk = os.path.join(base_dir, "adsweep_unsigned.apk")
    aligned_apk = os.path.join(base_dir, "adsweep_aligned.apk")

    # Step 1: Recompile with apktool
    from decompiler import recompile
    if not recompile(decompiled_dir, unsigned_apk):
        return False

    # Step 1b: Restore entries lost by apktool rebuild (obfuscated res/ filenames)
    if original_apk:
        _restore_missing_entries(unsigned_apk, original_apk)

    # Step 2: Patch binary manifest (extractNativeLibs, etc.)
    patch_manifest_in_apk(unsigned_apk)

    # Step 3: Zipalign with page alignment (-p) for uncompressed .so files
    print("[*] Aligning APK...")
    cmd = [ZIPALIGN, "-f", "-p", "4", unsigned_apk, aligned_apk]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Zipalign failed:\n{result.stderr}")
        return False
    print("[+] APK aligned")

    # Step 3: Sign
    print("[*] Signing APK...")
    cmd = [
        APKSIGNER, "sign",
        "--ks", keystore,
        "--ks-pass", f"pass:{ks_pass}",
        "--ks-key-alias", key_alias,
        "--key-pass", f"pass:{key_pass}",
        aligned_apk,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Signing failed:\n{result.stderr}")
        return False
    print("[+] APK signed")

    # Step 4: Rename to final output
    os.rename(aligned_apk, output_apk)

    # Cleanup
    for tmp in [unsigned_apk]:
        if os.path.exists(tmp):
            os.remove(tmp)

    print(f"[+] Final APK: {output_apk}")
    return True


def _restore_missing_entries(rebuilt_apk: str, original_apk: str):
    """Restore entries from original APK that apktool dropped during rebuild.

    apktool 3.x with -r mode may lose some obfuscated resource filenames
    (e.g., res/-P.xml, res/2F.xml) during rebuild. This function detects
    missing entries and copies them back from the original APK.
    """
    with zipfile.ZipFile(original_apk, 'r') as orig:
        orig_entries = {i.filename: i for i in orig.infolist()}

    with zipfile.ZipFile(rebuilt_apk, 'r') as rebuilt:
        rebuilt_names = set(i.filename for i in rebuilt.infolist())

    # Find entries in original that are missing from rebuilt
    # Skip META-INF/ (signatures) and classes*.dex (we have our own)
    missing = []
    for name, info in orig_entries.items():
        if name in rebuilt_names:
            continue
        if name.startswith("META-INF/"):
            continue
        if name.endswith(".dex") and name.startswith("classes"):
            continue
        missing.append(info)

    if not missing:
        return

    # Append missing entries to rebuilt APK
    with zipfile.ZipFile(original_apk, 'r') as orig, \
         zipfile.ZipFile(rebuilt_apk, 'a') as rebuilt:
        for info in missing:
            data = orig.read(info)
            rebuilt.writestr(info, data)

    print(f"[+] Restored {len(missing)} entries lost by apktool rebuild")


def extract_original_signature(apk_path: str, decompiled_dir: str):
    """Extract the original APK's signing certificate and save as an asset.

    Tries two methods:
    1. META-INF/*.RSA (v1 JAR signature) — raw PKCS#7 bytes
    2. apksigner + keytool (v2/v3 signature) — extract DER cert

    Runtime uses Android's CertificateFactory to parse the stored bytes.
    """
    assets_dir = os.path.join(decompiled_dir, "assets")
    os.makedirs(assets_dir, exist_ok=True)
    sig_path = os.path.join(assets_dir, "adsweep_original_sig.bin")

    try:
        # Method 1: Try META-INF/*.RSA (v1 signature)
        with zipfile.ZipFile(apk_path, 'r') as zf:
            for name in zf.namelist():
                if name.startswith("META-INF/") and \
                   (name.endswith(".RSA") or name.endswith(".DSA") or name.endswith(".EC")):
                    data = zf.read(name)
                    with open(sig_path, "wb") as f:
                        f.write(data)
                    print(f"[+] Saved original signing certificate ({len(data)} bytes) from {name}")
                    return

        # Method 2: v2/v3 signature — use apksigner to get cert, then keytool to export DER
        _extract_v2_signature(apk_path, sig_path)

    except Exception as e:
        print(f"[!] Failed to extract signature: {e}")


def _extract_v2_signature(apk_path: str, sig_path: str):
    """Extract signing certificate from APK with v2/v3 signature scheme."""
    import tempfile

    # Use apksigner to extract the certificate lineage/certs
    # apksigner verify --print-certs-pem prints PEM-encoded certs
    result = subprocess.run(
        [APKSIGNER, "verify", "--print-certs-pem", apk_path],
        capture_output=True, text=True
    )

    if result.returncode != 0:
        print(f"[*] No signing certificate found (apksigner: {result.stderr.strip()})")
        return

    # Parse PEM certificate from output
    import base64
    pem_lines = []
    in_cert = False
    for line in result.stdout.splitlines():
        if "BEGIN CERTIFICATE" in line:
            in_cert = True
            pem_lines = []
        elif "END CERTIFICATE" in line:
            in_cert = False
            if pem_lines:
                # Got a complete cert — convert PEM to DER
                der_bytes = base64.b64decode("".join(pem_lines))
                with open(sig_path, "wb") as f:
                    f.write(der_bytes)
                print(f"[+] Saved original signing certificate ({len(der_bytes)} bytes) from v2/v3 signature")
                return
        elif in_cert:
            pem_lines.append(line.strip())

    print("[*] No signing certificate found in APK")
