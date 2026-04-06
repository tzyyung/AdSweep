"""
AdSweep Injector - Patch the target APK to load AdSweep on startup.

This module:
1. Finds the Application class from AndroidManifest.xml
2. Injects AdSweep.init() call into Application.onCreate()
3. Copies AdSweep DEX and native libraries into the decompiled directory
4. Adds required permissions and activities to the manifest
"""
import os
import re
import glob
import shutil
import xml.etree.ElementTree as ET

from config import PREBUILT_DIR


def patch(decompiled_dir: str) -> bool:
    """Apply all patches to the decompiled APK directory."""
    manifest_path = os.path.join(decompiled_dir, "AndroidManifest.xml")

    if not os.path.exists(manifest_path):
        print("[!] AndroidManifest.xml not found")
        return False

    # Step 1: Find or create Application class
    app_class = find_application_class(manifest_path)
    if app_class:
        print(f"[+] Found Application class: {app_class}")
        smali_path = find_smali_file(decompiled_dir, app_class)
        if not smali_path:
            print(f"[!] Could not find smali file for {app_class}")
            return False
    else:
        print("[*] No custom Application class found, creating stub...")
        app_class = "com.adsweep.AdSweepApp"
        smali_path = create_stub_application(decompiled_dir)
        update_manifest_application(manifest_path, app_class)

    # Step 2: Inject init call into onCreate
    if not inject_init_call(smali_path):
        return False

    # Step 3: Copy payload files
    if not copy_payload(decompiled_dir):
        return False

    # Step 4: Fix broken resources from apktool decompilation
    fix_decompile_artifacts(decompiled_dir)

    # Step 5: Patch manifest (permissions + activity)
    if not patch_manifest(manifest_path):
        return False

    print("[+] All patches applied successfully")
    return True


def find_application_class(manifest_path: str) -> str:
    """Extract the Application class name from AndroidManifest.xml."""
    tree = ET.parse(manifest_path)
    root = tree.getroot()
    ns = {"android": "http://schemas.android.com/apk/res/android"}

    app_elem = root.find("application")
    if app_elem is not None:
        name = app_elem.get("{http://schemas.android.com/apk/res/android}name")
        if name:
            # Handle relative class names
            if name.startswith("."):
                package = root.get("package", "")
                name = package + name
            return name
    return None


def find_smali_file(decompiled_dir: str, class_name: str) -> str:
    """Find the smali file for a given Java class name."""
    # Convert com.example.MyApp to com/example/MyApp.smali
    relative_path = class_name.replace(".", "/") + ".smali"

    # Search in all smali directories (smali, smali_classes2, etc.)
    for smali_dir in sorted(glob.glob(os.path.join(decompiled_dir, "smali*"))):
        candidate = os.path.join(smali_dir, relative_path)
        if os.path.exists(candidate):
            return candidate
    return None


def create_stub_application(decompiled_dir: str) -> str:
    """Create a minimal Application subclass that calls AdSweep.init()."""
    smali_dir = os.path.join(decompiled_dir, "smali", "com", "adsweep")
    os.makedirs(smali_dir, exist_ok=True)

    stub_path = os.path.join(smali_dir, "AdSweepApp.smali")
    with open(stub_path, "w") as f:
        f.write(""".class public Lcom/adsweep/AdSweepApp;
.super Landroid/app/Application;

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/app/Application;-><init>()V
    return-void
.end method

.method public onCreate()V
    .locals 0
    invoke-super {p0}, Landroid/app/Application;->onCreate()V
    invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V
    return-void
.end method
""")
    print(f"[+] Created stub Application: {stub_path}")
    return stub_path


def update_manifest_application(manifest_path: str, app_class: str) -> bool:
    """Set the android:name attribute on the <application> element."""
    with open(manifest_path, "r") as f:
        content = f.read()

    # Add android:name to <application> tag
    if 'android:name=' not in content.split('<application')[1].split('>')[0]:
        content = content.replace(
            "<application",
            f'<application android:name="{app_class}"',
            1
        )
        with open(manifest_path, "w") as f:
            f.write(content)
        print(f"[+] Updated manifest with Application class: {app_class}")
    return True


def inject_init_call(smali_path: str) -> bool:
    """Inject AdSweep.init() call into the Application's onCreate method."""
    with open(smali_path, "r") as f:
        content = f.read()

    init_call = "    invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V"

    # Check if already injected
    if "Lcom/adsweep/AdSweep;->init" in content:
        print("[*] AdSweep.init() already injected, skipping")
        return True

    # Find onCreate method and inject after .locals or .registers
    oncreate_pattern = r"(\.method\s+public\s+onCreate\(\)V\s*\n(?:.*\n)*?\s*\.(?:locals|registers)\s+\d+)"
    match = re.search(oncreate_pattern, content)

    if match:
        injection_point = match.end()
        content = content[:injection_point] + "\n\n" + init_call + "\n" + content[injection_point:]

        with open(smali_path, "w") as f:
            f.write(content)
        print(f"[+] Injected AdSweep.init() into {smali_path}")
        return True
    else:
        print(f"[!] Could not find onCreate() in {smali_path}")
        print("[!] You may need to manually inject the init call")
        return False


def copy_payload(decompiled_dir: str) -> bool:
    """Copy AdSweep DEX, native libraries, and assets into the decompiled directory."""
    if not os.path.exists(PREBUILT_DIR):
        print(f"[!] Prebuilt directory not found: {PREBUILT_DIR}")
        print("[!] Build the core module first: ./gradlew :core:assembleDebug")
        return False

    # Copy native libraries
    lib_src = os.path.join(PREBUILT_DIR, "lib")
    lib_dst = os.path.join(decompiled_dir, "lib")
    if os.path.exists(lib_src):
        for abi in os.listdir(lib_src):
            src = os.path.join(lib_src, abi)
            dst = os.path.join(lib_dst, abi)
            os.makedirs(dst, exist_ok=True)
            for so_file in glob.glob(os.path.join(src, "*.so")):
                shutil.copy2(so_file, dst)
                print(f"[+] Copied {os.path.basename(so_file)} -> {dst}")

    # Copy assets
    assets_src = os.path.join(PREBUILT_DIR, "assets")
    assets_dst = os.path.join(decompiled_dir, "assets")
    if os.path.exists(assets_src):
        os.makedirs(assets_dst, exist_ok=True)
        for asset_file in os.listdir(assets_src):
            shutil.copy2(os.path.join(assets_src, asset_file), assets_dst)
            print(f"[+] Copied asset: {asset_file}")

    # Convert DEX to smali and add as next smali_classesN directory
    dex_src = os.path.join(PREBUILT_DIR, "classes.dex")
    if os.path.exists(dex_src):
        if not inject_dex_as_smali(decompiled_dir, dex_src):
            return False

    return True


def inject_dex_as_smali(decompiled_dir: str, dex_path: str) -> bool:
    """Convert a DEX file to smali and add it to the decompiled directory."""
    import subprocess

    # Find the next smali_classesN directory number
    existing = sorted(glob.glob(os.path.join(decompiled_dir, "smali_classes*")))
    if existing:
        # Extract the highest number from smali_classes2, smali_classes3, etc.
        last = os.path.basename(existing[-1])
        num = int(last.replace("smali_classes", "")) if last != "smali" else 1
        next_num = num + 1
    else:
        next_num = 2  # smali is classes.dex, smali_classes2 is classes2.dex

    target_smali_dir = os.path.join(decompiled_dir, f"smali_classes{next_num}")
    print(f"[*] Converting DEX to smali -> {os.path.basename(target_smali_dir)}")

    # Use baksmali (bundled with apktool or standalone)
    # Try java -jar baksmali first, then fallback to apktool's internal baksmali
    # Find baksmali: check PATH, then bundled jar in injector directory
    baksmali = shutil.which("baksmali")
    injector_dir = os.path.dirname(os.path.abspath(__file__))
    baksmali_jar = os.path.join(injector_dir, "baksmali.jar")

    if baksmali:
        cmd = [baksmali, "d", dex_path, "-o", target_smali_dir]
    elif os.path.exists(baksmali_jar):
        cmd = ["java", "-jar", baksmali_jar, "d", dex_path, "-o", target_smali_dir]
    else:
        print("[!] baksmali not found. Place baksmali.jar in the injector/ directory.")
        return False

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] baksmali failed:\n{result.stderr}")
        return False

    smali_count = len(glob.glob(os.path.join(target_smali_dir, "**/*.smali"), recursive=True))
    print(f"[+] Converted DEX to {smali_count} smali files in {os.path.basename(target_smali_dir)}")
    return True


def fix_decompile_artifacts(decompiled_dir: str):
    """Fix common apktool decompilation artifacts that cause runtime crashes."""
    fixed = 0

    # Fix @null references in bitmap/drawable XML files
    # apktool sometimes decompiles resource references as @null which crash at inflate time
    for xml_file in glob.glob(os.path.join(decompiled_dir, "res", "drawable*", "*.xml")):
        try:
            with open(xml_file, "r") as f:
                content = f.read()

            if "@null" not in content:
                continue

            original = content
            # <bitmap android:src="@null" /> → <color android:color="@android:color/transparent" />
            content = re.sub(
                r'<bitmap\s+android:src="@null"\s*/>',
                '<color android:color="@android:color/transparent" />',
                content
            )
            # android:drawable="@null" → android:drawable="@android:color/transparent"
            content = content.replace(
                'android:drawable="@null"',
                'android:drawable="@android:color/transparent"'
            )

            if content != original:
                with open(xml_file, "w") as f:
                    f.write(content)
                fixed += 1
        except Exception:
            pass

    if fixed > 0:
        print(f"[+] Fixed @null references in {fixed} drawable XML files")


def patch_manifest(manifest_path: str) -> bool:
    """Add required permissions and SettingsActivity to the manifest."""
    with open(manifest_path, "r") as f:
        content = f.read()

    # Set extractNativeLibs to true (required for injected .so files)
    if 'android:extractNativeLibs="false"' in content:
        content = content.replace(
            'android:extractNativeLibs="false"',
            'android:extractNativeLibs="true"'
        )
        print("[+] Set extractNativeLibs=true")

    # Add SYSTEM_ALERT_WINDOW permission if not present
    if "SYSTEM_ALERT_WINDOW" not in content:
        content = content.replace(
            "<application",
            '<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n\n    <application',
            1
        )
        print("[+] Added SYSTEM_ALERT_WINDOW permission")

    # Add SettingsActivity if not present
    if "com.adsweep.ui.SettingsActivity" not in content:
        # Insert before </application>
        settings_activity = """
        <activity
            android:name="com.adsweep.ui.SettingsActivity"
            android:exported="true"
            android:label="AdSweep Settings"
            android:theme="@android:style/Theme.DeviceDefault.Light" />"""
        content = content.replace("</application>", settings_activity + "\n    </application>")
        print("[+] Added SettingsActivity to manifest")

    with open(manifest_path, "w") as f:
        f.write(content)

    return True
