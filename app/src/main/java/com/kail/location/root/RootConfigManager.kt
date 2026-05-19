package com.kail.location.root

import android.location.Location
import android.location.LocationManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Configuration manager for root hook.
 * Supports global mode and per-app independent mode.
 * Reads config from /data/local/kail-lib/kail_location.conf
 */
object RootConfigManager {
    private const val TAG = "KailRootConfig"
    private const val CONFIG_PATH = "/data/local/kail-lib/kail_location.conf"
    private const val FALLBACK_CONFIG_PATH = "/data/local/tmp/kail_location.conf"
    private const val WIFI_CONFIG_PATH = "/data/local/kail-lib/kail_wifi.json"
    private const val CELL_CONFIG_PATH = "/data/local/kail-lib/kail_cell.json"

    // Mode: "global" or "independent"
    val modeRef = AtomicReference("global")
    val enabledRef = AtomicBoolean(false)
    val locationRef = AtomicReference<Location?>(null)
    val speedRef = AtomicReference(0f)
    val bearingRef = AtomicReference(0f)
    val altitudeRef = AtomicReference(0.0)
    val accuracyRef = AtomicReference(25.0f)
    // Simulation type: "location" or "route"
    val simTypeRef = AtomicReference("location")
    val targetPackagesRef = AtomicReference<List<String>>(emptyList())
    val wifiMockRef = AtomicBoolean(false)
    val cellMockRef = AtomicBoolean(false)
    val gnssMockRef = AtomicBoolean(false)

    // WiFi proxy data
    val wifiProxyEnabledRef = AtomicBoolean(false)
    val wifiListRef = AtomicReference<List<WifiEntry>>(emptyList())
    // Cell proxy data
    val cellProxyEnabledRef = AtomicBoolean(false)
    val cellListRef = AtomicReference<List<CellEntry>>(emptyList())

    // Step simulation data (written by ServiceGoRoot into kail_location.conf)
    val stepMockRef = AtomicBoolean(false)
    val stepFreqRef = AtomicReference(120f)
    val stepSchemeRef = AtomicReference("0")

    private var lastModified: Long = 0

    data class WifiEntry(
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val frequency: Int,
        val linkSpeed: Int,
        val capabilities: String
    )

    data class CellEntry(
        val networkType: String,
        val mcc: Int,
        val mnc: Int,
        val lac: Int,
        val cid: Long,
        val psc: Int,
        val latitude: Double,
        val longitude: Double,
        val radius: Float
    )

    @JvmStatic
    fun isEnabled(): Boolean = enabledRef.get()

    @JvmStatic
    fun getMode(): String = modeRef.get()

    @JvmStatic
    fun isGlobalMode(): Boolean = getMode() == "global"

    @JvmStatic
    fun isIndependentMode(): Boolean = getMode() == "independent"

    @JvmStatic
    fun getSimType(): String = simTypeRef.get()

    @JvmStatic
    fun isLocationSim(): Boolean = getSimType() == "location"

    @JvmStatic
    fun isRouteSim(): Boolean = getSimType() == "route"

    @JvmStatic
    fun isWifiMockEnabled(): Boolean {
        // WiFi mock is controlled entirely by kail_wifi.json (not by kail_location.conf wifi_mock)
        return wifiProxyEnabledRef.get() && wifiListRef.get().isNotEmpty()
    }

    @JvmStatic
    fun isCellMockEnabled(): Boolean {
        // Cell mock is controlled entirely by kail_cell.json
        return cellProxyEnabledRef.get() && cellListRef.get().isNotEmpty()
    }

    @JvmStatic
    fun isGnssMockEnabled(): Boolean = gnssMockRef.get()

    @JvmStatic
    fun getLatitude(): Double = locationRef.get()?.latitude ?: 0.0

    @JvmStatic
    fun getLongitude(): Double = locationRef.get()?.longitude ?: 0.0

    @JvmStatic
    fun getAltitude(): Double = altitudeRef.get()

    @JvmStatic
    fun getSpeed(): Float = speedRef.get()

    @JvmStatic
    fun getBearing(): Float = bearingRef.get()

    @JvmStatic
    fun getAccuracy(): Float = accuracyRef.get()

    @JvmStatic
    fun isStepMockEnabled(): Boolean = stepMockRef.get()

    @JvmStatic
    fun getStepFreq(): Float = stepFreqRef.get()

    @JvmStatic
    fun getStepScheme(): String = stepSchemeRef.get()

    @JvmStatic
    fun getTargetPackages(): List<String> = targetPackagesRef.get()

    /**
     * Check if a package should be mocked.
     * Global mode: all packages are mocked.
     * Independent mode: only target packages are mocked.
     */
    @JvmStatic
    fun shouldMockPackage(pkgName: String?): Boolean {
        if (!isEnabled()) return false
        if (isGlobalMode()) return true
        if (pkgName == null) return false
        val targets = getTargetPackages()
        return targets.isEmpty() || targets.any { pkgName.contains(it) || it.contains(pkgName) }
    }

    @JvmStatic
    fun reload() {
        val file = File(CONFIG_PATH)
        val actualFile = if (file.exists()) file else File(FALLBACK_CONFIG_PATH)
        var targets = emptyList<String>()

        // Always load WiFi and Cell configs (they can change independently of conf file)
        loadWifiConfig()
        loadCellConfig()

        // Only skip conf file parsing if it hasn't changed
        if (actualFile.exists()) {
            val modified = actualFile.lastModified()
            if (modified != lastModified) {
                lastModified = modified
                try {
                    val props = Properties()
                    actualFile.inputStream().use { props.load(it) }

                    val enabled = props.getProperty("enabled", "false").toBooleanStrictOrNull() ?: false
                    enabledRef.set(enabled)

                    modeRef.set(props.getProperty("mode", "global"))
                    simTypeRef.set(props.getProperty("sim_type", "location"))
                    wifiMockRef.set(props.getProperty("wifi_mock", "false").toBooleanStrictOrNull() ?: false)
                    cellMockRef.set(props.getProperty("cell_mock", "false").toBooleanStrictOrNull() ?: false)
                    gnssMockRef.set(props.getProperty("gnss_mock", "false").toBooleanStrictOrNull() ?: false)

                    // Step simulation params (written by ServiceGoRoot)
                    stepMockRef.set(props.getProperty("step_mock", "false").toBooleanStrictOrNull() ?: false)
                    stepFreqRef.set(props.getProperty("step_freq", "120.0").toFloatOrNull() ?: 120f)
                    stepSchemeRef.set(props.getProperty("step_scheme", "0") ?: "0")

                    targets = props.getProperty("target_packages", "")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    targetPackagesRef.set(targets)

                    if (enabled) {
                        val lat = props.getProperty("lat", "0.0").toDoubleOrNull() ?: 0.0
                        val lon = props.getProperty("lon", "0.0").toDoubleOrNull() ?: 0.0
                        val alt = props.getProperty("alt", "0.0").toDoubleOrNull() ?: 0.0
                        val speed = props.getProperty("speed", "0.0").toFloatOrNull() ?: 0f
                        val bearing = props.getProperty("bearing", "0.0").toFloatOrNull() ?: 0f
                        val accuracy = props.getProperty("accuracy", "25.0").toFloatOrNull() ?: 25.0f

                        val loc = Location(LocationManager.GPS_PROVIDER)
                        loc.latitude = lat
                        loc.longitude = lon
                        loc.altitude = alt
                        loc.time = System.currentTimeMillis()
                        locationRef.set(loc)

                        altitudeRef.set(alt)
                        speedRef.set(speed)
                        bearingRef.set(bearing)
                        accuracyRef.set(accuracy)
                    }

                    Log.i(TAG, "Config loaded: enabled=$enabled mode=${getMode()} sim=${getSimType()} " +
                        "wifi=${isWifiMockEnabled()} cell=${isCellMockEnabled()} gnss=${isGnssMockEnabled()} " +
                        "wifiProxy=${wifiProxyEnabledRef.get()} cellProxy=${cellProxyEnabledRef.get()} " +
                        "lat=${getLatitude()} lon=${getLongitude()} targets=$targets")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to reload conf file", e)
                }
            }
        } else {
            Log.w(TAG, "Config file not found at $CONFIG_PATH or $FALLBACK_CONFIG_PATH")
        }
    }

    @JvmStatic
    fun loadWifiConfig() {
        try {
            val file = File(WIFI_CONFIG_PATH)
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val obj = JSONObject(json)
            wifiProxyEnabledRef.set(obj.optBoolean("enabled", false))
            val list = mutableListOf<WifiEntry>()
            val arr = obj.optJSONArray("list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(WifiEntry(
                        ssid = item.optString("ssid", ""),
                        bssid = item.optString("bssid", ""),
                        rssi = item.optInt("rssi", -50),
                        frequency = item.optInt("frequency", 2412),
                        linkSpeed = item.optInt("linkSpeed", 65),
                        capabilities = item.optString("capabilities", "")
                    ))
                }
            }
            wifiListRef.set(list)
        } catch (e: Throwable) {
            Log.w(TAG, "loadWifiConfig failed: ${e.message}")
        }
    }

    @JvmStatic
    fun loadCellConfig() {
        try {
            val file = File(CELL_CONFIG_PATH)
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val obj = JSONObject(json)
            cellProxyEnabledRef.set(obj.optBoolean("enabled", false))
            val list = mutableListOf<CellEntry>()
            val arr = obj.optJSONArray("list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(CellEntry(
                        networkType = item.optString("networkType", "LTE"),
                        mcc = item.optInt("mcc", 460),
                        mnc = item.optInt("mnc", 0),
                        lac = item.optInt("lac", 0),
                        cid = item.optLong("cid", 0),
                        psc = item.optInt("psc", 0),
                        latitude = item.optDouble("latitude", 0.0),
                        longitude = item.optDouble("longitude", 0.0),
                        radius = item.optDouble("radius", 1000.0).toFloat()
                    ))
                }
            }
            cellListRef.set(list)
        } catch (e: Throwable) {
            Log.w(TAG, "loadCellConfig failed: ${e.message}")
        }
    }

    // ========== Utility for Hooks ==========
    @JvmStatic
    fun setField(obj: Any, fieldName: String, value: Any?) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (_: Throwable) {}
    }
}