// libfakeloc_init.cpp
//
// Reconstructed, compilable source for the generic app init entry library.
// Recovered from do/complete/libfakeloc_init..c (arm64).  The 32-bit behaviour
// is identical at the JNI level, so this single portable source covers both.
//
// Flow:
//   doRun(JavaVM**, arg) -> AttachCurrentThread -> init(env)
//   init(env):
//     - verify payload MD5 and release signature
//     - build a DexClassLoader over /data/fakeloc/libfakeloc.so with an opt
//       directory of /data/fakeloc/system_dex
//     - load com.lerist.inject.fakelocation.InjectDex
//     - call InjectDex.init(context) reflectively

#include "fakeloc_common.h"

using namespace fakeloc;

static const char *kOptDir = "/data/fakeloc/system_dex";
static bool gInitLoaded = false;     // byte_7038

// ---------------------------------------------------------------------------
// init  (sub_2430)
// ---------------------------------------------------------------------------
static void init(JNIEnv *env) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "InitApp is Executing");

  if (verifyApkMd5() != 0)
    return;
  if (!env) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "jni_env is NULL!!");
    return;
  }
  if (verifyReleaseSignature(env) != 0)
    return;

  jstring optDir  = env->NewStringUTF(kOptDir);
  jstring dexPath = env->NewStringUTF(kPayloadPath);

  jclass dclClass = env->FindClass("dalvik/system/DexClassLoader");
  jmethodID dclCtor = env->GetMethodID(
      dclClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
  jmethodID dclLoad = env->GetMethodID(
      dclClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

  jobject context = getGlobalContext(env);
  jclass ctxClass = env->FindClass("android/content/Context");
  jmethodID getCl = env->GetMethodID(ctxClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
  jobject parentLoader = env->CallObjectMethod(context, getCl);

  jobject loader = env->NewObject(dclClass, dclCtor, dexPath, optDir, nullptr, parentLoader);

  jstring injectClassName = env->NewStringUTF("com.lerist.inject.fakelocation.InjectDex");
  jclass injectClass = (jclass)env->CallObjectMethod(loader, dclLoad, injectClassName);

  jmethodID initMethod = env->GetMethodID(
      injectClass, "init", "(Ljava/lang/Object;)[Ljava/lang/Object;");
  env->CallStaticObjectMethod(injectClass, initMethod, context);

  __android_log_print(ANDROID_LOG_INFO, kLogTag, "InitApp is finished.");

  env->DeleteLocalRef(optDir);
  env->DeleteLocalRef(dexPath);
  env->DeleteLocalRef(dclClass);
  env->DeleteLocalRef(context);
  env->DeleteLocalRef(ctxClass);
  env->DeleteLocalRef(parentLoader);
  env->DeleteLocalRef(injectClassName);
}

// ---------------------------------------------------------------------------
// doRun  (sub_2A38)
// ---------------------------------------------------------------------------
extern "C" __attribute__((visibility("default"))) void doRun(JavaVM **vmPtr, const char *arg) {
  (void)arg;
  if (gInitLoaded) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "-- Already loaded");
    return;
  }
  gInitLoaded = true;

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
  if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "AttachCurrentThread (main) != JNI_OK");
    return;
  }
  init(env);
}
