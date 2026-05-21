package com.kail.location.root

import android.util.Log
import com.kail.location.utils.ShellUtils

/**
 * Native sensor hook for step frequency simulation.
 * Loads libkail_native_hook.so and manages step counter / step detector mocking.
 */
object NativeSensorHook {
    private const val TAG = "KailNativeSensor"

    private var soLoaded = false
    private var initCalled = false

    // Native methods (matching hook.cpp JNI signatures)
    @JvmStatic
    external fun nativeSetWriteOffset(offset: Long)

    @JvmStatic
    external fun nativeSetConvertOffset(offset: Long)

    @JvmStatic
    external fun nativeSetRouteSimulation(active: Boolean, spm: Float, mode: Int)

    @JvmStatic
    external fun nativeSetGaitParams(spm: Float, mode: Int, scheme: Int, enable: Boolean)

    @JvmStatic
    external fun nativeReloadConfig(): Boolean

    @JvmStatic
    external fun nativeSetMocking(mocking: Int)

    @JvmStatic
    external fun nativeSetAuthorized(authorized: Int)

    @JvmStatic
    external fun nativeSetStepSimEnabled(enabled: Boolean)

    @JvmStatic
    external fun nativeInitHook(spm: Float, mode: Int, scheme: Int, enable: Boolean)

    @JvmStatic
    external fun nativeReset()

    /**
     * Initialize the native sensor hook.
     * Should be called once per APP process.
     *
     * @param spm    Steps per minute (default 120)
     * @param mode   Gait mode: 0=walk, 1=run, 2=fast_run
     * @param scheme Simulation scheme: 0=fourier, 1=sine_noise
     * @param enabled Whether step simulation is enabled
     */
    @JvmStatic
    @JvmOverloads
    fun init(spm: Float = 120f, mode: Int = 0, scheme: Int = 0, enabled: Boolean = true) {
        if (initCalled) return
        initCalled = true
        try {
            loadSo()
            if (!soLoaded) {
                Log.w(TAG, "libkail_native_hook.so not loaded, skipping init")
                return
            }

            val (sendOffsetStr, convertOffsetStr) = getOffsetsFromSystem()

            val sendOffset = try {
                sendOffsetStr.removePrefix("0x").toLong(16)
            } catch (_: Throwable) { 0L }
            val convertOffset = try {
                convertOffsetStr.removePrefix("0x").toLong(16)
            } catch (_: Throwable) { 0L }

            if (sendOffset > 0) {
                nativeSetWriteOffset(sendOffset)
                Log.i(TAG, "send_objects offset=0x${sendOffset.toString(16)}")
            } else {
                Log.w(TAG, "send_objects offset not found, step frequency simulation may not work")
            }

            if (convertOffset > 0) {
                nativeSetConvertOffset(convertOffset)
                Log.i(TAG, "convertToSensorEvent offset=0x${convertOffset.toString(16)}")
            } else {
                Log.w(TAG, "convertToSensorEvent offset not found, using default 0x5b420")
                nativeSetConvertOffset(0x5b420)
            }

            nativeInitHook(spm, mode, scheme, enabled)
            Log.i(TAG, "Native sensor hook initialized (spm=$spm, mode=$mode, scheme=$scheme, enabled=$enabled)")
        } catch (e: Throwable) {
            Log.e(TAG, "init failed: ${e.message}")
        }
    }

    /**
     * Enable/disable step simulation for route simulation.
     */
    @JvmStatic
    fun setRouteSimulation(active: Boolean, spm: Float = 120f, mode: Int = 0) {
        try {
            if (!soLoaded) return
            nativeSetRouteSimulation(active, spm, mode)
            Log.i(TAG, "setRouteSimulation active=$active spm=$spm mode=$mode")
        } catch (e: Throwable) {
            Log.e(TAG, "setRouteSimulation failed: ${e.message}")
        }
    }

    /**
     * Enable/disable step simulation (standalone).
     */
    @JvmStatic
    fun setStepSimEnabled(enabled: Boolean) {
        try {
            if (!soLoaded) return
            nativeSetStepSimEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "setStepSimEnabled failed: ${e.message}")
        }
    }

    /**
     * Reset simulation state. Hooks remain installed (resident), only flags are cleared.
     */
    @JvmStatic
    fun reset() {
        try {
            if (!soLoaded) {
                Log.w(TAG, "SO not loaded, skipping reset")
                return
            }
            // DO NOT reset initCalled - hooks remain installed, only simulation state is cleared
            nativeReset()
            Log.i(TAG, "nativeReset succeeded")
        } catch (e: Throwable) {
            Log.e(TAG, "nativeReset failed: ${e.message}")
        }
    }

    /**
     * Mark SO as loaded externally (e.g., by Xposed module).
     */
    @JvmStatic
    fun markSoLoaded() {
        soLoaded = true
    }

    /**
     * Check if SO is loaded.
     */
    @JvmStatic
    fun isSoLoaded(): Boolean = soLoaded

    private fun loadSo() {
        try {
            // 1. Try versioned path written by ServiceGoRoot (bypasses dlopen cache)
            var soPath = ""
            try {
                val pathFile = java.io.File("/data/local/tmp/kail_native_hook_path.txt")
                if (pathFile.exists()) {
                    soPath = pathFile.readText().trim()
                }
            } catch (_: Throwable) {}

            // 2. Fallback to default path
            if (soPath.isEmpty() || !java.io.File(soPath).exists()) {
                soPath = "/data/local/kail-lib/libkail_native_hook.so"
            }

            val file = java.io.File(soPath)
            if (!file.exists()) {
                Log.w(TAG, "libkail_native_hook.so not found at $soPath")
                return
            }
            System.load(soPath)
            soLoaded = true
            Log.i(TAG, "Loaded $soPath")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load so: ${e.message}")
        }
    }

    private val offsetCacheFile = "/data/local/tmp/kail_sensor_offsets.txt"

    /**
     * Get function offsets from precomputed cache file (preferred) or fallback to readelf.
     * Cache file is written by ServiceGoRoot with root permissions before injection.
     */
    private fun getOffsetsFromSystem(): Pair<String, String> {
        // 1. Try cache file first (works in system_server without root)
        try {
            val file = java.io.File(offsetCacheFile)
            if (file.exists()) {
                val lines = file.readLines()
                var sendOffset = ""
                var convertOffset = ""
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("send_objects=")) {
                        sendOffset = trimmed.substringAfter("=")
                    } else if (trimmed.startsWith("convert_to_sensor_event=")) {
                        convertOffset = trimmed.substringAfter("=")
                    }
                }
                if (sendOffset.isNotEmpty() && convertOffset.isNotEmpty()) {
                    Log.i(TAG, ">>> Loaded offsets from cache: send=$sendOffset, convert=$convertOffset")
                    return Pair(sendOffset, convertOffset)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, ">>> Failed to read offset cache: ${e.message}")
        }

        // 2. Fallback: try readelf directly (only works if we have root, e.g. app process)
        val commands = listOf("toybox readelf", "readelf", "/system/bin/toybox readelf")

        for (cmd in commands) {
            try {
                Log.i(TAG, ">>> Trying command: $cmd")
                val testCmd = ShellUtils.executeCommand("$cmd 2>&1")
                if (testCmd.contains("not found") || (testCmd.isEmpty() && !cmd.startsWith("/"))) {
                    continue
                }

                val sensorOut = ShellUtils.executeCommand(
                    "$cmd -Ws /system/lib64/libsensor.so 2>/dev/null | grep _ZN7android7BitTube11sendObjects"
                )

                val sensorServiceOut = ShellUtils.executeCommand(
                    "$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors14implementation20convertToSensorEvent[^4V1]'"
                )
                val sensorServiceV1Out = ShellUtils.executeCommand(
                    "$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEvent'"
                )

                Log.i(TAG, ">>> sensorOut: $sensorOut")
                Log.i(TAG, ">>> sensorServiceOut: $sensorServiceOut")
                Log.i(TAG, ">>> sensorServiceV1Out: $sensorServiceV1Out")

                if (sensorOut.isNotEmpty()) {
                    val sensorOffset = parseReadelfOffset(sensorOut)
                    val sensorServiceOffset = when {
                        sensorServiceOut.isNotEmpty() -> parseReadelfOffset(sensorServiceOut)
                        sensorServiceV1Out.isNotEmpty() -> parseReadelfOffset(sensorServiceV1Out)
                        else -> ""
                    }

                    if (sensorOffset.isNotEmpty() && sensorServiceOffset.isNotEmpty()) {
                        val finalSensorOffset = if (sensorOffset.startsWith("0x")) sensorOffset else "0x$sensorOffset"
                        val finalSensorServiceOffset = if (sensorServiceOffset.startsWith("0x")) sensorServiceOffset else "0x$sensorServiceOffset"
                        Log.i(TAG, ">>> Got offsets: sensor=$finalSensorOffset, sensorService=$finalSensorServiceOffset")
                        return Pair(finalSensorOffset, finalSensorServiceOffset)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, ">>> getOffsets exception: ${e.message}")
                continue
            }
        }

        Log.w(TAG, ">>> readelf not available and no cache, skipping sensor offset detection")
        return Pair("0x0", "0x0")
    }

    private fun parseReadelfOffset(output: String): String {
        // readelf -Ws output format (one line per symbol):
        // "Num: Value Size Type Bind Vis Ndx Name"
        // e.g. "    42: 0000000000005b420    40 FUNC    GLOBAL DEFAULT   12 _ZN7android8hardware7sensors..."
        val trimmed = output.trim().lines().firstOrNull()?.trim() ?: return ""
        val parts = trimmed.split(Regex("\\s+"))
        // Find first 8-16 digit hex string (the actual address), skip "Num:" indices
        return parts.firstOrNull { it.matches(Regex("^[0-9a-fA-F]{8,16}$")) } ?: ""
    }
}
