// libStepSensor.cpp
//
// Reconstructed, compilable source for the step-sensor spoofing library (arm64).
// Recovered from do/complete/libStepSensor.c.
//
// It hooks libsensorservice.so via the ELF PLT/GOT engine in elf_hooker.h so
// that accelerometer / step-detector / step-counter sensor events delivered to
// apps can be overridden with mock values supplied from Java.
//
// Hooked symbols:
//   android::SensorEventQueue::write(...)                 -> new_SensorEventQueue_write
//   android::hardware::sensors::V1_0::implementation::
//       convertToSensorEvent(Event const&, sensors_event_t*) -> new_convertToSensorEvent
//
// JNI surface (registered by name):
//   LStepSensor.doHook(env, license, key)
//   LStepSensor.setMocking(env, enabled)
//   LStepSensor.isMocking()
//   LStepSensor.setSensorValues(env, type, handle, float[])

#include <cstdint>
#include <cstdlib>
#include <ctime>

#include <jni.h>
#include <android/log.h>

#include "elf_hooker.h"
#include "fakeloc_common.h"

using namespace elfhook;

// Sensor type constants used by the framework.
static const int SENSOR_TYPE_ACCELEROMETER = 1;
static const int SENSOR_TYPE_STEP_DETECTOR = 18;
static const int SENSOR_TYPE_STEP_COUNTER  = 19;

// ---------------------------------------------------------------------------
// State (decompiled globals 0x1A0xx)
// ---------------------------------------------------------------------------
static int mSensorHandleAccelerometer = -1;
static int mSensorHandleStepDetector  = -1;
static int mSensorHandleStepCounter   = -1;

// Each mock value is a vec3 (x,y,z); -1 in every component means "unset".
static float mAccelerometer[3] = {-1.f, -1.f, -1.f};
static float mStepDetector[3]  = {-1.f, -1.f, -1.f};
static float mStepCounter[3]   = {-1.f, -1.f, -1.f};

static int  gMocking          = 0;   // isMocking
static int  gAuthorized       = 0;   // isAuthorized
static int  gHooked           = 0;   // isHooked
static int  gStepDetectorTrig = 0;   // stepdetectorTrigger
static int  gStepCounterTrig  = 0;   // stepcounterTrigger

// Saved originals of the hooked functions.
static int64_t (*gOrigSensorEventQueueWrite)(void *, void *, void *, size_t) = nullptr;
static int64_t (*gOrigConvertToSensorEvent)(void *, void *) = nullptr;

// sensors_event_t layout used by the framework (104 bytes per event on arm64):
//   off 0  : int64 version/size header word (treated opaque)
//   off 4  : int32 sensor handle
//   off 8  : int32 sensor type
//   off 24 : float data[...] (sensor payload)
struct SensorEvent {
  uint8_t raw[104];
};

static inline int32_t &eventHandle(uint8_t *e) { return *reinterpret_cast<int32_t *>(e + 4); }
static inline int32_t &eventType(uint8_t *e)   { return *reinterpret_cast<int32_t *>(e + 8); }
static inline float   *eventData(uint8_t *e)   { return reinterpret_cast<float *>(e + 24); }

static bool vecIsSet(const float v[3]) {
  return v[0] != -1.f || v[1] != -1.f || v[2] != -1.f;
}

// ---------------------------------------------------------------------------
// new_SensorEventQueue_write  (sub_3040)
//   Rewrite the payload of each outgoing sensor event with the mock values.
// ---------------------------------------------------------------------------
static int64_t new_SensorEventQueue_write(void *self, void *bitTube, void *events, size_t count) {
  if (gMocking && gAuthorized && events) {
    uint8_t *e = reinterpret_cast<uint8_t *>(events);
    for (size_t i = 0; i < count; ++i, e += sizeof(SensorEvent)) {
      int32_t type = eventType(e);
      float *data = eventData(e);
      if (type == SENSOR_TYPE_STEP_COUNTER) {
        if (vecIsSet(mStepCounter))
          data[0] = mStepCounter[0];
      } else if (type == SENSOR_TYPE_STEP_DETECTOR) {
        if (vecIsSet(mStepDetector)) {
          data[0] = mStepDetector[0];
          data[1] = mStepDetector[1];
        }
      } else if (type == SENSOR_TYPE_ACCELEROMETER) {
        if (vecIsSet(mAccelerometer)) {
          data[0] = mAccelerometer[0];
          data[1] = mAccelerometer[1];
          data[2] = mAccelerometer[2];
        }
      }
    }
  }
  if (gOrigSensorEventQueueWrite)
    return gOrigSensorEventQueueWrite(self, bitTube, events, count);
  __android_log_print(ANDROID_LOG_ERROR, kTag, "failed to get original SensorEventQueue_write");
  return -1;
}

// ---------------------------------------------------------------------------
// new_convertToSensorEvent  (sub_31B4)
//   When a step-detector/counter trigger is pending, retype the next event so
//   a synthetic step is injected into the stream.
// ---------------------------------------------------------------------------
static int64_t new_convertToSensorEvent(void *eventIn, void *eventOut) {
  int64_t result = gOrigConvertToSensorEvent(eventIn, eventOut);
  if (gMocking && gAuthorized) {
    uint8_t *out = reinterpret_cast<uint8_t *>(eventOut);
    if (eventType(out) == 3 /* generic motion */) {
      if (gStepDetectorTrig == 1 && mSensorHandleStepDetector != -1) {
        gStepDetectorTrig = 0;
        eventHandle(out) = mSensorHandleStepDetector;
        eventType(out)   = SENSOR_TYPE_STEP_DETECTOR;
      } else if (gStepCounterTrig == 1 && mSensorHandleStepCounter != -1) {
        gStepCounterTrig = 0;
        eventHandle(out) = mSensorHandleStepCounter;
        eventType(out)   = SENSOR_TYPE_STEP_COUNTER;
        *reinterpret_cast<int64_t *>(out + 24) = 0;
      }
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// doHook  (sub_3354)
//   Resolve libsensorservice.so, parse it and install the two PLT hooks.
// ---------------------------------------------------------------------------
static void doHook() {
  if (!gAuthorized)
    return;

  const char *kSensorLib = "/system/lib64/libsensorservice.so";
  void *base = ElfHooker::getModuleBase(kSensorLib);
  const char *name = kSensorLib;
  if (!base) {
    const char *path = ElfHooker::getModulePath("libsensorservice.so");
    if (!path)
      return;
    __android_log_print(ANDROID_LOG_DEBUG, kTag, "find module %s", path);
    base = ElfHooker::getModuleBase(path);
    if (!base)
      return;
    name = path;
  }

  ElfReader reader(name, base);
  if (reader.parse() != 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "failed to parse %s in %d maps at %p",
                        kSensorLib, getpid(), base);
    return;
  }

  // SensorEventQueue::write has two mangled signatures across versions (m / j).
  if (reader.hook(
          "_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm",
          (void *)new_SensorEventQueue_write, (void **)&gOrigSensorEventQueueWrite) != 0) {
    reader.hook(
        "_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventj",
        (void *)new_SensorEventQueue_write, (void **)&gOrigSensorEventQueueWrite);
  }
  reader.hook(
      "_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEventERKNS2_5EventEP15sensors_event_t",
      (void *)new_convertToSensorEvent, (void **)&gOrigConvertToSensorEvent);

  gHooked = 1;
}

// ---------------------------------------------------------------------------
// de  (decryptLicensePayload): DES-decrypt a Base64 license blob keyed by
//   ("Lerist." + key) and verify the trailing token matches `key`.  Returns the
//   decrypted String[] on success, else null.  Reconstructed via JNI.
// ---------------------------------------------------------------------------
static jobjectArray decryptLicensePayload(JNIEnv *env, jbyteArray license, jstring key) {
  jclass base64 = env->FindClass("android/util/Base64");
  jmethodID decode = env->GetStaticMethodID(base64, "decode", "([BI)[B");
  jbyteArray cipherBytes = (jbyteArray)env->CallStaticObjectMethod(base64, decode, license, 0);
  if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

  jclass secureRandomClass = env->FindClass("java/security/SecureRandom");
  jmethodID srCtor = env->GetMethodID(secureRandomClass, "<init>", "()V");
  jobject secureRandom = env->NewObject(secureRandomClass, srCtor);

  jclass desKeySpecClass = env->FindClass("javax/crypto/spec/DESKeySpec");
  jmethodID desKeySpecCtor = env->GetMethodID(desKeySpecClass, "<init>", "([B)V");

  // keyBytes = ("Lerist." + key).getBytes()
  jclass strClass = env->FindClass("java/lang/String");
  jmethodID concat = env->GetMethodID(strClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
  jstring lerist = env->NewStringUTF("Lerist.");
  jstring fullKey = (jstring)env->CallObjectMethod(lerist, concat, key);
  env->DeleteLocalRef(lerist);
  jmethodID getBytes = env->GetMethodID(strClass, "getBytes", "()[B");
  jbyteArray keyBytes = (jbyteArray)env->CallObjectMethod(fullKey, getBytes);

  jobject desKeySpec = env->NewObject(desKeySpecClass, desKeySpecCtor, keyBytes);
  if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

  jclass skfClass = env->FindClass("javax/crypto/SecretKeyFactory");
  jmethodID skfGet = env->GetStaticMethodID(
      skfClass, "getInstance", "(Ljava/lang/String;)Ljavax/crypto/SecretKeyFactory;");
  jstring des = env->NewStringUTF("DES");
  jobject skf = env->CallStaticObjectMethod(skfClass, skfGet, des);
  jmethodID generateSecret = env->GetMethodID(
      skfClass, "generateSecret", "(Ljava/security/spec/KeySpec;)Ljavax/crypto/SecretKey;");
  jobject secretKey = env->CallObjectMethod(skf, generateSecret, desKeySpec);

  jclass cipherClass = env->FindClass("javax/crypto/Cipher");
  jmethodID cipherGet = env->GetStaticMethodID(
      cipherClass, "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
  jobject cipher = env->CallStaticObjectMethod(cipherClass, cipherGet, des);
  jmethodID cipherInit = env->GetMethodID(
      cipherClass, "init", "(ILjava/security/Key;Ljava/security/SecureRandom;)V");
  env->CallVoidMethod(cipher, cipherInit, 2 /*DECRYPT_MODE*/, secretKey, secureRandom);
  jmethodID doFinal = env->GetMethodID(cipherClass, "doFinal", "([B)[B");
  jbyteArray plain = (jbyteArray)env->CallObjectMethod(cipher, doFinal, cipherBytes);
  if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

  // plaintext = new String(plain); parts = plaintext.split("#")
  jmethodID strCtor = env->GetMethodID(strClass, "<init>", "([B)V");
  jstring plaintext = (jstring)env->NewObject(strClass, strCtor, plain);
  jmethodID split = env->GetMethodID(strClass, "split", "(Ljava/lang/String;)[Ljava/lang/String;");
  jstring sep = env->NewStringUTF("#");
  jobjectArray parts = (jobjectArray)env->CallObjectMethod(plaintext, split, sep);

  jsize n = env->GetArrayLength(parts);
  jstring last = (jstring)env->GetObjectArrayElement(parts, n - 1);
  const char *lastChars = env->GetStringUTFChars(last, nullptr);
  const char *keyChars  = env->GetStringUTFChars(key, nullptr);
  int match = strcmp(lastChars, keyChars);
  env->ReleaseStringUTFChars(last, lastChars);
  env->ReleaseStringUTFChars(key, keyChars);

  return match == 0 ? parts : nullptr;
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jint JNICALL
Java_com_lerist_inject_utils_LStepSensor_doHook(JNIEnv *env, jobject, jbyteArray license, jstring key) {
  gAuthorized = 0;
  if (!license || !key)
    return -1;
  if (fakeloc::verifyReleaseSignature(env) != 0)
    return -2;

  jobjectArray payload = decryptLicensePayload(env, license, key);
  if (!payload)
    return -3;

  // payload[0] = pro indate, payload[1] = key indate (epoch seconds).  Both
  // must be in the future relative to the current clock for authorization.
  clock_t now1 = clock();
  jstring proStr = (jstring)env->GetObjectArrayElement(payload, 1);
  const char *proChars = env->GetStringUTFChars(proStr, nullptr);
  long proIndate = atol(proChars);
  env->ReleaseStringUTFChars(proStr, proChars);
  if (now1 > proIndate)
    return -4;

  clock_t now2 = clock();
  jstring keyStr = (jstring)env->GetObjectArrayElement(payload, 0);
  const char *keyChars = env->GetStringUTFChars(keyStr, nullptr);
  long keyIndate = atol(keyChars);
  env->ReleaseStringUTFChars(keyStr, keyChars);
  if (now2 > keyIndate)
    return -5;

  gAuthorized = 1;
  if (!gHooked)
    doHook();
  return 0;
}

JNIEXPORT void JNICALL
Java_com_lerist_inject_utils_LStepSensor_setMocking(JNIEnv *, jobject, jboolean enabled) {
  gMocking = (gAuthorized != 0 && enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_lerist_inject_utils_LStepSensor_isMocking(JNIEnv *, jobject) {
  return (gHooked != 0 && gMocking != 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lerist_inject_utils_LStepSensor_setSensorValues(
    JNIEnv *env, jobject, jint type, jint handle, jfloatArray values) {
  jfloat *vals = env->GetFloatArrayElements(values, nullptr);
  switch (type) {
    case SENSOR_TYPE_STEP_COUNTER:
      mSensorHandleStepCounter = handle;
      mStepCounter[0] = vals[0];
      mStepCounter[1] = vals[1];
      mStepCounter[2] = vals[2];
      gStepCounterTrig = 1;
      break;
    case SENSOR_TYPE_STEP_DETECTOR:
      mSensorHandleStepDetector = handle;
      mStepDetector[0] = vals[0];
      mStepDetector[1] = vals[1];
      mStepDetector[2] = vals[2];
      gStepDetectorTrig = 1;
      break;
    case SENSOR_TYPE_ACCELEROMETER:
      mSensorHandleAccelerometer = handle;
      mAccelerometer[0] = vals[0];
      mAccelerometer[1] = vals[1];
      mAccelerometer[2] = vals[2];
      break;
    default:
      break;
  }
  env->ReleaseFloatArrayElements(values, vals, 0);
}

}  // extern "C"
