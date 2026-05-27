package com.kail.location.service.Developer

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.kail.location.R
import com.kail.location.utils.KailLog

/**
 * 负责 developer 模式下 Mock Location Provider 的注册、更新与清理。
 */
class MockLocationProvider(
    private val context: Context,
    private val locationManager: LocationManager
) {

    fun ensureProviders() {
        try {
            removeTestProviderNetwork()
            addTestProviderNetwork()
            removeTestProviderGPS()
            addTestProviderGPS()
        } catch (e: Throwable) {
            KailLog.e(null, "MockLocationProvider", "Error ensuring providers: ${e.message}")
        }
    }

    fun setLocation(
        lat: Double,
        lng: Double,
        alt: Double,
        bea: Float,
        speed: Double,
        isStop: Boolean
    ) {
        setLocationGPS(lat, lng, alt, bea, speed, isStop)
        setLocationNetwork(lat, lng, alt, bea, speed, isStop)
    }

    fun cleanup() {
        removeTestProviderNetwork()
        removeTestProviderGPS()
    }

    @SuppressLint("WrongConstant")
    private fun addTestProviderGPS() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true, 3, 1
                )
            }
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            }
        } catch (e: Exception) {
            KailLog.e(null, "MockLocationProvider", "addTestProviderGPS error: ${e.message}")
            showMockLocationPermissionToast(e)
        }
    }

    private fun removeTestProviderGPS() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
        } catch (e: Exception) {
            KailLog.e(null, "MockLocationProvider", "removeTestProviderGPS error: ${e.message}")
        }
    }

    private fun setLocationGPS(
        lat: Double, lng: Double, alt: Double, bea: Float, speed: Double, isStop: Boolean
    ) {
        try {
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                accuracy = 1.0f
                this.altitude = alt
                bearing = bea
                this.latitude = lat
                this.longitude = lng
                time = System.currentTimeMillis()
                val speedToSet = if (isStop) 0.0f else speed.toFloat()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this.speed = speedToSet
                    speedAccuracyMetersPerSecond = 0.1f
                    verticalAccuracyMeters = 0.1f
                    bearingAccuracyDegrees = 0.1f
                } else {
                    this.speed = speedToSet
                }
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = android.os.Bundle().apply { putInt("satellites", 7) }
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: Exception) {
            KailLog.e(null, "MockLocationProvider", "setLocationGPS error: ${e.message}")
        }
    }

    @SuppressLint("WrongConstant")
    private fun addTestProviderNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, 1, 2
                )
            }
            if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            }
        } catch (e: SecurityException) {
            KailLog.e(null, "MockLocationProvider", "addTestProviderNetwork error: ${e.message}")
            showMockLocationPermissionToast(e)
        }
    }

    private fun showMockLocationPermissionToast(e: Exception) {
        if (e.message?.contains("not allowed to perform MOCK_LOCATION") == true) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.service_set_mock_app), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun removeTestProviderNetwork() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            KailLog.e(null, "MockLocationProvider", "removeTestProviderNetwork error: ${e.message}")
        }
    }

    private fun setLocationNetwork(
        lat: Double, lng: Double, alt: Double, bea: Float, speed: Double, isStop: Boolean
    ) {
        try {
            val loc = Location(LocationManager.NETWORK_PROVIDER).apply {
                accuracy = 1.0f
                this.altitude = alt
                bearing = bea
                this.latitude = lat
                this.longitude = lng
                time = System.currentTimeMillis()
                val speedToSet = if (isStop) 0.0f else speed.toFloat()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this.speed = speedToSet
                    speedAccuracyMetersPerSecond = 0.1f
                    verticalAccuracyMeters = 0.1f
                    bearingAccuracyDegrees = 0.1f
                } else {
                    this.speed = speedToSet
                }
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = android.os.Bundle().apply { putInt("satellites", 7) }
            }
            locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc)
        } catch (e: Exception) {
            KailLog.e(null, "MockLocationProvider", "setLocationNetwork error: ${e.message}")
        }
    }
}
