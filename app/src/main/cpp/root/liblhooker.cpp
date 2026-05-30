// liblhooker.cpp
//
// Reconstructed, compilable source for the ART method-hook engine.
// Recovered from do/complete/liblhooker64.c (arm64) and liblhooker.c (arm).
// This single ABI-aware source covers both arm64-v8a and armeabi-v7a.
//
// LHooker rewrites the entry_point_from_quick_compiled_code field of an
// ArtMethod so calls route through a generated trampoline that loads the
// replacement ArtMethod and jumps to its entry point.  Layout offsets differ
// per Android API level and per ABI and are configured in LHooker_init().
//
// Trampolines (generated into an RWX pool):
//   arm64 (24 bytes, entry at base+4):
//     ldr  x0, #16           ; x0 = ArtMethod* (literal at base+16)
//     ldr  x16, [x0, #off]   ; x16 = method->entry_point_from_quick_code
//     br   x16
//     <ArtMethod*>
//   arm  (16 bytes, entry at base+4):
//     ldr  r0, [pc, #0]      ; r0 = ArtMethod* (literal at base+12)
//     ldr  pc, [r0, #off]    ; jump to method->entry_point_from_quick_code
//     <ArtMethod*>
//
// JNI surface (com.lerist.lib.lhooker.LHooker):
//   init, findMethodNative, hookMethodNative, shouldVisiblyInit.
//
// NOTE: ART internal layout is highly version-sensitive.  The per-API offset
// tables below come directly from the decompilation; validate against the
// target ROM before relying on cross-version behaviour.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <android/log.h>

#include "fakeloc_common.h"

static const char *kTag = "LHooker.Native";

#if defined(__LP64__)
using word_t = uint64_t;     // pointer-sized GOT/entry word
#else
using word_t = uint32_t;
#endif

// ---------------------------------------------------------------------------
// Configuration discovered at init() time.
// ---------------------------------------------------------------------------
static int  gInited           = -1;   // isInited
static int  gSdkInt           = 0;    // SDK_INT
static int  gEntryPointOffset = 0;    // OFFSET_entry_point_from_quick_compiled_code
static int  gHotnessOffset    = 0;    // dword_9830 / dword_6F8C (copied on clone; 0 when unused)
static int  gArtMethodSize    = 0;    // qword_9828 / dword_6F88 (bytes copied when cloning)
static jfieldID gArtMethodField = nullptr;  // qword_9818 / dword_6F84 (Executable.artMethod, SDK>=30)

// Access-flag layout, derived identically on both ABIs:
//   offset:  SDK >= 24 -> +4 else +0          (byte_9824 / byte_6F80)
//   or-bits: SDK >= 27 -> 0x2000000 else 0x1000000  (byte_9820 / byte_6F7C)
//   masking: SDK >= 31 -> 0xFF7FFFFF else 0xFFDFFFFF (byte_9810 / byte_6F78)
static bool gAccessFlag4ByteOffset = false;
static bool gUseExtendedOrBit      = false;
static bool gUseApi31AndMask       = false;

// ---------------------------------------------------------------------------
// Trampoline templates and entry-offset patching.
// ---------------------------------------------------------------------------
#if defined(__LP64__)

// 24-byte block; instruction stream begins at +4, ArtMethod* literal at +16.
//   [0]  padding
//   [4]  0x58000060  ldr x0, #16
//   [8]  0xF8400010  ldr x16, [x0, #imm]   (imm patched via bytes 9..10)
//   [12] 0xD61F0200  br  x16
//   [16] ArtMethod*
static uint8_t gTrampoline[16] = {
    0x00, 0x00, 0x00, 0x00,
    0x60, 0x00, 0x00, 0x58,
    0x10, 0x00, 0x40, 0xf8,
    0x00, 0x02, 0x1f, 0xd6,
};
static const size_t kNoBackupSize = 24;

static void setupTrampoline(int entryOffset) {
  // Mirror the decompiled bit-twiddle that folds the entry offset into the
  // ldr x16,[x0,#imm] immediate (bytes 9 and 10 of the template).
  gTrampoline[9]  |= (uint8_t)(16 * (entryOffset & 0x0f));
  gTrampoline[10] |= (uint8_t)((entryOffset & 0xf0) >> 4);
}

static void *emitTrampoline(uint8_t *slot, uintptr_t method) {
  memcpy(slot, gTrampoline, sizeof(gTrampoline));
  *(uint64_t *)(slot + 16) = (uint64_t)method;
  return slot + 4;
}

#else  // arm

// 16-byte block; instruction stream begins at +4, ArtMethod* literal at +12.
//   [0]  padding
//   [4]  0xE59F0000  ldr r0, [pc, #0]      ; r0 = *(base+12) = ArtMethod*
//   [8]  0xE590F000  ldr pc, [r0, #imm]    ; imm = byte[8] (entry offset)
//   [12] ArtMethod*
static uint8_t gTrampoline[12] = {
    0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x9f, 0xe5,
    0x00, 0xf0, 0x90, 0xe5,
};
static const size_t kNoBackupSize = 16;

static void setupTrampoline(int entryOffset) {
  // Decompiled setupTrampoline stored the entry offset into byte_6C48, which is
  // byte 8 of the block: the low byte of "ldr pc, [r0, #imm]".
  gTrampoline[8] = (uint8_t)entryOffset;
}

static void *emitTrampoline(uint8_t *slot, uintptr_t method) {
  memcpy(slot, gTrampoline, sizeof(gTrampoline));
  *(uint32_t *)(slot + 12) = (uint32_t)method;
  return slot + 4;
}

#endif

// ---------------------------------------------------------------------------
// Trampoline pool allocator  (genTrampoline / RWX pool globals)
// ---------------------------------------------------------------------------
static uintptr_t gPoolCur = 0;
static uintptr_t gPoolEnd = 0;

static void *genTrampoline(uintptr_t method) {
  if (gPoolCur + kNoBackupSize > gPoolEnd) {
    void *page = mmap(nullptr, 0x1000, PROT_READ | PROT_WRITE | PROT_EXEC,
                      MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    if (page == MAP_FAILED) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "mmap failed, errno = %s", strerror(errno));
      gPoolCur = 0;
      return nullptr;
    }
    gPoolCur = (uintptr_t)page;
    gPoolEnd = gPoolCur + 0x1000;
  }

  void *entry = emitTrampoline((uint8_t *)gPoolCur, method);
  __builtin___clear_cache((char *)gPoolCur, (char *)gPoolCur + kNoBackupSize);
  gPoolCur += kNoBackupSize;
  return entry;
}

// ---------------------------------------------------------------------------
// ArtMethod access-flag helpers (setNonCompilable / setPrivate).
// ---------------------------------------------------------------------------
static int accessFlagsOffset() { return gAccessFlag4ByteOffset ? 4 : 0; }

static void setNonCompilable(uintptr_t method) {
  if (gSdkInt < 24)
    return;
  int off = accessFlagsOffset();
  uint32_t flags   = *(uint32_t *)(method + off);
  uint32_t orBits  = gUseExtendedOrBit ? 0x02000000u : 0x01000000u;
  uint32_t andMask = 0xffffffffu;
  if (gSdkInt > 29)
    andMask = gUseApi31AndMask ? 0xff7fffffu : 0xffdfffffu;
  *(uint32_t *)(method + off) = (flags | orBits) & andMask;
}

static void setPrivate(uintptr_t method) {
  int off = accessFlagsOffset();
  uint32_t flags = *(uint32_t *)(method + off);
  if ((flags & 8) == 0)
    *(uint32_t *)(method + off) = (flags & 0xfffffff8u) | 2;
}

// Resolve the native ArtMethod pointer behind a Java reflect Method/Constructor.
static uintptr_t artMethodFromReflected(JNIEnv *env, jobject method) {
  if (!method)
    return 0;
  if (gSdkInt < 30)
    return (uintptr_t)env->FromReflectedMethod(method);
  return (uintptr_t)env->GetLongField(method, gArtMethodField);
}

// installHook (sub_3250): point `target`'s entry at a trampoline to `hook`.
static int installHook(uintptr_t target, uintptr_t hook) {
  void *tramp = genTrampoline(hook);
  if (!tramp) {
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "failed to allocate space for trampoline of target method");
    return 1;
  }

  *(word_t *)(target + gEntryPointOffset) = (word_t)(uintptr_t)tramp;
  if (gHotnessOffset)
    *(word_t *)(target + gHotnessOffset) = *(word_t *)(hook + gHotnessOffset);

  if (gSdkInt >= 26) {
    int off = accessFlagsOffset();
    uint32_t flags = *(uint32_t *)(target + off);
    uint32_t newFlags = flags;
    if (gSdkInt > 28)
      newFlags &= ~0x40000000u;   // clear kAccFastInterpreterToInterpreterInvoke
    if (gSdkInt < 30)
      newFlags |= 0x100u;         // set kAccSkipAccessChecks-ish
    if (newFlags != flags)
      *(uint32_t *)(target + off) = newFlags;
  }
  return 0;
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jint JNICALL
Java_com_lerist_lib_lhooker_LHooker_init(JNIEnv *env, jobject, jint sdkInt) {
  int sig = fakeloc::verifyReleaseSignature(env);
  if (sig != 0 && sig != -2) {
    gInited = -1;
    return -1;
  }

  gSdkInt = sdkInt;
  __android_log_print(ANDROID_LOG_INFO, kTag, "SDK %d", sdkInt);

  // Common access-flag switches.
  gAccessFlag4ByteOffset = (sdkInt >= 24);
  gUseExtendedOrBit      = (sdkInt >= 27);
  gUseApi31AndMask       = (sdkInt >= 31);

  // entry-point offset / ArtMethod size / hotness(copy) offset are ABI-specific.
#if defined(__LP64__)
  switch (sdkInt) {
    case 21: gEntryPointOffset = 40; gArtMethodSize = 72; gHotnessOffset = 24; break;
    case 22: gEntryPointOffset = 56; gArtMethodSize = 64; gHotnessOffset = 40; break;
    case 23: gEntryPointOffset = 48; gArtMethodSize = 56; gHotnessOffset = 32; break;
    case 24:
    case 25: gEntryPointOffset = 48; gArtMethodSize = 56; break;
    case 26:
    case 27: gEntryPointOffset = 40; gArtMethodSize = 48; break;
    case 28:
    case 29: gEntryPointOffset = 32; gArtMethodSize = 40; break;
    case 30: {
      jclass executable = env->FindClass("java/lang/reflect/Executable");
      gArtMethodField = env->GetFieldID(executable, "artMethod", "J");
      gEntryPointOffset = 32; gArtMethodSize = 40;
      break;
    }
    case 31:
    case 32:
    case 33: {
      jclass executable = env->FindClass("java/lang/reflect/Executable");
      gArtMethodField = env->GetFieldID(executable, "artMethod", "J");
      gEntryPointOffset = 24; gArtMethodSize = 32;
      break;
    }
    default:
      __android_log_print(ANDROID_LOG_ERROR, kTag, "Unsupported SDK %d", sdkInt);
      break;
  }
#else
  switch (sdkInt) {
    case 21: gEntryPointOffset = 40; gArtMethodSize = 72; gHotnessOffset = 24; break;
    case 22: gEntryPointOffset = 44; gArtMethodSize = 48; gHotnessOffset = 36; break;
    case 23: gEntryPointOffset = 36; gArtMethodSize = 40; gHotnessOffset = 28; break;
    case 24:
    case 25: gEntryPointOffset = 32; gArtMethodSize = 36; break;
    case 26:
    case 27: gEntryPointOffset = 28; gArtMethodSize = 32; break;
    case 28:
    case 29: gEntryPointOffset = 24; gArtMethodSize = 28; break;
    case 30: {
      jclass executable = env->FindClass("java/lang/reflect/Executable");
      gArtMethodField = env->GetFieldID(executable, "artMethod", "J");
      gEntryPointOffset = 24; gArtMethodSize = 28;
      break;
    }
    case 31:
    case 32:
    case 33: {
      jclass executable = env->FindClass("java/lang/reflect/Executable");
      gArtMethodField = env->GetFieldID(executable, "artMethod", "J");
      gEntryPointOffset = 20; gArtMethodSize = 24;
      break;
    }
    default:
      __android_log_print(ANDROID_LOG_ERROR, kTag, "Unsupported SDK %d", sdkInt);
      break;
  }
#endif

  setupTrampoline(gEntryPointOffset);
  gInited = 0;
  return 0;
}

JNIEXPORT jobject JNICALL
Java_com_lerist_lib_lhooker_LHooker_findMethodNative(
    JNIEnv *env, jobject, jclass clazz, jstring nameStr, jstring sigStr) {
  if (gInited != 0)
    return nullptr;

  const char *name = env->GetStringUTFChars(nameStr, nullptr);
  const char *sig  = env->GetStringUTFChars(sigStr, nullptr);

  jmethodID mid = env->GetMethodID(clazz, name, sig);
  jobject result;
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    jmethodID smid = env->GetStaticMethodID(clazz, name, sig);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      result = nullptr;
    } else {
      result = env->ToReflectedMethod(clazz, smid, JNI_TRUE);
    }
  } else {
    result = env->ToReflectedMethod(clazz, mid, JNI_FALSE);
  }

  env->ReleaseStringUTFChars(nameStr, name);
  env->ReleaseStringUTFChars(sigStr, sig);
  return result;
}

JNIEXPORT jboolean JNICALL
Java_com_lerist_lib_lhooker_LHooker_hookMethodNative(
    JNIEnv *env, jobject, jobject target, jobject hook, jobject backup, jobject backup2) {
  if (gInited != 0)
    return JNI_FALSE;

  uintptr_t targetMethod  = artMethodFromReflected(env, target);
  uintptr_t hookMethod    = artMethodFromReflected(env, hook);
  uintptr_t backupMethod  = backup  ? artMethodFromReflected(env, backup)  : 0;
  uintptr_t backup2Method = backup2 ? artMethodFromReflected(env, backup2) : 0;

  if (gSdkInt >= 24) {
    setNonCompilable(targetMethod);
    setNonCompilable(hookMethod);
    if (backupMethod)  setNonCompilable(backupMethod);
    if (backup2Method) setNonCompilable(backup2Method);
  }

  int rc = 0;
  if (backupMethod) {
    // Clone the target ArtMethod into the backup so the original can still be
    // invoked, then redirect the backup to the hook.
    memcpy((void *)backup2Method, (void *)targetMethod, gArtMethodSize);
    rc += installHook(backupMethod, hookMethod);
    if (gSdkInt >= 30) {
      setPrivate(backupMethod);
      setPrivate(hookMethod);
    }
  }
  rc += installHook(targetMethod, hookMethod);

  __android_log_print(ANDROID_LOG_INFO, kTag, "Hook method done.");
  if (rc != 0)
    return JNI_FALSE;

  env->DeleteLocalRef(hook);
  if (backup)  env->DeleteLocalRef(backup);
  if (backup2) env->DeleteLocalRef(backup2);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_lerist_lib_lhooker_LHooker_shouldVisiblyInit(JNIEnv *, jobject) {
  return gSdkInt > 29 ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
