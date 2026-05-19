package com.kail.location.service.Root

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.kail.location.utils.KailLog
import com.kail.location.utils.ShellUtils
import com.kail.location.utils.service.ServiceConstants

/**
 * 负责 root 模式下的 hook 注入、配置文件写入与生命周期管理。
 */
class RootHookManager(private val context: Context) {

    private var kailRandomKey: String? = null
    private var kailStarted: Boolean = false

    var stepEnabled: Boolean = false
    var stepFreq: Double = 0.0
    var simScheme: Int = 0
    var stepSimEnabled: Boolean = true

    var currentLat: Double = ServiceConstants.DEFAULT_LAT
    var currentLng: Double = ServiceConstants.DEFAULT_LNG
    var currentAlt: Double = ServiceConstants.DEFAULT_ALT
    var currentBea: Float = ServiceConstants.DEFAULT_BEA
    var currentSpeed: Double = 1.2

    fun initIfNeeded(): Boolean {
        if (kailRandomKey != null) return true
        return injectRootHookIfNeeded()
    }

    fun startIfNeeded(): Boolean {
        if (kailStarted) return true
        if (!initIfNeeded()) {
            KailLog.e(context, "RootHookManager", "startIfNeeded failed because init failed")
            return false
        }
        kailStarted = true
        writeConfig()
        KailLog.i(context, "RootHookManager", "kail root mode started")
        return true
    }

    fun updateOnce() {
        if (!startIfNeeded()) {
            KailLog.e(context, "RootHookManager", "updateOnce failed because start failed")
            return
        }
        writeConfig()
    }

    fun tick() {
        if (!startIfNeeded()) return
        writeConfig()
    }

    fun stopSafe() {
        try {
            val sharedFile = "/data/local/kail-lib/kail_location.conf"
            ShellUtils.executeCommand("echo 'enabled=false' > $sharedFile")
            ShellUtils.executeCommand("chmod 777 $sharedFile")
        } catch (e: Exception) {
            KailLog.e(context, "RootHookManager", "stopSafe failed: ${e.message}")
        }
        kailStarted = false
        kailRandomKey = null
        KailLog.i(context, "RootHookManager", ">>> Keeping SELinux permissive (injected code still active)")
    }

    fun writeConfig() {
        try {
            val sharedFile = "/data/local/kail-lib/kail_location.conf"
            val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val independentEnabled = prefs.getBoolean("independent_enabled", false)

            val mode: String
            val simType: String
            val wifiMock: Boolean
            val cellMock: Boolean
            val gnssMock: Boolean
            val targetPkgs: String

            if (independentEnabled) {
                mode = "independent"
                val indLocation = prefs.getBoolean("independent_mode_location", true)
                val indRoute = prefs.getBoolean("independent_mode_route", false)
                val indWifi = prefs.getBoolean("independent_mode_wifi", false)
                val indCell = prefs.getBoolean("independent_mode_cell", false)
                val indGnss = prefs.getBoolean("independent_mode_gnss", true)

                simType = if (indRoute) "route" else "location"
                val wifiFeatureEnabled = prefs.getBoolean("wifi_sim_enabled", false)
                val cellFeatureEnabled = prefs.getBoolean("cell_sim_enabled", false)
                wifiMock = indWifi && wifiFeatureEnabled
                cellMock = indCell && cellFeatureEnabled
                gnssMock = indGnss
                targetPkgs = prefs.getString("independent_target_packages", "") ?: ""
            } else {
                mode = prefs.getString("root_mode", "global") ?: "global"
                simType = prefs.getString("sim_type", "location") ?: "location"
                wifiMock = prefs.getBoolean("wifi_mock", false)
                cellMock = prefs.getBoolean("cell_mock", false)
                gnssMock = prefs.getBoolean("setting_gps_satellite_sim", true)
                targetPkgs = prefs.getString("target_packages", "") ?: ""
            }

            val stepMock = stepEnabled
            val stepFreqFloat = stepFreq.toFloat()
            val stepSchemeStr = prefs.getString("setting_sim_scheme", "0") ?: "0"

            val content = buildString {
                appendLine("enabled=$kailStarted")
                appendLine("mode=$mode")
                appendLine("sim_type=$simType")
                appendLine("wifi_mock=$wifiMock")
                appendLine("cell_mock=$cellMock")
                appendLine("gnss_mock=$gnssMock")
                appendLine("step_mock=$stepMock")
                appendLine("step_freq=$stepFreqFloat")
                appendLine("step_scheme=$stepSchemeStr")
                appendLine("lat=$currentLat")
                appendLine("lon=$currentLng")
                appendLine("alt=$currentAlt")
                appendLine("speed=${currentSpeed.toFloat()}")
                appendLine("bearing=$currentBea")
                appendLine("accuracy=25.0")
                appendLine("target_packages=$targetPkgs")
            }

            ShellUtils.executeCommand("echo '$content' > $sharedFile")
            ShellUtils.executeCommand("chmod 777 $sharedFile")

            try {
                val nativeStepConfig = buildString {
                    appendLine("steps_per_minute=$stepFreqFloat")
                    appendLine("enable=${if (stepMock) 1 else 0}")
                    val schemeStr = when (stepSchemeStr) {
                        "1" -> "sine_noise"
                        else -> "fourier"
                    }
                    appendLine("scheme=$schemeStr")
                    val modeStr = when {
                        stepFreqFloat >= 160 -> "fast_run"
                        stepFreqFloat >= 130 -> "run"
                        else -> "walk"
                    }
                    appendLine("mode=$modeStr")
                }
                val stepConfigFile = "/data/local/tmp/step_config"
                ShellUtils.executeCommand("echo '$nativeStepConfig' > $stepConfigFile")
                ShellUtils.executeCommand("chmod 777 $stepConfigFile")
                KailLog.i(context, "RootHookManager", ">>> Step config written to $stepConfigFile")
            } catch (e: Exception) {
                KailLog.e(context, "RootHookManager", ">>> Failed to write step config: ${e.message}")
            }

            KailLog.i(context, "RootHookManager", ">>> Config written: mode=$mode sim=$simType wifi=$wifiMock cell=$cellMock gnss=$gnssMock targets=$targetPkgs independent=$independentEnabled")
        } catch (e: Exception) {
            KailLog.e(context, "RootHookManager", "writeConfig failed: ${e.message}")
        }
    }

    private fun injectRootHookIfNeeded(): Boolean {
        KailLog.i(context, "RootHookManager", ">>> injectRootHookIfNeeded called")

        if (!ShellUtils.hasRoot()) {
            KailLog.e(context, "RootHookManager", ">>> No root access for root-only mode!")
            return false
        }

        ShellUtils.executeCommand("setenforce 0")

        val soDir = java.io.File("/data/local/kail-lib")
        ShellUtils.executeCommand("mkdir -p ${soDir.absolutePath}")
        ShellUtils.executeCommand("chmod 777 ${soDir.absolutePath}")

        val appInfo = context.applicationInfo
        val nativeDir = appInfo.nativeLibraryDir

        // Copy root hook library
        val rootHookSo = java.io.File(soDir, "libkail_root_hook.so")
        runCatching {
            val apkRootHook = java.io.File(nativeDir, "libkail_root_hook.so")
            if (apkRootHook.exists()) {
                ShellUtils.executeCommand("cp ${apkRootHook.absolutePath} ${rootHookSo.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${rootHookSo.absolutePath}")
            }
        }.onFailure {
            KailLog.e(context, "RootHookManager", ">>> Failed to copy root hook so: ${it.message}")
        }

        // Copy LHooker library
        val lhookerSo = java.io.File(soDir, "libkail_lhooker.so")
        runCatching {
            val apkLHooker = java.io.File(nativeDir, "libkail_lhooker.so")
            if (apkLHooker.exists()) {
                ShellUtils.executeCommand("rm -f ${lhookerSo.absolutePath}")
                ShellUtils.executeCommand("cp ${apkLHooker.absolutePath} ${lhookerSo.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${lhookerSo.absolutePath}")
                KailLog.i(context, "RootHookManager", ">>> Copied libkail_lhooker.so")
            } else {
                KailLog.w(context, "RootHookManager", ">>> libkail_lhooker.so not found in APK")
            }
        }.onFailure {
            KailLog.e(context, "RootHookManager", ">>> Failed to copy lhooker so: ${it.message}")
        }

        // Copy native sensor hook library
        val nativeHookSo = java.io.File(soDir, "libkail_native_hook.so")
        val versionedNativeHookSo = java.io.File(soDir, "libkail_native_hook_${System.currentTimeMillis()}.so")
        runCatching {
            val apkNativeHook = java.io.File(nativeDir, "libkail_native_hook.so")
            if (apkNativeHook.exists()) {
                ShellUtils.executeCommand("cp ${apkNativeHook.absolutePath} ${nativeHookSo.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${nativeHookSo.absolutePath}")
                ShellUtils.executeCommand("cp ${apkNativeHook.absolutePath} ${versionedNativeHookSo.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${versionedNativeHookSo.absolutePath}")
                val pathFile = java.io.File("/data/local/tmp/kail_native_hook_path.txt")
                ShellUtils.executeCommand("echo '${versionedNativeHookSo.absolutePath}' > ${pathFile.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${pathFile.absolutePath}")
                KailLog.i(context, "RootHookManager", ">>> Copied libkail_native_hook.so -> ${versionedNativeHookSo.name}")
            } else {
                KailLog.w(context, "RootHookManager", ">>> libkail_native_hook.so not found in APK")
            }
        }.onFailure {
            KailLog.e(context, "RootHookManager", ">>> Failed to copy native hook so: ${it.message}")
        }

        // Copy kail_inject executable
        val injectExe = java.io.File(soDir, "kail_inject")
        runCatching {
            val apkInject = java.io.File(nativeDir, "libkail_inject.so")
            if (apkInject.exists()) {
                ShellUtils.executeCommand("rm -f ${injectExe.absolutePath}")
                ShellUtils.executeCommand("cp ${apkInject.absolutePath} ${injectExe.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${injectExe.absolutePath}")
                KailLog.i(context, "RootHookManager", ">>> Copied kail_inject to ${injectExe.absolutePath}")
            } else {
                KailLog.e(context, "RootHookManager", ">>> kail_inject not found in APK native dir: ${apkInject.absolutePath}")
            }
        }.onFailure {
            KailLog.e(context, "RootHookManager", ">>> Failed to copy kail_inject: ${it.message}")
        }

        // Copy APK for DexClassLoader
        val apkDest = java.io.File(soDir, "kail_location.apk")
        runCatching {
            val apkSource = java.io.File(appInfo.sourceDir)
            if (apkSource.exists()) {
                ShellUtils.executeCommand("cp ${apkSource.absolutePath} ${apkDest.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${apkDest.absolutePath}")
                KailLog.i(context, "RootHookManager", ">>> Copied APK to ${apkDest.absolutePath}")
            } else {
                KailLog.e(context, "RootHookManager", ">>> APK source not found: ${apkSource.absolutePath}")
            }
        }.onFailure {
            KailLog.e(context, "RootHookManager", ">>> Failed to copy APK: ${it.message}")
        }

        // Pre-resolve sensor hook offsets
        try {
            val offsetCacheFile = "/data/local/tmp/kail_sensor_offsets.txt"
            ShellUtils.executeCommand("rm -f $offsetCacheFile")
            val readelfCmds = listOf("toybox readelf", "readelf", "/system/bin/toybox readelf")
            var sendOffset = ""
            var convertOffset = ""
            for (cmd in readelfCmds) {
                val test = ShellUtils.executeCommand("$cmd 2>&1")
                if (test.contains("not found") || (test.isEmpty() && !cmd.startsWith("/"))) continue
                if (sendOffset.isEmpty()) {
                    val out = ShellUtils.executeCommand(
                        "$cmd -Ws /system/lib64/libsensor.so 2>/dev/null | grep _ZN7android7BitTube11sendObjects"
                    )
                    if (out.isNotEmpty()) {
                        sendOffset = out.trim().lines().firstOrNull()?.trim()
                            ?.split(Regex("\\s+"))
                            ?.firstOrNull { it.matches(Regex("^[0-9a-fA-F]{8,16}$")) }
                            ?: ""
                    }
                }
                if (convertOffset.isEmpty()) {
                    val out = ShellUtils.executeCommand(
                        "$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors14implementation20convertToSensorEvent[^4V1]'"
                    )
                    val outV1 = ShellUtils.executeCommand(
                        "$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEvent'"
                    )
                    val raw = if (out.isNotEmpty()) out else outV1
                    if (raw.isNotEmpty()) {
                        convertOffset = raw.trim().lines().firstOrNull()?.trim()
                            ?.split(Regex("\\s+"))
                            ?.firstOrNull { it.matches(Regex("^[0-9a-fA-F]{8,16}$")) }
                            ?: ""
                    }
                }
                if (sendOffset.isNotEmpty() && convertOffset.isNotEmpty()) break
            }
            if (sendOffset.isNotEmpty() && convertOffset.isNotEmpty()) {
                val sendHex = if (sendOffset.startsWith("0x")) sendOffset else "0x$sendOffset"
                val convertHex = if (convertOffset.startsWith("0x")) convertOffset else "0x$convertOffset"
                val content = "send_objects=$sendHex\nconvert_to_sensor_event=$convertHex\n"
                ShellUtils.executeCommand("echo '$content' > $offsetCacheFile")
                ShellUtils.executeCommand("chmod 777 $offsetCacheFile")
                KailLog.i(context, "RootHookManager", ">>> Cached sensor offsets: send=$sendHex, convert=$convertHex")
            } else {
                KailLog.w(context, "RootHookManager", ">>> Could not resolve sensor offsets, step sim may not work")
            }
        } catch (e: Exception) {
            KailLog.e(context, "RootHookManager", ">>> Failed to cache sensor offsets: ${e.message}")
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val globalMode = prefs.getBoolean("kail_global_mode", true)
        val injectAvailable = ShellUtils.executeCommand("[ -f ${injectExe.absolutePath} ] && echo yes || echo no").trim() == "yes"

        if (!injectAvailable) {
            KailLog.e(context, "RootHookManager", ">>> kail_inject not available, cannot inject anything")
            return false
        }

        fun injectTarget(name: String, pid: Int, isSystemServer: Boolean = false) {
            val checkResult = ShellUtils.executeCommand("cat /proc/$pid/maps | grep libkail_root_hook")
            if (checkResult.isNotEmpty()) {
                KailLog.i(context, "RootHookManager", ">>> $name already has libkail_root_hook mapped, skipping injection")
                return
            }
            KailLog.i(context, "RootHookManager", ">>> Injecting into $name pid=$pid")
            val injectCmd = "${injectExe.absolutePath} -p $pid -l ${rootHookSo.absolutePath}"
            val injectOutput = ShellUtils.executeCommand(injectCmd)
            KailLog.i(context, "RootHookManager", ">>> Inject output for $name: $injectOutput")
            val injectResult = injectOutput.contains("Injection successful", ignoreCase = true)
            KailLog.i(context, "RootHookManager", ">>> Inject result for $name: $injectResult")
        }

        if (globalMode) {
            var systemPid: Int? = null
            val pidofSystem = ShellUtils.executeCommand("pidof system_server").trim().toIntOrNull()
            if (pidofSystem != null && pidofSystem > 0) systemPid = pidofSystem
            if (systemPid == null) {
                val pidofAndroid = ShellUtils.executeCommand("pidof android").trim().toIntOrNull()
                if (pidofAndroid != null && pidofAndroid > 0) systemPid = pidofAndroid
            }
            if (systemPid == null) {
                val psResult = ShellUtils.executeCommand("ps -A -o PID,NAME | grep system_server")
                systemPid = psResult.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            }
            if (systemPid != null && systemPid > 0) {
                injectTarget("system_server", systemPid, isSystemServer = true)
            } else {
                KailLog.w(context, "RootHookManager", ">>> system_server not found, skipping global injection")
            }
        }

        val targetPackagesRaw = prefs.getString("kail_target_packages", "com.autonavi.minimap")
        val targetPackages = targetPackagesRaw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("com.autonavi.minimap")

        for (pkg in targetPackages) {
            val pidResult = ShellUtils.executeCommand("pidof $pkg")
            val targetPid = pidResult.trim().toIntOrNull()
            if (targetPid != null && targetPid > 0) {
                injectTarget(pkg, targetPid)
            } else {
                KailLog.w(context, "RootHookManager", ">>> $pkg is not running, skipping injection")
            }
        }

        kailRandomKey = "root_mode"
        return true
    }
}
