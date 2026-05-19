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

#define LOG_TAG "KailNativeSensor"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
static uint64_t convert_to_sensor_event_offset = 0;

static int stepdetectorTrigger = 0;
static int stepcounterTrigger = 0;
static int mSensorHandleStepDetector = -1;
static int mSensorHandleStepCounter = -1;
static int isMocking = 0;
static int isAuthorized = 0;
static int step_sim_enabled = 1;
static float current_spm = 120.0f;
static int step_event_counter = 0;
static int64_t last_step_inject_ns = 0;

#define ALOGI_TO_FILE(...) ALOGI(__VA_ARGS__)
#define ALOGE_TO_FILE(...) ALOGE(__VA_ARGS__)

void setRouteSimulationActive(bool active) {
    route_simulation_active = active;
    if (!active) {
        gait::SensorSimulator::Get().UpdateParams(120.0f, 0, 0, false);
    }
}

extern "C" void hooked_send_objects(long* param_1, void* param_2, long param_3, long param_4) {
    int count = (int)param_3;
    bool preview_has_step = false;
    if (param_2 && count > 0 && count <= 1000) {
        char* ptr = (char*)param_2;
        for (int i = 0; i < count; i++) {
            int type = *(int*)(ptr + i * EVENT_SIZE + 0x08);
            if (type == SENSOR_TYPE_STEP_COUNTER || type == SENSOR_TYPE_STEP_DETECTOR) {
                preview_has_step = true;
                break;
            }
        }
    }
    ALOGI("[HOOK] hooked_send_objects: count=%d, has_step=%d, route_active=%d, step_sim_enabled=%d, isMocking=%d, current_spm=%.1f",
          count, preview_has_step ? 1 : 0, route_simulation_active ? 1 : 0, step_sim_enabled, isMocking, current_spm);

    if (!param_2) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }
    
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

    bool has_step = false;
    char* ptr = heap_buffer;
    for (int i = 0; i < count; i++) {
        void* event = ptr + i * EVENT_SIZE;
        uintptr_t addr = (uintptr_t)event;
        if (addr < 0x10000) {
            continue;
        }

        if (route_simulation_active && step_sim_enabled) {
            int type = *(int*)((char*)event + 0x08);
            
            int64_t timestamp = *(int64_t*)((char*)event + 0x10);
            
            if (type == SENSOR_TYPE_STEP_COUNTER || type == SENSOR_TYPE_STEP_DETECTOR) {
                has_step = true;
            }
            
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

    if (has_step) {
        ALOGI("hooked_send_objects: processed %d events (route_active=%d)", count, route_simulation_active);
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
    int sensor_handle = *(int*)((char*)param_2 + 0x04);

    if (sensor_type == SENSOR_TYPE_STEP_DETECTOR) {
        stepdetectorTrigger = 1;
        mSensorHandleStepDetector = sensor_handle;
        ALOGI("convert_to_sensor_event: got STEP_DETECTOR handle=%d", sensor_handle);
    } else if (sensor_type == SENSOR_TYPE_STEP_COUNTER) {
        stepcounterTrigger = 1;
        mSensorHandleStepCounter = sensor_handle;
        ALOGI("convert_to_sensor_event: got STEP_COUNTER handle=%d", sensor_handle);
    }

    if (original_convert_to_sensor_event) {
        original_convert_to_sensor_event(param_1, param_2);
    }

    // Inject fake step events when mocking is enabled.
    // We inject on ACCELEROMETER (type 1) events because they always fire
    // frequently, guaranteeing step data delivery to registered listeners.
    // Throttle injection to step frequency so we don't fire at 60 Hz.
    if ((isMocking != 0) && step_sim_enabled && (sensor_type == SENSOR_TYPE_ACCELEROMETER)) {
        int64_t now = *(int64_t*)((char*)param_2 + 0x10); // event timestamp in ns
        double step_interval_ns = (60.0 / current_spm) * 1e9;
        int64_t min_interval_ns = static_cast<int64_t>(step_interval_ns * 0.8);
        if (last_step_inject_ns != 0 && (now - last_step_inject_ns) < min_interval_ns) {
            // Too soon since last injected step; skip to preserve accelerometer data
            return;
        }
        last_step_inject_ns = now;

        // 注入前记录原始值
        int orig_handle = *(int*)((char*)param_2 + 0x04);
        int orig_type = *(int*)((char*)param_2 + 0x08);
        float orig_data0 = *(float*)((char*)param_2 + 0x18);

        if ((stepdetectorTrigger == 1) && (mSensorHandleStepDetector != -1) &&
            (stepcounterTrigger == 1) && (mSensorHandleStepCounter != -1)) {
            if (step_event_counter < 4) {
                step_event_counter++;
                *(int*)((char*)param_2 + 0x04) = mSensorHandleStepDetector;
                *(int*)((char*)param_2 + 0x08) = SENSOR_TYPE_STEP_DETECTOR;
                *(float*)((char*)param_2 + 0x18) = 1.0f;
                int new_type = *(int*)((char*)param_2 + 0x08);
                ALOGI("convert_to_sensor_event: injected STEP_DETECTOR (throttled) orig_type=%d new_type=%d handle=%d->%d data=%f->1.0",
                      orig_type, new_type, orig_handle, mSensorHandleStepDetector, orig_data0);
            } else {
                step_event_counter = 0;
                *(int*)((char*)param_2 + 0x04) = mSensorHandleStepCounter;
                *(int*)((char*)param_2 + 0x08) = SENSOR_TYPE_STEP_COUNTER;
                *(uint64_t*)((char*)param_2 + 0x18) = 0;
                int new_type = *(int*)((char*)param_2 + 0x08);
                ALOGI("convert_to_sensor_event: injected STEP_COUNTER (throttled) orig_type=%d new_type=%d handle=%d->%d",
                      orig_type, new_type, orig_handle, mSensorHandleStepCounter);
            }
        } else if ((stepdetectorTrigger == 1) && (mSensorHandleStepDetector != -1)) {
            stepdetectorTrigger = 0;
            *(int*)((char*)param_2 + 0x04) = mSensorHandleStepDetector;
            *(int*)((char*)param_2 + 0x08) = SENSOR_TYPE_STEP_DETECTOR;
            *(float*)((char*)param_2 + 0x18) = 1.0f;
            int new_type = *(int*)((char*)param_2 + 0x08);
            ALOGI("convert_to_sensor_event: injected STEP_DETECTOR (fallback, throttled) orig_type=%d new_type=%d",
                  orig_type, new_type);
        } else if ((stepcounterTrigger == 1) && (mSensorHandleStepCounter != -1)) {
            stepcounterTrigger = 0;
            *(int*)((char*)param_2 + 0x04) = mSensorHandleStepCounter;
            *(int*)((char*)param_2 + 0x08) = SENSOR_TYPE_STEP_COUNTER;
            *(uint64_t*)((char*)param_2 + 0x18) = 0;
            int new_type = *(int*)((char*)param_2 + 0x08);
            ALOGI("convert_to_sensor_event: injected STEP_COUNTER (fallback, throttled) orig_type=%d new_type=%d",
                  orig_type, new_type);
        } else {
            stepdetectorTrigger = 1;
            mSensorHandleStepDetector = 0;
            *(int*)((char*)param_2 + 0x04) = 0;
            *(int*)((char*)param_2 + 0x08) = SENSOR_TYPE_STEP_DETECTOR;
            *(float*)((char*)param_2 + 0x18) = 1.0f;
            int new_type = *(int*)((char*)param_2 + 0x08);
            ALOGI("convert_to_sensor_event: injected STEP_DETECTOR (init, throttled) orig_type=%d new_type=%d",
                  orig_type, new_type);
        }
    }
}

static void install_send_objects_hook() {
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        ALOGE("Cannot open /proc/self/maps");
        return;
    }
    
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        // send_objects lives in libsensor.so (verified by readelf on this device)
        if (strstr(line, "libsensor.so")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = (void*)start;
            break;
        }
    }
    fclose(fp);
    
    if (!base) {
        ALOGE("libsensor.so not found in maps, cannot hook send_objects");
        return;
    }
    
    if (send_objects_offset == 0) {
        ALOGE("send_objects_offset is 0, cannot hook");
        return;
    }
    
    void* addr = (void*)((char*)base + send_objects_offset);
    
    int ret = DobbyHook(addr, (void*)hooked_send_objects, (void**)&original_send_objects);
    
    if (ret == 0) {
        send_objects_hook_installed = true;
        ALOGI("send_objects hook installed at %p (base=%p offset=0x%lx)", addr, base, (unsigned long)send_objects_offset);
    } else {
        ALOGE("send_objects DobbyHook failed: ret=%d addr=%p", ret, addr);
    }
}

static void install_convert_to_sensor_event_hook() {
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        ALOGE("Cannot open /proc/self/maps for convert hook");
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
        ALOGE("libsensorservice.so not found, cannot hook convert_to_sensor_event");
        return;
    }
    
    if (convert_to_sensor_event_offset == 0) {
        ALOGE("convert_to_sensor_event_offset is 0, cannot hook");
        return;
    }
    
    void* addr = (void*)((char*)base + convert_to_sensor_event_offset);
    
    int ret = DobbyHook(addr, (void*)hooked_convert_to_sensor_event, (void**)&original_convert_to_sensor_event);
    
    if (ret == 0) {
        convert_to_sensor_event_hook_installed = true;
        ALOGI("convert_to_sensor_event hook installed at %p (base=%p offset=0x%lx)", addr, base, (unsigned long)convert_to_sensor_event_offset);
    } else {
        ALOGE("convert_to_sensor_event DobbyHook failed: ret=%d addr=%p", ret, addr);
    }
}

extern "C" {

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
    } else {
        setRouteSimulationActive(false);
        isMocking = 0;
    }
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
    route_simulation_active = (enabled != JNI_FALSE);
    if (!enabled) {
        isMocking = 0;
    }
    ALOGI("[JNI] nativeSetStepSimEnabled: enabled=%d, step_sim_enabled=%d, route_active=%d, isMocking=%d",
          enabled ? 1 : 0, step_sim_enabled, route_simulation_active ? 1 : 0, isMocking);
}

JNIEXPORT void JNICALL
Java_com_kail_location_root_NativeSensorHook_nativeInitHook(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    ALOGI("[JNI] nativeInitHook ENTRY: spm=%.1f, mode=%d, scheme=%d, enable=%d", spm, mode, scheme, enable != JNI_FALSE);
    gait::SensorSimulator::Get().Init();

    current_spm = spm;
    ALOGI("[JNI] nativeInitHook: current_spm set to %.1f", current_spm);
    gait::SensorSimulator::Get().UpdateParams(spm, mode, scheme, enable != JNI_FALSE);
    ALOGI("[JNI] nativeInitHook: UpdateParams called");

    if (send_objects_offset != 0) {
        install_send_objects_hook();
    }

    if (convert_to_sensor_event_offset != 0) {
        install_convert_to_sensor_event_hook();
    }

    isMocking = 1;
    step_sim_enabled = 1;
    route_simulation_active = true;
    ALOGI("[JNI] nativeInitHook EXIT: isMocking=%d, step_sim_enabled=%d, route_active=%d",
          isMocking, step_sim_enabled, route_simulation_active ? 1 : 0);
}

// ============================================================
// Xposed 模块 JNI 函数 (com.kail.locationxposed.xposed.sensor.NativeSensorHook)
// 与 root 模块共享相同的 native 实现
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
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeInitHook(
    JNIEnv* env,
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz, spm, mode, scheme, enable);
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
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz, spm, mode, scheme, enable);
}

// ============================================================
// 主应用包名 JNI 绑定 (com.kail.location.xposed.core.FakeLocState)
// 与 root 模块共享相同的 native 实现
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
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz, spm, mode, scheme, enable);
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
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz, spm, mode, scheme, enable);
}

// ============================================================
// Xposed 模块 JNI 绑定 (com.kail.locationxposed.xposed.core.FakeLocState)
// 注意包名差异: locationxposed 而非 location.xposed
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
    jclass clazz,
    jfloat spm,
    jint mode,
    jint scheme,
    jboolean enable
) {
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz, spm, mode, scheme, enable);
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
    Java_com_kail_location_root_NativeSensorHook_nativeInitHook(env, clazz, spm, mode, scheme, enable);
}

}
