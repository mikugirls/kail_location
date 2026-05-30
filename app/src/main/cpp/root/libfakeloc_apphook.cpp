// libfakeloc_apphook.cpp
//
// Reconstructed, compilable source for the app-process hook entry library.
// Recovered from do/complete/libfakeloc_apphook.c (arm) and
// libfakeloc_apphook64.c (arm64); both ABIs are reconstructed from this single
// portable JNI source.
//
// Flow:
//   doRun(JavaVM**, arg)  -- called by the ptrace injector inside the target
//     -> AttachCurrentThread, then init(env)
//   init(env):
//     - verify payload MD5 and release signature
//     - build a DexClassLoader over /data/fakeloc/libfakeloc.so
//     - load com.lerist.inject.fakelocation.InjectDex
//     - call InjectDex.hookApplication(context) reflectively

#include "fakeloc_common.h"

using namespace fakeloc;

static bool gAppHookLoaded = false;     // byte_5D48 / byte_74A8

// ---------------------------------------------------------------------------
// init  (sub_2030 / sub_289C)
// ---------------------------------------------------------------------------
static void init(JNIEnv *env) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "AppHook is Executing");

  if (verifyApkMd5() != 0)
    return;
  if (!env) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "jni_env is NULL!!");
    return;
  }
  if (verifyReleaseSignature(env) != 0)
    return;

  jobject context = getGlobalContext(env);

  jclass ctxClass = env->FindClass("android/content/Context");
  jmethodID getPkgName = env->GetMethodID(ctxClass, "getPackageName", "()Ljava/lang/String;");
  jstring pkgName = (jstring)env->CallObjectMethod(context, getPkgName);

  const char *pkgChars = env->GetStringUTFChars(pkgName, nullptr);
  jstring dataDir = concatString(env, "/data/data/", pkgChars);
  env->ReleaseStringUTFChars(pkgName, pkgChars);

  jstring dexPath  = env->NewStringUTF(kPayloadPath);
  jstring optDir   = dataDir;

  jclass dclClass = env->FindClass("dalvik/system/DexClassLoader");
  jmethodID dclCtor = env->GetMethodID(
      dclClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
  jmethodID dclLoad = env->GetMethodID(
      dclClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

  jmethodID getCl = env->GetMethodID(ctxClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
  jobject parentLoader = env->CallObjectMethod(context, getCl);

  jobject loader = env->NewObject(dclClass, dclCtor, dexPath, optDir, nullptr, parentLoader);

  jstring injectClassName = env->NewStringUTF("com.lerist.inject.fakelocation.InjectDex");
  jclass injectClass = (jclass)env->CallObjectMethod(loader, dclLoad, injectClassName);

  jmethodID hookApp = env->GetMethodID(
      injectClass, "hookApplication", "(Ljava/lang/Object;)[Ljava/lang/Object;");
  // hookApplication is reflectively invoked on a freshly default-constructed
  // InjectDex instance; the original called it as a static-style helper.
  env->CallStaticObjectMethod(injectClass, hookApp, context);

  __android_log_print(ANDROID_LOG_INFO, kLogTag, "AppHook is finished");

  env->DeleteLocalRef(context);
  env->DeleteLocalRef(ctxClass);
  env->DeleteLocalRef(pkgName);
  env->DeleteLocalRef(dexPath);
  env->DeleteLocalRef(optDir);
  env->DeleteLocalRef(dclClass);
  env->DeleteLocalRef(parentLoader);
  env->DeleteLocalRef(injectClassName);
}

// ---------------------------------------------------------------------------
// doRun  (sub_23D4 / sub_2EA0) -- exported entry point used by the injector.
// ---------------------------------------------------------------------------
extern "C" __attribute__((visibility("default"))) void doRun(JavaVM **vmPtr, const char *arg) {
  (void)arg;
  if (gAppHookLoaded) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "-- Already loaded");
    return;
  }
  if (!vmPtr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "JavaVM** == NULL");
    return;
  }
  JavaVM *vm = *vmPtr;
  if (!vm) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "JavaVM* == NULL");
    return;
  }

  JNIEnv *env = nullptr;
  if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "AttachCurrentThread (main) != JNI_OK");
  else
    init(env);

  gAppHookLoaded = true;
}
