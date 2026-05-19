/**
 * Simplified LHooker - ART Method Hooking Engine
 * Based on FakeLocation's LHooker (2bc5ad15.s.c)
 * Supports Android 7.0+ (SDK 24+)
 */

#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#define LOG_TAG "KailLHooker"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static int g_sdk_level = 0;
static size_t g_art_method_size = 0;
static size_t g_offset_entry_point = 0;
static jfieldID g_art_method_field = nullptr;
static bool g_initialized = false;

// Trampoline pool
static pthread_mutex_t g_trampoline_mutex = PTHREAD_MUTEX_INITIALIZER;

// ArtMethod field names vary by version
// Android 7.0+: java.lang.reflect.Executable has "artMethod" field
// Android 6.0: java.lang.reflect.ArtMethod

struct HookInfo {
    void* target_method;
    void* backup_entry_point;
    void* hook_entry_point;
    void* trampoline;
};

static bool ensure_art_method_field(JNIEnv* env) {
    if (g_art_method_field) return true;

    jclass executableClass = env->FindClass("java/lang/reflect/Executable");
    if (!executableClass) {
        env->ExceptionClear();
        // Fallback for older Android
        jclass artMethodClass = env->FindClass("java/lang/reflect/ArtMethod");
        if (artMethodClass) {
            g_art_method_field = env->GetFieldID(artMethodClass, "artMethod", "J");
        }
        return g_art_method_field != nullptr;
    }

    g_art_method_field = env->GetFieldID(executableClass, "artMethod", "J");
    if (!g_art_method_field) {
        env->ExceptionClear();
        // Try "artMethod" with different signature
        g_art_method_field = env->GetFieldID(executableClass, "artMethod", "J");
    }
    return g_art_method_field != nullptr;
}

static void* get_art_method(JNIEnv* env, jobject method) {
    if (!ensure_art_method_field(env)) {
        ALOGE("Failed to get artMethod field");
        return nullptr;
    }
    jlong artMethod = env->GetLongField(method, g_art_method_field);
    return (void*)artMethod;
}

static bool init_sdk_params(int sdk) {
    g_sdk_level = sdk;
    ALOGI("Initializing LHooker for SDK %d", sdk);

    // ArtMethod size and entry_point offset by SDK
    // These are empirical values from AOSP source
    switch (sdk) {
        case 24: // Android 7.0
        case 25: // Android 7.1
            g_art_method_size = 0x48;
            g_offset_entry_point = 0x28;
            break;
        case 26: // Android 8.0
        case 27: // Android 8.1
            g_art_method_size = 0x40;
            g_offset_entry_point = 0x28;
            break;
        case 28: // Android 9
            g_art_method_size = 0x38;
            g_offset_entry_point = 0x28;
            break;
        case 29: // Android 10
            g_art_method_size = 0x28;
            g_offset_entry_point = 0x20;
            break;
        case 30: // Android 11
        case 31: // Android 12
        case 32: // Android 12L
            g_art_method_size = 0x28;
            g_offset_entry_point = 0x20;
            break;
        case 33: // Android 13
        case 34: // Android 14
        case 35: // Android 15
        case 36: // Android 16
            g_art_method_size = 0x20;
            g_offset_entry_point = 0x18;
            break;
        default:
            if (sdk >= 33) {
                g_art_method_size = 0x20;
                g_offset_entry_point = 0x18;
            } else if (sdk >= 29) {
                g_art_method_size = 0x28;
                g_offset_entry_point = 0x20;
            } else {
                ALOGE("Unsupported SDK %d", sdk);
                return false;
            }
            break;
    }

    ALOGI("ArtMethod size=%zu, entry_point offset=%zu", g_art_method_size, g_offset_entry_point);
    return true;
}

static bool calculate_dynamic_art_method_size(JNIEnv* env) {
    // Use two adjacent methods to calculate ArtMethod size dynamically
    jclass nsmeClass = env->FindClass("java/lang/NoSuchMethodException");
    if (!nsmeClass) {
        env->ExceptionClear();
        return false;
    }

    jmethodID init1 = env->GetMethodID(nsmeClass, "<init>", "()V");
    jmethodID init2 = env->GetMethodID(nsmeClass, "<init>", "(Ljava/lang/String;)V");

    if (!init1 || !init2) {
        env->ExceptionClear();
        return false;
    }

    jobject method1 = env->ToReflectedMethod(nsmeClass, init1, JNI_FALSE);
    jobject method2 = env->ToReflectedMethod(nsmeClass, init2, JNI_FALSE);

    if (!method1 || !method2) {
        env->ExceptionClear();
        return false;
    }

    void* art1 = get_art_method(env, method1);
    void* art2 = get_art_method(env, method2);

    if (!art1 || !art2) {
        return false;
    }

    size_t diff = (art1 > art2) ? ((size_t)art1 - (size_t)art2) : ((size_t)art2 - (size_t)art1);
    if (diff > 0 && diff < 0x80) {
        g_art_method_size = diff;
        ALOGI("Dynamic ArtMethod size=%zu", g_art_method_size);
    }

    return true;
}

// ARM64 Trampoline for method replacement
// Layout:
//   ldr x16, [pc, #8]   // Load hook address
//   br x16              // Jump to hook
//   .quad hook_addr     // Hook method entry point
static void* create_trampoline_arm64(void* hook_addr) {
    // 16 bytes: 2 instructions + 8 bytes address
    uint8_t* trampoline = (uint8_t*)mmap(nullptr, 16, PROT_READ | PROT_WRITE | PROT_EXEC,
                                          MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (trampoline == MAP_FAILED) {
        ALOGE("mmap trampoline failed");
        return nullptr;
    }

    // ldr x16, [pc, #8]  => 0x58000050 (imm19=2, Rt=16)
    trampoline[0] = 0x50;
    trampoline[1] = 0x00;
    trampoline[2] = 0x00;
    trampoline[3] = 0x58;

    // br x16  => 0xd61f0200
    trampoline[4] = 0x00;
    trampoline[5] = 0x02;
    trampoline[6] = 0x1f;
    trampoline[7] = 0xd6;

    // nop padding to align address at pc+8
    trampoline[8] = 0x1f;
    trampoline[9] = 0x20;
    trampoline[10] = 0x03;
    trampoline[11] = 0xd5;

    // Hook address
    memcpy(trampoline + 8, &hook_addr, sizeof(void*));

    __builtin___clear_cache((char*)trampoline, (char*)(trampoline + 16));
    return trampoline;
}

// ARM32 Trampoline for method replacement
// Layout:
//   ldr pc, [pc, #-4]   // Load hook address from next word
//   .word hook_addr
static void* create_trampoline_arm32(void* hook_addr) {
    // 8 bytes: 1 instruction + 4 bytes address
    uint8_t* trampoline = (uint8_t*)mmap(nullptr, 8, PROT_READ | PROT_WRITE | PROT_EXEC,
                                          MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (trampoline == MAP_FAILED) {
        ALOGE("mmap trampoline failed (arm32)");
        return nullptr;
    }

    // ldr pc, [pc, #-4] => 0xe51ff004
    trampoline[0] = 0x04;
    trampoline[1] = 0xf0;
    trampoline[2] = 0x1f;
    trampoline[3] = 0xe5;

    // Hook address at offset 4
    memcpy(trampoline + 4, &hook_addr, sizeof(void*));

    __builtin___clear_cache((char*)trampoline, (char*)(trampoline + 8));
    return trampoline;
}

static void* create_trampoline(void* hook_addr) {
#if defined(__aarch64__)
    return create_trampoline_arm64(hook_addr);
#elif defined(__arm__)
    return create_trampoline_arm32(hook_addr);
#else
    ALOGE("Unsupported architecture for trampoline");
    return nullptr;
#endif
}

// Get entry_point_from_quick_compiled_code from ArtMethod
static void* get_entry_point(void* art_method) {
    if (!art_method || g_offset_entry_point == 0) return nullptr;
    void** entry_point_ptr = (void**)((uint8_t*)art_method + g_offset_entry_point);
    return *entry_point_ptr;
}

static void set_entry_point(void* art_method, void* entry_point) {
    if (!art_method || g_offset_entry_point == 0) return;
    void** entry_point_ptr = (void**)((uint8_t*)art_method + g_offset_entry_point);
    *entry_point_ptr = entry_point;
    __builtin___clear_cache((char*)entry_point_ptr, (char*)((uint8_t*)entry_point_ptr + sizeof(void*)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kail_location_root_LHooker_init(JNIEnv* env, jclass clazz, jint sdk) {
    if (g_initialized) return JNI_TRUE;

    if (!init_sdk_params(sdk)) {
        return JNI_FALSE;
    }

    if (!calculate_dynamic_art_method_size(env)) {
        ALOGE("Failed to calculate dynamic ArtMethod size");
    }

    g_initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kail_location_root_LHooker_hookMethod(JNIEnv* env, jclass clazz,
                                                      jobject target_method,
                                                      jobject hook_method,
                                                      jobject backup_method) {
    if (!g_initialized) {
        ALOGE("LHooker not initialized");
        return JNI_FALSE;
    }

    void* target_art = get_art_method(env, target_method);
    void* hook_art = get_art_method(env, hook_method);
    void* backup_art = backup_method ? get_art_method(env, backup_method) : nullptr;

    if (!target_art || !hook_art) {
        ALOGE("Failed to get ArtMethod pointers");
        return JNI_FALSE;
    }

    void* target_entry = get_entry_point(target_art);
    void* hook_entry = get_entry_point(hook_art);

    ALOGI("Target art=%p entry=%p, Hook art=%p entry=%p",
          target_art, target_entry, hook_art, hook_entry);

    if (!target_entry || !hook_entry) {
        ALOGE("Failed to get entry points");
        return JNI_FALSE;
    }

    // CRITICAL: Copy original target ArtMethod to backup so backup stub
    // executes original compiled code (FakeLocation LHooker strategy).
    if (backup_art && g_art_method_size > 0) {
        memcpy(backup_art, target_art, g_art_method_size);
        __builtin___clear_cache((char*)backup_art,
                                (char*)((uint8_t*)backup_art + g_art_method_size));
        ALOGI("Backup ArtMethod copied: %p <- %p size=%zu",
              backup_art, target_art, g_art_method_size);
    }

    // Create trampoline that jumps to hook method
    void* trampoline = create_trampoline(hook_entry);
    if (!trampoline) {
        ALOGE("Failed to create trampoline");
        return JNI_FALSE;
    }

    // Replace target method's entry point with trampoline
    set_entry_point(target_art, trampoline);

    ALOGI("Hooked method: trampoline=%p", trampoline);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kail_location_root_LHooker_findAndHookMethod(JNIEnv* env, jclass clazz,
                                                             jclass target_class,
                                                             jstring method_name,
                                                             jstring method_sig,
                                                             jobject hook_method,
                                                             jobject backup_method) {
    if (!target_class || !method_name) {
        ALOGE("Invalid parameters");
        return JNI_FALSE;
    }

    const char* name = env->GetStringUTFChars(method_name, nullptr);
    const char* sig = method_sig ? env->GetStringUTFChars(method_sig, nullptr) : nullptr;

    jmethodID target_mid = env->GetMethodID(target_class, name, sig ? sig : "()V");
    if (!target_mid) {
        env->ExceptionClear();
        target_mid = env->GetStaticMethodID(target_class, name, sig ? sig : "()V");
    }

    if (!target_mid) {
        env->ExceptionClear();
        ALOGE("Method not found: %s %s", name, sig ? sig : "(null)");
        env->ReleaseStringUTFChars(method_name, name);
        if (sig) env->ReleaseStringUTFChars(method_sig, sig);
        return JNI_FALSE;
    }

    jobject target_method = env->ToReflectedMethod(target_class, target_mid, JNI_FALSE);
    if (!target_method) {
        ALOGE("Failed to reflect target method");
        env->ReleaseStringUTFChars(method_name, name);
        if (sig) env->ReleaseStringUTFChars(method_sig, sig);
        return JNI_FALSE;
    }

    jboolean result = Java_com_kail_location_root_LHooker_hookMethod(
        env, clazz, target_method, hook_method, backup_method);

    env->DeleteLocalRef(target_method);
    env->ReleaseStringUTFChars(method_name, name);
    if (sig) env->ReleaseStringUTFChars(method_sig, sig);

    return result;
}
