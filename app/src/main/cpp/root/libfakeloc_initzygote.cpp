// libfakeloc_initzygote.cpp
//
// Reconstructed, compilable source for the zygote init entry library.
// Recovered from do/complete/libfakeloc_initzygote.c (arm) and
// libfakeloc_initzygote64.c (arm64); one portable JNI source covers both ABIs.
//
// Flow:
//   doRun(JavaVM**, arg) -> AttachCurrentThread -> init(env)
//   init(env):
//     - verify release signature (allowing -2 "no context yet" in zygote)
//     - build a DexClassLoader over /data/fakeloc/libfakeloc.so with an opt
//       directory of /data/fakeloc/zygote_dex and the system class loader as
//       parent
//     - load com.lerist.inject.fakelocation.InjectDex
//     - call InjectDex.initZygote(systemClassLoader) reflectively

#include "fakeloc_common.h"

using namespace fakeloc;

static const char *kOptDir = "/data/fakeloc/zygote_dex";
static bool gInitLoaded = false;     // byte_5960 / byte_6E28

// ---------------------------------------------------------------------------
// init  (sub_1D34 / sub_23E0)
// ---------------------------------------------------------------------------
static void init(JNIEnv *env) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "InitZygote is Executing");

  if (!env) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "jni_env is NULL!!");
    return;
  }

  int sig = verifyReleaseSignature(env);
  if (sig != 0 && sig != -2)
    return;

  jstring optDir  = env->NewStringUTF(kOptDir);
  jstring dexPath = env->NewStringUTF(kPayloadPath);

  jclass dclClass = env->FindClass("dalvik/system/DexClassLoader");
  jmethodID dclCtor = env->GetMethodID(
      dclClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
  jmethodID dclLoad = env->GetMethodID(
      dclClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

  jobject systemLoader = getSystemClassLoader(env);

  jobject loader = env->NewObject(dclClass, dclCtor, dexPath, optDir, nullptr, systemLoader);

  jstring injectClassName = env->NewStringUTF("com.lerist.inject.fakelocation.InjectDex");
  jclass injectClass = (jclass)env->CallObjectMethod(loader, dclLoad, injectClassName);

  jmethodID initZygote = env->GetMethodID(
      injectClass, "initZygote", "(Ljava/lang/Object;)[Ljava/lang/Object;");
  env->CallStaticObjectMethod(injectClass, initZygote, systemLoader);

  __android_log_print(ANDROID_LOG_INFO, kLogTag, "InitZygote is finished");

  env->DeleteLocalRef(optDir);
  env->DeleteLocalRef(dexPath);
  env->DeleteLocalRef(dclClass);
  env->DeleteLocalRef(systemLoader);
  env->DeleteLocalRef(injectClassName);
}

// ---------------------------------------------------------------------------
// doRun  (sub_205C / sub_2940)
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
