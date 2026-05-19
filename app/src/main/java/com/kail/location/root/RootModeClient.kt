package com.kail.location.root

import android.content.Context
import com.kail.location.service.Root.RootHookManager
import com.kail.location.utils.KailLog
import com.kail.location.utils.service.ServiceConstants

/**
 * Root Mode Client
 * Provides a unified API for the app to control the root mode hooking
 * Bridges the app-side configuration with the injected module's Binder services
 *
 * Architecture:
 * 1. App (ServiceGoRoot) -> writes config via this client
 * 2. RootHookManager -> injects native library into system_server
 * 3. Injected module -> loads Java classes, calls RootInjectionEntry.init()
 * 4. RootInjectionEntry -> replaces system services with Binder stubs
 * 5. Binder stubs -> use HookControllers to intercept calls
 */
class RootModeClient(private val context: Context) {

    private val rootHookManager = RootHookManager(context)

    // Direct references to the Binder stubs (used when running in the same process as injected module)
    // In practice, these are accessed via reflection from the injected module
    private var locationStub: MockLocationManagerStub? = null
    private var wifiStub: MockWifiManagerStub? = null
    private var sensorStub: MockSensorManagerStub? = null

    var currentLat: Double = ServiceConstants.DEFAULT_LAT
    var currentLng: Double = ServiceConstants.DEFAULT_LNG
    var currentAlt: Double = ServiceConstants.DEFAULT_ALT
    var currentBea: Float = ServiceConstants.DEFAULT_BEA
    var currentSpeed: Double = 1.2

    var stepEnabled: Boolean = false
    var stepFreq: Double = 0.0
    var simScheme: Int = 0
    var stepSimEnabled: Boolean = true

    var enableMockGnss: Boolean = true
    var disableFused: Boolean = false
    var hideMock: Boolean = false
    var hookWifi: Boolean = false
    var downgradeCdma: Boolean = false
    var antiPullback: Boolean = false
    var minSatellites: Int = 12
    var reportIntervalMs: Int = 100

    /**
     * Initialize the root mode injection
     */
    fun init(): Boolean {
        KailLog.i(context, "RootModeClient", "init")
        return rootHookManager.initIfNeeded()
    }

    /**
     * Start the root mode hooking
     */
    fun start(): Boolean {
        KailLog.i(context, "RootModeClient", "start")
        if (!rootHookManager.startIfNeeded()) return false

        // Write the initial config
        writeConfig()

        // If we're in the same process as the injected module, directly control the stubs
        tryBindToStubs()

        return true
    }

    /**
     * Update location and notify the injected module
     */
    fun updateLocation() {
        KailLog.i(context, "RootModeClient", "updateLocation: lat=$currentLat, lng=$currentLng")

        // Method 1: Write to config file (existing mechanism)
        writeConfig()

        // Method 2: Direct Binder call (if stubs are accessible)
        locationStub?.let { stub ->
            val loc = LocationData(
                latitude = currentLat,
                longitude = currentLng,
                altitude = currentAlt,
                speed = currentSpeed.toFloat(),
                bearing = currentBea
            )
            stub.setMockLocation(loc)
        }
    }

    /**
     * Start location mocking
     */
    fun startMockLocation(): Boolean {
        KailLog.i(context, "RootModeClient", "startMockLocation")
        writeConfig()
        return locationStub?.startMockLocation() ?: false
    }

    /**
     * Stop location mocking
     */
    fun stopMockLocation(): Boolean {
        KailLog.i(context, "RootModeClient", "stopMockLocation")
        writeConfig()
        return locationStub?.stopMockLocation() ?: false
    }

    /**
     * Start WiFi mocking
     */
    fun startMockWifi(): Boolean {
        KailLog.i(context, "RootModeClient", "startMockWifi")
        return wifiStub?.startMockWifi() ?: false
    }

    /**
     * Stop WiFi mocking
     */
    fun stopMockWifi(): Boolean {
        KailLog.i(context, "RootModeClient", "stopMockWifi")
        return wifiStub?.stopMockWifi() ?: false
    }

    /**
     * Start step sensor mocking
     */
    fun startMockSteps(): Boolean {
        KailLog.i(context, "RootModeClient", "startMockSteps")
        return sensorStub?.startMockSteps() ?: false
    }

    /**
     * Stop step sensor mocking
     */
    fun stopMockSteps(): Boolean {
        KailLog.i(context, "RootModeClient", "stopMockSteps")
        return sensorStub?.stopMockSteps() ?: false
    }

    /**
     * Stop all mocking and restore system services
     */
    fun stopAll() {
        KailLog.i(context, "RootModeClient", "stopAll")
        locationStub?.stopMockLocation()
        wifiStub?.stopMockWifi()
        sensorStub?.stopMockSteps()
        rootHookManager.stopSafe()
    }

    /**
     * Write config to the shared file
     * This is the primary IPC mechanism between app and injected module
     */
    fun writeConfig() {
        // Write config via the existing RootHookManager
        rootHookManager.currentLat = currentLat
        rootHookManager.currentLng = currentLng
        rootHookManager.currentAlt = currentAlt
        rootHookManager.currentBea = currentBea
        rootHookManager.currentSpeed = currentSpeed
        rootHookManager.stepEnabled = stepEnabled
        rootHookManager.stepFreq = stepFreq
        rootHookManager.simScheme = simScheme
        rootHookManager.stepSimEnabled = stepSimEnabled
        rootHookManager.writeConfig()
    }

    /**
     * Try to bind to the injected Binder stubs
     * This works when the app and injected module share the same process
     * (e.g., when injecting into the app's own process)
     */
    private fun tryBindToStubs() {
        try {
            // Try to get the stubs from RootInjectionEntry via reflection
            val entryClass = Class.forName("com.kail.location.root.RootInjectionEntry")
            val getLocationMethod = entryClass.getMethod("getLocationManagerStub")
            val getWifiMethod = entryClass.getMethod("getWifiManagerStub")
            val getSensorMethod = entryClass.getMethod("getSensorManagerStub")

            locationStub = getLocationMethod.invoke(null) as? MockLocationManagerStub
            wifiStub = getWifiMethod.invoke(null) as? MockWifiManagerStub
            sensorStub = getSensorMethod.invoke(null) as? MockSensorManagerStub

            KailLog.i(context, "RootModeClient", "Bound to stubs: location=${locationStub != null}, wifi=${wifiStub != null}, sensor=${sensorStub != null}")
        } catch (e: Exception) {
            KailLog.w(context, "RootModeClient", "Could not bind to stubs (expected when not in injected process): ${e.message}")
        }
    }
}
