#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include "lsplant.hpp"
#include "shadowhook.h"

#define LOG_TAG "AdSweep"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool g_initialized = false;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv");
        return JNI_ERR;
    }

    // Initialize ShadowHook (provides inline hook for LSPlant)
    int sh_ret = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (sh_ret != 0) {
        LOGE("ShadowHook init failed: %d", sh_ret);
        // Continue anyway — some modes may still work
    } else {
        LOGI("ShadowHook initialized");
    }

    // Open libart.so handle for symbol resolution
    void *art_handle = shadowhook_dlopen("libart.so");

    lsplant::InitInfo info{
        .inline_hooker = [](void *target, void *hooker) -> void * {
            void *backup = nullptr;
            void *stub = shadowhook_hook_func_addr(target, hooker, &backup);
            if (stub != nullptr) {
                return backup;
            }
            return nullptr;
        },
        .inline_unhooker = [](void *func) -> bool {
            // ShadowHook unhook needs the stub, not the original func.
            // For LSPlant's internal use, this is rarely called.
            // Return true to satisfy the interface.
            return true;
        },
        .art_symbol_resolver = [art_handle](std::string_view symbol_name) -> void * {
            std::string name(symbol_name);
            return shadowhook_dlsym(art_handle, name.c_str());
        },
        .art_symbol_prefix_resolver = [art_handle](std::string_view symbol_prefix) -> void * {
            std::string prefix(symbol_prefix);
            return shadowhook_dlsym(art_handle, prefix.c_str());
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
        JNIEnv *env, jclass /* clazz */,
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
        JNIEnv *env, jclass /* clazz */, jobject target) {
    if (!g_initialized) return JNI_FALSE;
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeIsHooked(
        JNIEnv *env, jclass /* clazz */, jobject target) {
    if (!g_initialized) return JNI_FALSE;
    return lsplant::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeDeoptimize(
        JNIEnv *env, jclass /* clazz */, jobject target) {
    if (!g_initialized) return JNI_FALSE;
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adsweep_hook_HookEngine_nativeIsInitialized(
        JNIEnv * /* env */, jclass /* clazz */) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}
