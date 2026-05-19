/**
 * KailLocation Root Hook Library
 * Automatically initializes when injected into a process via ptrace/dlopen.
 * Gets JNIEnv, loads minimal Hook class, and exits thread.
 * Global refs ensure loaded classes survive GC without keeping the thread alive.
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

#define LOG_TAG "KailRootHook"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static volatile int g_initialized = 0;
static pthread_mutex_t g_init_mutex = PTHREAD_MUTEX_INITIALIZER;

// Global refs to prevent GC from unloading DexClassLoader and our classes.
// These persist across the lifetime of the process — no thread needed.
static jobject g_dex_class_loader = nullptr;
static jobject g_hook_class = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    ALOGI("JNI_OnLoad called");
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static bool find_java_vm() {
    typedef jint (*JNI_GetCreatedJavaVMs_t)(JavaVM**, jsize, jsize*);
    JNI_GetCreatedJavaVMs_t get_vms = nullptr;

    void* handle = dlopen("libart.so", RTLD_NOW);
    if (handle) {
        get_vms = (JNI_GetCreatedJavaVMs_t)dlsym(handle, "JNI_GetCreatedJavaVMs");
    }
    if (!get_vms) {
        handle = dlopen("libnativehelper.so", RTLD_NOW);
        if (handle) {
            get_vms = (JNI_GetCreatedJavaVMs_t)dlsym(handle, "JNI_GetCreatedJavaVMs");
        }
    }
    if (!get_vms) {
        get_vms = (JNI_GetCreatedJavaVMs_t)dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    }
    if (!get_vms) {
        ALOGE("JNI_GetCreatedJavaVMs not found");
        return false;
    }

    JavaVM* vms[2];
    jsize count = 0;
    jint ret = get_vms(vms, 2, &count);
    if (ret != JNI_OK || count == 0) {
        ALOGE("No JavaVM found, ret=%d count=%d", ret, count);
        return false;
    }

    g_vm = vms[0];
    ALOGI("Found JavaVM: %p", (void*)g_vm);
    return true;
}

static char* get_process_name() {
    static char name[256] = {0};
    FILE* fp = fopen("/proc/self/cmdline", "r");
    if (fp) {
        fgets(name, sizeof(name), fp);
        fclose(fp);
    }
    for (int i = 0; i < sizeof(name); i++) {
        if (name[i] == '\n' || name[i] == '\0') {
            name[i] = '\0';
            break;
        }
    }
    return name;
}

static bool extract_lhooker_from_apk(const char* apk_path) {
    const char* abi =
#ifdef __aarch64__
        "arm64-v8a";
#else
        "armeabi-v7a";
#endif
    const char* dest = "/data/local/kail-lib/libkail_lhooker.so";

    if (access(dest, R_OK) == 0) {
        ALOGI("libkail_lhooker.so already exists at %s", dest);
        return true;
    }

    // Ensure dest directory exists
    mkdir("/data/local/kail-lib", 0777);

    // Try unzip command (available on most Android devices)
    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
        "unzip -p '%s' 'lib/%s/libkail_lhooker.so' > '%s' && chmod 755 '%s'",
        apk_path, abi, dest, dest);

    ALOGI("Extracting lhooker: %s", cmd);
    FILE* fp = popen(cmd, "r");
    if (fp) {
        int status = pclose(fp);
        if (status == 0 && access(dest, R_OK) == 0) {
            ALOGI("Extracted libkail_lhooker.so successfully");
            return true;
        }
    }

    // Fallback: try with busybox unzip or toybox
    snprintf(cmd, sizeof(cmd),
        "busybox unzip -p '%s' 'lib/%s/libkail_lhooker.so' > '%s' && chmod 755 '%s'",
        apk_path, abi, dest, dest);
    fp = popen(cmd, "r");
    if (fp) {
        int status = pclose(fp);
        if (status == 0 && access(dest, R_OK) == 0) {
            ALOGI("Extracted libkail_lhooker.so via busybox");
            return true;
        }
    }

    ALOGE("Failed to extract libkail_lhooker.so from APK");
    return false;
}

static void ensure_lhooker_loaded(const char* apk_path) {
    const char* lhooker_path = "/data/local/kail-lib/libkail_lhooker.so";

    // Try dlopen directly if already extracted
    if (access(lhooker_path, R_OK) == 0) {
        void* handle = dlopen(lhooker_path, RTLD_NOW);
        if (handle) {
            ALOGI("Pre-loaded libkail_lhooker.so via dlopen");
            return;
        }
        ALOGE("dlopen failed: %s", dlerror());
    }

    // Extract from APK
    if (extract_lhooker_from_apk(apk_path)) {
        void* handle = dlopen(lhooker_path, RTLD_NOW);
        if (handle) {
            ALOGI("Pre-loaded libkail_lhooker.so after extraction");
        } else {
            ALOGE("dlopen after extraction failed: %s", dlerror());
        }
    }
}

static bool load_and_init(JNIEnv* env) {
    const char* dex_paths[] = {
        "/data/local/kail-lib/kail_location.apk",
        "/data/local/tmp/kail_location.apk",
        "/data/local/tmp/kail_location.dex",
        "/data/local/kail-lib/kail_location.dex",
        nullptr
    };

    const char* found_dex = nullptr;
    for (int i = 0; dex_paths[i]; i++) {
        if (access(dex_paths[i], R_OK) == 0) {
            found_dex = dex_paths[i];
            break;
        }
    }
    if (!found_dex) {
        ALOGE("No DEX/Apk found in known paths");
        return false;
    }
    ALOGI("Found DEX: %s", found_dex);

    // Pre-extract and pre-load libkail_lhooker.so so Java System.loadLibrary finds it
    ensure_lhooker_loaded(found_dex);

    jclass classLoaderClass = env->FindClass("dalvik/system/DexClassLoader");
    if (!classLoaderClass) {
        ALOGE("DexClassLoader not found");
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }

    jmethodID constructor = env->GetMethodID(classLoaderClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!constructor) {
        ALOGE("DexClassLoader constructor not found");
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }

    const char* opt_dir = "/data/local/kail-lib/kail_dexopt";
    mkdir(opt_dir, 0777);

    jstring dexPath = env->NewStringUTF(found_dex);
    jstring optDir = env->NewStringUTF(opt_dir);
    jstring libDir = env->NewStringUTF("/data/local/kail-lib");

    // Get system classloader
    jobject systemClassLoader = nullptr;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass) {
        jmethodID getClassLoader = env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
        if (getClassLoader) {
            systemClassLoader = env->CallObjectMethod(classClass, getClassLoader);
        }
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); systemClassLoader = nullptr; }
    }
    ALOGI("System classloader: %p", (void*)systemClassLoader);

    jobject dexClassLoader = env->NewObject(classLoaderClass, constructor, dexPath, optDir, libDir, systemClassLoader);
    if (!dexClassLoader) {
        ALOGE("Failed to create DexClassLoader");
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }
    ALOGI("DexClassLoader created: %p", (void*)dexClassLoader);

    // Determine which class to load based on process
    char* process_name = get_process_name();
    ALOGI("Process name: %s", process_name);

    const char* entry_class_name;
    const char* entry_method_name;
    const char* entry_method_sig;

    if (strcmp(process_name, "system_server") == 0 || strcmp(process_name, "android") == 0 || strcmp(process_name, "system") == 0) {
        ALOGI("system_server process -> loading SystemLocationHook (no context needed)");
        entry_class_name = "com.kail.location.root.SystemLocationHook";
        entry_method_name = "init";
        entry_method_sig = "()V";
    } else if (strcmp(process_name, "com.android.location.fused") == 0) {
        ALOGI("fused location process -> loading FusedLocationHook (no context needed)");
        entry_class_name = "com.kail.location.root.FusedLocationHook";
        entry_method_name = "init";
        entry_method_sig = "()V";
    } else if (strcmp(process_name, "com.android.phone") == 0) {
        ALOGI("phone process -> loading TelephonyServiceHook (no context needed)");
        entry_class_name = "com.kail.location.root.TelephonyServiceHook";
        entry_method_name = "init";
        entry_method_sig = "()V";
    } else {
        ALOGI("App process (%s) -> loading RootHookEntry (needs context)", process_name);
        entry_class_name = "com.kail.location.root.RootHookEntry";
        entry_method_name = "init";
        entry_method_sig = "(Landroid/content/Context;)V";
    }

    jmethodID loadClass = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring className = env->NewStringUTF(entry_class_name);
    jclass hookClass = (jclass)env->CallObjectMethod(dexClassLoader, loadClass, className);
    if (!hookClass) {
        ALOGE("Failed to load %s", entry_class_name);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }
    ALOGI("Loaded %s: %p", entry_class_name, (void*)hookClass);

    jmethodID initMethod = env->GetStaticMethodID(hookClass, entry_method_name, entry_method_sig);
    if (!initMethod) {
        ALOGE("Method %s%s not found in %s", entry_method_name, entry_method_sig, entry_class_name);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        return false;
    }

    if (strcmp(entry_method_sig, "(Landroid/content/Context;)V") == 0) {
        // Need context for RootHookEntry
        jobject context = nullptr;
        jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
        if (activityThreadClass) {
            jmethodID currentAT = env->GetStaticMethodID(activityThreadClass, "currentActivityThread", "()Landroid/app/ActivityThread;");
            if (currentAT) {
                jobject activityThread = env->CallStaticObjectMethod(activityThreadClass, currentAT);
                if (activityThread) {
                    jmethodID getSystemContext = env->GetMethodID(activityThreadClass, "getSystemContext", "()Landroid/app/ContextImpl;");
                    if (getSystemContext) {
                        context = env->CallObjectMethod(activityThread, getSystemContext);
                    }
                }
            }
        }
        ALOGI("Context: %p", (void*)context);
        env->CallStaticVoidMethod(hookClass, initMethod, context);
    } else {
        // No context needed (SystemLocationHook)
        env->CallStaticVoidMethod(hookClass, initMethod);
    }

    if (env->ExceptionCheck()) {
        ALOGE("Exception in %s.%s()", entry_class_name, entry_method_name);
        jthrowable exc = env->ExceptionOccurred();
        env->ExceptionClear();
        // Try to get exception message via toString()
        if (exc) {
            jclass excClass = env->GetObjectClass(exc);
            jmethodID toString = env->GetMethodID(excClass, "toString", "()Ljava/lang/String;");
            if (toString) {
                jstring msg = (jstring)env->CallObjectMethod(exc, toString);
                if (msg) {
                    const char* cmsg = env->GetStringUTFChars(msg, nullptr);
                    ALOGE("Exception message: %s", cmsg);
                    env->ReleaseStringUTFChars(msg, cmsg);
                }
            }
            // Print stack trace via printStackTrace()
            jmethodID printStackTrace = env->GetMethodID(excClass, "printStackTrace", "()V");
            if (printStackTrace) {
                env->CallVoidMethod(exc, printStackTrace);
            }
            env->DeleteLocalRef(exc);
        }
        return false;
    }
    ALOGI("%s.%s() returned successfully", entry_class_name, entry_method_name);

    // CRITICAL: Save global refs to prevent GC from collecting our classes.
    // These survive without any thread - the JNI global ref table holds them.
    g_dex_class_loader = env->NewGlobalRef(dexClassLoader);
    g_hook_class = env->NewGlobalRef(hookClass);
    ALOGI("Global refs saved: dexClassLoader=%p hookClass=%p", (void*)g_dex_class_loader, (void*)g_hook_class);

    return true;
}

static void* init_thread(void* arg) {
    ALOGI("Init thread started, pid=%d", getpid());
    usleep(3000 * 1000); // 3 seconds

    if (!find_java_vm()) {
        ALOGE("Failed to find JavaVM");
        return nullptr;
    }

    JNIEnv* env = nullptr;
    jint ret = g_vm->AttachCurrentThread(&env, nullptr);
    if (ret != JNI_OK || env == nullptr) {
        ALOGE("AttachCurrentThread failed: %d", ret);
        return nullptr;
    }
    ALOGI("Attached to JVM, env=%p", (void*)env);

    bool success = load_and_init(env);
    ALOGI("load_and_init result: %s", success ? "SUCCESS" : "FAILED");

    // IMPORTANT: Detach from JVM. Global refs keep our objects alive.
    // No thread needs to stay alive — Binder callbacks run on system_server's
    // own threads, and ScheduledExecutorService for config polling runs in Java.
    g_vm->DetachCurrentThread();
    ALOGI("Detached from JVM, thread exiting cleanly");

    return nullptr;
}

__attribute__((constructor))
static void kail_root_hook_init() {
    pthread_mutex_lock(&g_init_mutex);
    if (g_initialized) {
        pthread_mutex_unlock(&g_init_mutex);
        return;
    }
    g_initialized = 1;
    pthread_mutex_unlock(&g_init_mutex);

    if (access("/proc/self/maps", R_OK) == 0) {
        FILE* fp = fopen("/proc/self/maps", "r");
        if (fp) {
            char line[512];
            bool has_art = false;
            while (fgets(line, sizeof(line), fp)) {
                if (strstr(line, "libart.so")) {
                    has_art = true;
                    break;
                }
            }
            fclose(fp);
            if (!has_art) {
                ALOGI("Not a Java process, skipping init");
                return;
            }
        }
    }

    ALOGI("kail_root_hook_init() called in pid %d", getpid());

    pthread_t tid;
    pthread_create(&tid, nullptr, init_thread, nullptr);
    pthread_detach(tid);
}

// Explicit reinit for when dlopen constructor won't fire again (so already loaded)
extern "C" __attribute__((visibility("default"))) void kail_reinit() {
    pthread_mutex_lock(&g_init_mutex);
    if (g_initialized >= 1) {
        pthread_mutex_unlock(&g_init_mutex);
        ALOGI("kail_reinit() skipped: already initialized (g_initialized=%d)", g_initialized);
        return;
    }
    g_initialized = 1;
    pthread_mutex_unlock(&g_init_mutex);
    ALOGI("kail_reinit() called in pid %d", getpid());
    pthread_t tid;
    pthread_create(&tid, nullptr, init_thread, nullptr);
    pthread_detach(tid);
}

__attribute__((destructor))
static void kail_root_hook_fini() {
    ALOGI("kail_root_hook_fini() called");
}