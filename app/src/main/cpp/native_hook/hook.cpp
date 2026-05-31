#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dobby.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <sys/types.h>
#include <cinttypes>
#include <cmath>

#include "sensor_simulator.h"
#include "kail_log.h"

#define LOG_TAG "KailNativeSensor"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 统一日志标签（带 [file:line#func] 定位信息，见 kail_log.h）。
static const char *kHookTag = "NativeSensorHook.native";

#define SENSOR_TYPE_ACCELEROMETER 1
#define SENSOR_TYPE_LINEAR_ACCELERATION 10
#define SENSOR_TYPE_STEP_COUNTER 19
#define SENSOR_TYPE_STEP_DETECTOR 18

#define EVENT_SIZE 0x68

typedef void (*SendObjectsFunc)(long*, void*, long, long);
static SendObjectsFunc original_send_objects = nullptr;
static bool send_objects_hook_installed = false;
static bool route_simulation_active = false;
static uint64_t send_objects_offset = 0;

typedef void (*ConvertToSensorEventFunc)(void* param_1, void* param_2);
static ConvertToSensorEventFunc original_convert_to_sensor_event = nullptr;
static bool convert_to_sensor_event_hook_installed = false;
static uint64_t convert_to_sensor_event_offset = 0x5b420;

static int stepdetectorTrigger = 0;
static int stepcounterTrigger = 0;
static int mSensorHandleStepDetector = -1;
static int mSensorHandleStepCounter = -1;
static int isMocking = 0;
static int isAuthorized = 0;
static int step_sim_enabled = 1;
static float current_spm = 120.0f;
static int step_event_counter = 0;

// --- Cadence-accurate, time-based step synthesis (convertToSensorEvent path) ---
// The old logic turned EVERY light-sensor (type 5) event into a step event, so
// the emitted step rate tracked the light-sensor event rate instead of the
// configured cadence — that was the source of the large spm-vs-actual error.
// Instead we gate emission on REAL elapsed time: at cadence `current_spm`, one
// step is due every (60000/spm) ms. We use the sensor event's own monotonic
// timestamp (ns) as the clock, accumulate the exact fractional step debt, and
// emit a step-detector pulse + advance a cumulative step-counter only when a
// whole step is actually due.
static int64_t step_last_ts_ns = 0;     // timestamp of last processed trigger event
static double  step_debt = 0.0;         // fractional steps owed (0..1+)
static uint64_t step_count_total = 0;   // cumulative spoofed step counter
static bool    step_have_base = false;  // captured the device's real counter base
static int     step_emit_phase = 0;     // 0 = emit detector next, 1 = emit counter next
static const int64_t kStepMaxGapNs = 5LL * 1000000000LL; // clamp idle gaps to 5s

#define ALOGI_TO_FILE(...) ALOGI(__VA_ARGS__)
#define ALOGE_TO_FILE(...) ALOGE(__VA_ARGS__)

void setRouteSimulationActive(bool active) {
    route_simulation_active = active;
    if (!active) {
        gait::SensorSimulator::Get().UpdateParams(120.0f, 0, 0, false);
    }
}

// Reset the cadence-accurate step synthesis state (debt clock + counter base).
// step_count_total is preserved across re-arming within a session so the
// cumulative counter keeps climbing; it is only zeroed on a full reset.
static void resetStepDebtClock(bool zeroCounter) {
    step_last_ts_ns = 0;
    step_debt = 0.0;
    step_emit_phase = 0;
    if (zeroCounter) {
        step_count_total = 0;
        step_have_base = false;
    }
}

extern "C" void hooked_send_objects(long* param_1, void* param_2, long param_3, long param_4) {
    if (!param_2) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    int count = (int)param_3;
    
    if (count <= 0 || count > 1000) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    size_t buffer_size = count * EVENT_SIZE;
    
    if (buffer_size > 65536) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    char* heap_buffer = new char[buffer_size];
    memcpy(heap_buffer, param_2, buffer_size);

    char* ptr = heap_buffer;
    for (int i = 0; i < count; i++) {
        void* event = ptr + i * EVENT_SIZE;
        uintptr_t addr = (uintptr_t)event;
        if (addr < 0x10000) {
            continue;
        }

        if (route_simulation_active) {
            int type = *(int*)((char*)event + 0x08);
            
            int64_t timestamp = *(int64_t*)((char*)event + 0x10);
            
            if (type == SENSOR_TYPE_STEP_COUNTER) {
                uint64_t data0 = *(uint64_t*)((char*)event + 0x18);
                
                sensors_event_t se;
                memset(&se, 0, sizeof(se));
                se.type = type;
                se.timestamp = timestamp;
                se.data[0] = (float)data0;
                
                gait::SensorSimulator::Get().ProcessSensorEvents(&se, 1);
                
                *(uint64_t*)((char*)event + 0x18) = (uint64_t)se.data[0];
            } else {
                float data0 = *(float*)((char*)event + 0x18);
                float data1 = *(float*)((char*)event + 0x1C);
                float data2 = *(float*)((char*)event + 0x20);

                sensors_event_t se;
                memset(&se, 0, sizeof(se));
                se.type = type;
                se.timestamp = timestamp;
                se.data[0] = data0;
                se.data[1] = data1;
                se.data[2] = data2;

                gait::SensorSimulator::Get().ProcessSensorEvents(&se, 1);

                *(float*)((char*)event + 0x18) = se.data[0];
                *(float*)((char*)event + 0x1C) = se.data[1];
                *(float*)((char*)event + 0x20) = se.data[2];
            }
        }
    }

    memcpy(param_2, heap_buffer, buffer_size);
    delete[] heap_buffer;

    if (!original_send_objects) {
        return;
    }

    original_send_objects(param_1, param_2, param_3, param_4);
}

extern "C" void hooked_convert_to_sensor_event(void* param_1, void* param_2) {
    if (!param_2) {
        if (original_convert_to_sensor_event) {
            original_convert_to_sensor_event(param_1, param_2);
        }
        return;
    }

    int sensor_type = *(int*)((char*)param_2 + 0x08);

    if (sensor_type == SENSOR_TYPE_STEP_DETECTOR) {
        mSensorHandleStepDetector = *(int*)((char*)param_2 + 0x04);
    } else if (sensor_type == SENSOR_TYPE_STEP_COUNTER) {
        mSensorHandleStepCounter = *(int*)((char*)param_2 + 0x04);
    } else if (sensor_type == 5) {
        if (mSensorHandleStepDetector == -1) {
            mSensorHandleStepDetector = 0;
        }
        if (mSensorHandleStepCounter == -1) {
            mSensorHandleStepCounter = 0;
        }
    }

    if (original_convert_to_sensor_event) {
        original_convert_to_sensor_event(param_1, param_2);
    }

    // Cadence-accurate step synthesis: retype the carrier (light, type 5)
    // event into a step-detector / step-counter event, but ONLY when a whole
    // step is actually due in real time at the configured cadence. This makes
    // the emitted step rate equal the UI spm regardless of how often the
    // carrier event fires.
    if ((isMocking != 0) && step_sim_enabled && (sensor_type == 5)) {
        int64_t ts = *(int64_t*)((char*)param_2 + 0x10);

        float spm = current_spm;
        if (spm < 1.0f) spm = 1.0f;
        if (spm > 400.0f) spm = 400.0f;
        const double sps = (double)spm / 60.0;

        if (step_last_ts_ns == 0) {
            step_last_ts_ns = ts;
        }
        int64_t delta = ts - step_last_ts_ns;
        if (delta < 0) delta = 0;
        if (delta > kStepMaxGapNs) delta = kStepMaxGapNs; // ignore long idle gaps
        step_last_ts_ns = ts;

        // Accrue exact fractional step debt for the elapsed real time.
        step_debt += (double)delta * 1e-9 * sps;

        if (step_debt >= 1.0) {
            // A whole step is due. Alternate detector / counter emissions so an
            // app listening to either sensor sees a consistent cadence.
            if (step_emit_phase == 0 && mSensorHandleStepDetector != -1) {
                // STEP_DETECTOR pulse. Consumes exactly one step of debt.
                step_debt -= 1.0;
                step_count_total += 1;
                *(int*)((char*)param_2 + 0x04) = mSensorHandleStepDetector;
                *(int*)((char*)param_2 + 0x08) = 0x12; // TYPE_STEP_DETECTOR
                *(float*)((char*)param_2 + 0x18) = 1.0f;
                step_emit_phase = 1;
            } else if (mSensorHandleStepCounter != -1) {
                // STEP_COUNTER cumulative value. Catch up the FULL integer debt
                // here so the counter stays accurate even when the carrier
                // (light) event is sparser than the step cadence — a sparse
                // carrier can't limit the cumulative total this way.
                uint64_t due = (uint64_t)step_debt;
                if (due < 1) due = 1;
                step_debt -= (double)due;
                step_count_total += due;
                *(int*)((char*)param_2 + 0x04) = mSensorHandleStepCounter;
                *(int*)((char*)param_2 + 0x08) = 0x13; // TYPE_STEP_COUNTER
                *(uint64_t*)((char*)param_2 + 0x18) = step_count_total;
                step_emit_phase = 0;
            }
        }
        // If no whole step is due, leave the event as the original (post-hook)
        // light event — we simply don't fabricate a step this tick.
    }
}

static void install_send_objects_hook() {
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        KLOGE(kHookTag, "install_send_objects_hook: cannot open /proc/self/maps");
        return;
    }
    
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libsensor.so")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = (void*)start;
            break;
        }
    }
    fclose(fp);
    
    if (!base) {
        KLOGW(kHookTag, "install_send_objects_hook: libsensor.so not mapped in this process");
        return;
    }
    
    if (send_objects_offset == 0) {
        KLOGW(kHookTag, "install_send_objects_hook: send_objects_offset is 0, skip");
        return;
    }
    
    void* addr = (void*)((char*)base + send_objects_offset);
    
    int ret = DobbyHook(addr, (void*)hooked_send_objects, (void**)&original_send_objects);
    
    if (ret == 0) {
        send_objects_hook_installed = true;
        KLOGI(kHookTag, "install_send_objects_hook: hooked libsensor.so send_objects at %p (base=%p off=0x%llx)",
              addr, base, (unsigned long long)send_objects_offset);
    } else {
        KLOGE(kHookTag, "install_send_objects_hook: DobbyHook failed rc=%d at %p", ret, addr);
    }
}

static void install_convert_to_sensor_event_hook() {
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        KLOGE(kHookTag, "install_convert_to_sensor_event_hook: cannot open /proc/self/maps");
        return;
    }
    
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libsensorservice.so")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = (void*)start;
            break;
        }
    }
    fclose(fp);
    
    if (!base) {
        KLOGW(kHookTag, "install_convert_to_sensor_event_hook: libsensorservice.so not mapped in this process");
        return;
    }
    
    if (convert_to_sensor_event_offset == 0) {
        KLOGW(kHookTag, "install_convert_to_sensor_event_hook: offset is 0, skip");
        return;
    }
    
    void* addr = (void*)((char*)base + convert_to_sensor_event_offset);
    
    int ret = DobbyHook(addr, (void*)hooked_convert_to_sensor_event, (void**)&original_convert_to_sensor_event);
    
    if (ret == 0) {
        convert_to_sensor_event_hook_installed = true;
        KLOGI(kHookTag, "install_convert_to_sensor_event_hook: hooked libsensorservice.so at %p (base=%p off=0x%llx)",
              addr, base, (unsigned long long)convert_to_sensor_event_offset);
    } else {
        KLOGE(kHookTag, "install_convert_to_sensor_event_hook: DobbyHook failed rc=%d at %p", ret, addr);
    }
}

extern "C" {

// ============================================================
// Inject-package JNI functions (com.kail.location.inject.utils.NativeStepHook)
//
// These are driven from INSIDE system_server by the FakeLocation inject
// (InjectDex), which loads this .so by absolute path. system_server maps
// libsensorservice.so, so hooked_convert_to_sensor_event installs there and
// synthesises step-detector/counter events into the global sensor stream.
// The class com.kail.location.inject.utils.NativeStepHook lives in the slim
// inject dex, so its JNI names must be bound here.
// ============================================================

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeSetWriteOffset(
    JNIEnv* env, jclass clazz, jlong offset) {
    send_objects_offset = (uint64_t)offset;
}

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeSetConvertOffset(
    JNIEnv* env, jclass clazz, jlong offset) {
    convert_to_sensor_event_offset = (uint64_t)offset;
}

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeSetRouteSimulation(
    JNIEnv* env, jclass clazz, jboolean active, jfloat spm, jint mode) {
    bool isActive = (active != JNI_FALSE);
    if (isActive) {
        current_spm = spm;
        setRouteSimulationActive(true);
        gait::SensorSimulator::Get().UpdateParams(spm, mode, 0, true);
        isMocking = 1;
        step_event_counter = 0;
        resetStepDebtClock(false);
    } else {
        setRouteSimulationActive(false);
        isMocking = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeSetGaitParams(
    JNIEnv* env, jclass clazz, jfloat spm, jint mode, jint scheme, jboolean enable) {
    if (spm > 0.0f) current_spm = spm;   // keep the time-based synthesizer in sync
    gait::SensorSimulator::Get().UpdateParams(spm, mode, scheme, enable);
}

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeSetMocking(
    JNIEnv* env, jclass clazz, jint mocking) {
    isMocking = (int)mocking;
}

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeSetStepSimEnabled(
    JNIEnv* env, jclass clazz, jboolean enabled) {
    step_sim_enabled = (enabled != JNI_FALSE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeReset(
    JNIEnv* env, jclass clazz) {
    step_sim_enabled = 0;
    route_simulation_active = false;
    isMocking = 0;
    step_event_counter = 0;
    resetStepDebtClock(true);
    stepdetectorTrigger = 0;
    stepcounterTrigger = 0;
    mSensorHandleStepDetector = -1;
    mSensorHandleStepCounter = -1;
    current_spm = 120.0f;
}

JNIEXPORT jboolean JNICALL
Java_com_kail_location_inject_utils_NativeStepHook_nativeInitHook(
    JNIEnv* env, jclass clazz) {
    gait::SensorSimulator::Get().Init();
    if (send_objects_offset != 0) {
        install_send_objects_hook();
    }
    if (convert_to_sensor_event_offset != 0) {
        install_convert_to_sensor_event_hook();
    }
    gait::SensorSimulator::Get().ReloadConfig();
    // Report whether at least one hook is installed so Java can log status.
    bool ok = send_objects_hook_installed || convert_to_sensor_event_hook_installed;
    KLOGI(kHookTag, "NativeStepHook.nativeInitHook: sendObjects=%d convert=%d -> ok=%d",
          send_objects_hook_installed, convert_to_sensor_event_hook_installed, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// Root module JNI functions (com.kail.location.root.NativeSensorHook)
// ============================================================

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetWriteOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    send_objects_offset = (uint64_t)offset;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetConvertOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    convert_to_sensor_event_offset = (uint64_t)offset;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetRouteSimulation(
    JNIEnv* env,
    jclass clazz,
    jboolean active,
    jfloat spm,
    jint mode
) {
    bool isActive = (active != JNI_FALSE);
    
    if (isActive) {
        current_spm = spm;
        setRouteSimulationActive(true);
        gait::SensorSimulator::Get().UpdateParams(spm, mode, 0, true);
        isMocking = 1;
        step_event_counter = 0;
        resetStepDebtClock(false);
    } else {
        setRouteSimulationActive(false);
        isMocking = 0;
    }
    KLOGI(kHookTag, "nativeSetRouteSimulation: active=%d spm=%.2f mode=%d", isActive, spm, mode);
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    if (spm > 0.0f) current_spm = spm;   // keep the time-based synthesizer in sync
    gait::SensorSimulator::Get().UpdateParams(spm, mode, scheme, enable);
}

JNIEXPORT jboolean JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeReloadConfig(
    JNIEnv* env,
    jclass clazz
) {
    return gait::SensorSimulator::Get().ReloadConfig() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetMocking(
    JNIEnv* env,
    jclass clazz,
    jint mocking
) {
    isMocking = (int)mocking;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetAuthorized(
    JNIEnv* env,
    jclass clazz,
    jint authorized
) {
    isAuthorized = (int)authorized;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeSetStepSimEnabled(
    JNIEnv* env,
    jclass clazz,
    jboolean enabled
) {
    step_sim_enabled = (enabled != JNI_FALSE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeReset(
    JNIEnv* env,
    jclass clazz
) {
    step_sim_enabled = 0;
    route_simulation_active = false;
    isMocking = 0;
    step_event_counter = 0;
    resetStepDebtClock(true);
    stepdetectorTrigger = 0;
    stepcounterTrigger = 0;
    mSensorHandleStepDetector = -1;
    mSensorHandleStepCounter = -1;
    current_spm = 120.0f;
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeInitHook(
    JNIEnv* env,
    jclass clazz
) {
    gait::SensorSimulator::Get().Init();
    
    if (send_objects_offset != 0) {
        install_send_objects_hook();
    }
    
    if (convert_to_sensor_event_offset != 0) {
        install_convert_to_sensor_event_hook();
    }
    
    gait::SensorSimulator::Get().ReloadConfig();
    KLOGI(kHookTag, "root.NativeSensorHook.nativeInitHook: sendObjects=%d convert=%d",
          send_objects_hook_installed, convert_to_sensor_event_hook_installed);
}

// ============================================================
// Xposed module JNI functions (com.kail.locationxposed.xposed.sensor.NativeSensorHook)
// ============================================================

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetWriteOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetWriteOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetConvertOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetConvertOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetRouteSimulation(
    JNIEnv* env,
    jclass clazz,
    jboolean active,
    jfloat spm,
    jint mode
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetRouteSimulation(env, clazz, active, spm, mode);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetGaitParams(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
}

JNIEXPORT jboolean JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeReloadConfig(
    JNIEnv* env,
    jclass clazz
) {
    return Java_com_kail_location_root_NativeSensorHook_nativeReloadConfig(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetMocking(
    JNIEnv* env,
    jclass clazz,
    jint mocking
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetMocking(env, clazz, mocking);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetAuthorized(
    JNIEnv* env,
    jclass clazz,
    jint authorized
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetAuthorized(env, clazz, authorized);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetStepSimEnabled(
    JNIEnv* env,
    jclass clazz,
    jboolean enabled
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetStepSimEnabled(env, clazz, enabled);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeReset(
    JNIEnv* env,
    jclass clazz
) {
    Java_com_kail_location_root_NativeSensorHook_nativeReset(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeInit(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz);
}

// ============================================================
// Main app Xposed package JNI functions (com.kail.location.xposed.sensor.NativeSensorHook)
// ============================================================

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetWriteOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetWriteOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetConvertOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetConvertOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetRouteSimulation(
    JNIEnv* env,
    jclass clazz,
    jboolean active,
    jfloat spm,
    jint mode
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetRouteSimulation(env, clazz, active, spm, mode);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetGaitParams(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
}

JNIEXPORT jboolean JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeReloadConfig(
    JNIEnv* env,
    jclass clazz
) {
    return Java_com_kail_location_root_NativeSensorHook_nativeReloadConfig(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetMocking(
    JNIEnv* env,
    jclass clazz,
    jint mocking
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetMocking(env, clazz, mocking);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetAuthorized(
    JNIEnv* env,
    jclass clazz,
    jint authorized
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetAuthorized(env, clazz, authorized);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeSetStepSimEnabled(
    JNIEnv* env,
    jclass clazz,
    jboolean enabled
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetStepSimEnabled(env, clazz, enabled);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeReset(
    JNIEnv* env,
    jclass clazz
) {
    Java_com_kail_location_root_NativeSensorHook_nativeReset(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_sensor_NativeSensorHook_nativeInit(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz);
}

// ============================================================
// Main app package JNI binding (com.kail.location.xposed.core.FakeLocState)
// ============================================================

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetWriteOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetWriteOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetConvertOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetConvertOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetRouteSimulation(
    JNIEnv* env,
    jclass clazz,
    jboolean active,
    jfloat spm,
    jint mode
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetRouteSimulation(env, clazz, active, spm, mode);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetGaitParams(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
}

JNIEXPORT jboolean JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeReloadConfig(
    JNIEnv* env,
    jclass clazz
) {
    return Java_com_kail_location_root_NativeSensorHook_nativeReloadConfig(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetMocking(
    JNIEnv* env,
    jclass clazz,
    jint mocking
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetMocking(env, clazz, mocking);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetAuthorized(
    JNIEnv* env,
    jclass clazz,
    jint authorized
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetAuthorized(env, clazz, authorized);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeSetStepSimEnabled(
    JNIEnv* env,
    jclass clazz,
    jboolean enabled
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetStepSimEnabled(env, clazz, enabled);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeInitHook(
    JNIEnv* env,
    jclass clazz
) {
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_location_xposed_core_FakeLocState_nativeInit(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz);
}

// ============================================================
// Xposed module JNI binding (com.kail.locationxposed.xposed.core.FakeLocState)
// ============================================================

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetWriteOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetWriteOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetConvertOffset(
    JNIEnv* env,
    jclass clazz,
    jlong offset
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetConvertOffset(env, clazz, offset);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetRouteSimulation(
    JNIEnv* env,
    jclass clazz,
    jboolean active,
    jfloat spm,
    jint mode
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetRouteSimulation(env, clazz, active, spm, mode);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetGaitParams(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
}

JNIEXPORT jboolean JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeReloadConfig(
    JNIEnv* env,
    jclass clazz
) {
    return Java_com_kail_location_root_NativeSensorHook_nativeReloadConfig(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetMocking(
    JNIEnv* env,
    jclass clazz,
    jint mocking
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetMocking(env, clazz, mocking);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetAuthorized(
    JNIEnv* env,
    jclass clazz,
    jint authorized
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetAuthorized(env, clazz, authorized);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeSetStepSimEnabled(
    JNIEnv* env,
    jclass clazz,
    jboolean enabled
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetStepSimEnabled(env, clazz, enabled);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeInitHook(
    JNIEnv* env,
    jclass clazz
) {
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_core_FakeLocState_nativeInit(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeSetGaitParams(env, clazz, spm, mode, scheme, enable);
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz);
}

}
