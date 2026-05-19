package com.kail.location.root

import android.content.Context
import com.kail.location.utils.KailLog

/**
 * Root Injection Entry Point
 * This class is called when the native library is injected into system_server or target processes.
 * It registers the fake services into ServiceManager.
 * Based on Fake Location's C0012
 */
class RootInjectionEntry {

    companion object {
        private var initialized = false

        private val locationManagerStub = MockLocationManagerStub()
        private val wifiManagerStub = MockWifiManagerStub()
        private val sensorManagerStub = MockSensorManagerStub()

        /**
         * Initialize the injection
         * Called from native code after library injection
         */
        @JvmStatic
        fun init(context: Context, packageName: String) {
            if (initialized) {
                KailLog.i(null, "RootInjectionEntry", "Already initialized, skipping")
                return
            }

            KailLog.i(null, "RootInjectionEntry", "Initializing for package: $packageName")

            try {
                // Replace location service
                ServiceManagerHijacker.replaceService("location", locationManagerStub)

                // Replace wifi service
                ServiceManagerHijacker.replaceService("wifi", wifiManagerStub)

                // Replace sensor service
                ServiceManagerHijacker.replaceService("sensor", sensorManagerStub)

                initialized = true
                KailLog.i(null, "RootInjectionEntry", "Initialization complete")
            } catch (e: Exception) {
                KailLog.e(null, "RootInjectionEntry", "Initialization failed: ${e.message}")
                e.printStackTrace()
            }
        }

        /**
         * Get the location manager stub instance
         */
        @JvmStatic
        fun getLocationManagerStub(): MockLocationManagerStub = locationManagerStub

        /**
         * Get the wifi manager stub instance
         */
        @JvmStatic
        fun getWifiManagerStub(): MockWifiManagerStub = wifiManagerStub

        /**
         * Get the sensor manager stub instance
         */
        @JvmStatic
        fun getSensorManagerStub(): MockSensorManagerStub = sensorManagerStub

        /**
         * Stop all simulations and restore original services
         */
        @JvmStatic
        fun stopAll() {
            KailLog.i(null, "RootInjectionEntry", "Stopping all simulations")
            try {
                locationManagerStub.stopMockLocation()
                wifiManagerStub.stopMockWifi()
                sensorManagerStub.stopMockSteps()
                ServiceManagerHijacker.restoreAll()
                initialized = false
            } catch (e: Exception) {
                KailLog.e(null, "RootInjectionEntry", "Stop all failed: ${e.message}")
            }
        }
    }
}
