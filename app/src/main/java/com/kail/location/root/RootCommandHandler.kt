package com.kail.location.root

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/**
 * Handles kail commands sent via sendExtraCommand.
 * Replaces KailCommandHandler for root-only mode.
 */
object RootCommandHandler {
    private const val TAG = "KailRootCmd"
    private var keyRef: String? = null

    @JvmStatic
    fun handle(commandId: String, bundle: Bundle): Boolean {
        Log.d(TAG, "handle: $commandId")
        return when (commandId) {
            "exchange_key" -> {
                val key = "k${System.currentTimeMillis()}"
                keyRef = key
                bundle.putString("key", key)
                true
            }
            "is_start" -> {
                bundle.putBoolean("is_start", RootConfigManager.isEnabled())
                true
            }
            "start" -> {
                RootConfigManager.enabledRef.set(true)
                bundle.putBoolean("started", true)
                bundle.getDouble("altitude", Double.NaN).let {
                    if (!it.isNaN()) RootConfigManager.altitudeRef.set(it)
                }
                true
            }
            "stop" -> {
                RootConfigManager.enabledRef.set(false)
                bundle.putBoolean("stopped", true)
                true
            }
            "get_location" -> {
                val lat = RootConfigManager.getLatitude()
                val lon = RootConfigManager.getLongitude()
                bundle.putDouble("lat", lat)
                bundle.putDouble("lon", lon)
                bundle.putBoolean("ok", true)
                true
            }
            "set_speed" -> {
                val speed = bundle.getFloat("speed", 0f)
                RootConfigManager.speedRef.set(speed)
                bundle.putBoolean("ok", true)
                true
            }
            "set_bearing" -> {
                val bearing = bundle.getDouble("bearing", 0.0).toFloat()
                RootConfigManager.bearingRef.set(bearing)
                bundle.putBoolean("ok", true)
                true
            }
            "set_altitude" -> {
                val altitude = bundle.getDouble("altitude", Double.NaN)
                if (!altitude.isNaN()) {
                    RootConfigManager.altitudeRef.set(altitude)
                }
                bundle.putBoolean("ok", true)
                true
            }
            "update_location" -> {
                val lat = bundle.getDouble("lat", Double.NaN)
                val lon = bundle.getDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    val loc = Location(LocationManager.GPS_PROVIDER)
                    loc.latitude = lat
                    loc.longitude = lon
                    loc.altitude = RootConfigManager.getAltitude()
                    loc.time = System.currentTimeMillis()
                    RootConfigManager.locationRef.set(loc)
                }
                bundle.putBoolean("ok", true)
                true
            }
            "broadcast_location" -> {
                bundle.putBoolean("ok", true)
                true
            }
            "set_config" -> {
                bundle.putBoolean("ok", true)
                true
            }
            "load_library" -> {
                val path = bundle.getString("path")
                if (!path.isNullOrEmpty()) {
                    try {
                        System.load(path)
                        NativeSensorHook.markSoLoaded()
                        bundle.putBoolean("ok", true)
                        bundle.putString("result", "loaded: $path")
                        Log.i(TAG, "Library loaded: $path")
                    } catch (e: Throwable) {
                        bundle.putBoolean("ok", false)
                        bundle.putString("result", "failed: ${e.message}")
                        Log.e(TAG, "Failed to load library: ${e.message}")
                    }
                } else {
                    bundle.putBoolean("ok", false)
                    bundle.putString("result", "path is null")
                }
                true
            }
            else -> false
        }
    }
}
