"""
AdSweep Injector - Configuration and tool paths.
"""
import os
import shutil

# Android SDK paths (macOS defaults)
ANDROID_HOME = os.path.expanduser("~/Library/Android/sdk")
BUILD_TOOLS_VERSION = "36.1.0"

APKTOOL = shutil.which("apktool") or "/opt/homebrew/bin/apktool"
ZIPALIGN = os.path.join(ANDROID_HOME, "build-tools", BUILD_TOOLS_VERSION, "zipalign")
APKSIGNER = os.path.join(ANDROID_HOME, "build-tools", BUILD_TOOLS_VERSION, "apksigner")
ADB = os.path.join(ANDROID_HOME, "platform-tools", "adb")

# Default keystore
DEFAULT_KEYSTORE = os.path.join(os.path.dirname(os.path.dirname(__file__)), "debug.keystore")
DEFAULT_KS_PASS = "android"
DEFAULT_KEY_ALIAS = "debugkey"
DEFAULT_KEY_PASS = "android"

# Prebuilt payload directory
PREBUILT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "prebuilt")


def validate_tools():
    """Check that all required tools are available."""
    missing = []
    for name, path in [("apktool", APKTOOL), ("zipalign", ZIPALIGN), ("apksigner", APKSIGNER)]:
        if not os.path.isfile(path) and not shutil.which(name):
            missing.append(f"  {name}: expected at {path}")
    if missing:
        print("ERROR: Missing required tools:")
        print("\n".join(missing))
        print(f"\nMake sure Android SDK build-tools {BUILD_TOOLS_VERSION} is installed.")
        return False
    return True
