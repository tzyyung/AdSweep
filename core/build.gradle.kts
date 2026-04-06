plugins {
    id("com.android.library")
}

android {
    namespace = "com.adsweep"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20")
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.lsposed.lsplant:lsplant-standalone:6.4")
}
