plugins {
    id("com.android.application")
}

android {
    namespace = "com.adsweep.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adsweep.manager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // apktool for on-device APK decompile/recompile
    implementation("org.apktool:apktool-lib:2.9.3")
    // smali/baksmali for DEX ↔ smali
    implementation("com.android.tools.smali:smali-baksmali:3.0.7")
    implementation("com.android.tools.smali:smali:3.0.7")
    // APK signing
    implementation("com.android.tools.build:apksig:8.7.3")
}
