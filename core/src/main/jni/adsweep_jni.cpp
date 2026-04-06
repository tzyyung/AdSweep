#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include "lsplant.hpp"

#define LOG_TAG "AdSweep"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool g_initialized = false;

// Simple inline hook using function pointer replacement
// LSPlant needs an inline hooker for its internal ART method manipulation.
// We use a minimal approach: LSPlant's standalone variant handles this internally
// when possible. For cases where it doesn't, we provide a dlsym-based resolver.

static void *art_handle = nullptr;

static void *art_symbol_resolver(const char *symbol) {
    if (!art_handle) {
        art_handle = dlopen("libart.so", RTLD_NOW);
    }
    if (art_handle) {
        return dlsym(art_handle, symbol);
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv");
        return JNI_ERR;
    }

    // Initialize LSPlant
    lsplant::InitInfo info{
        .inline_hooker = [](auto *target, auto *hooker) -> void * {
            // LSPlant standalone handles inline hooking internally
            // This is a placeholder — the actual inline hook mechanism
            // depends on the LSPlant version and configuration
            return nullptr;
        },
        .inline_unhooker = [](auto *func) -> bool {
            return false;
        },
        .art_symbol_resolver = [](auto *handle, const char *symbol) -> void * {
            return art_symbol_resolver(symbol);
        },
        .art_symbol_prefix_resolver = [](auto *handle, const char *prefix) -> void * {
            return art_symbol_resolver(prefix);
        },
    };

    g_initialized = lsplant::Init(env, info);
    if (g_initialized) {
        LOGI("LSPlant initialized successfully");
    } else {
        LOGE("LSPlant initialization failed");
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_adsweep_hook_HookEngine_nativeHook(
        JNIEnv *env, jclass clazz,
        jobject target, jobject callback, jobject callbackMethod) {
    if (!g_initialized) {
        LOGE("Hook failed: LSPlant not initialized");
        return nullptr;
    }

    jobject backup = lsplant::Hook(env, target, callback, callbackMethod);
    if (backup) {
        LOGI("Hook installed successfully");
    } else {
        LOGE("Hook installation failed");
    }
    return backup;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeUnhook(
        JNIEnv *env, jclass clazz, jobject target) {
    if (!g_initialized) return JNI_FALSE;
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeIsHooked(
        JNIEnv *env, jclass clazz, jobject target) {
    if (!g_initialized) return JNI_FALSE;
    return lsplant::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeDeoptimize(
        JNIEnv *env, jclass clazz, jobject target) {
    if (!g_initialized) return JNI_FALSE;
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeIsInitialized(
        JNIEnv *env, jclass clazz) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}
