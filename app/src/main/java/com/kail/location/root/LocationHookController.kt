package com.kail.location.root

import android.location.Location
import android.telephony.CellInfo
import android.telephony.NeighboringCellInfo
import com.kail.location.utils.KailLog

/**
 * Location Hook Controller
 * Manages the hooking of LocationManagerService methods
 * Simplified version based on Fake Location's C0041
 */
class LocationHookController {

    private var mockLocation: LocationData? = null
    private var mockCells: List<CellInfoData> = emptyList()
    private var isMocking = false
    var enableMockGnss = false
    private var _antiPullback = false
    val antiPullback: Boolean get() = _antiPullback
    private var allowList: List<String> = emptyList()
    private var blockList: List<String> = emptyList()
    private val locationListeners = mutableListOf<Any>()

    fun startMockLocation() {
        KailLog.i(null, "LocationHookController", "startMockLocation")
        isMocking = true
    }

    fun stopMockLocation() {
        KailLog.i(null, "LocationHookController", "stopMockLocation")
        isMocking = false
        locationListeners.clear()
    }

    fun setMockLocation(location: LocationData) {
        mockLocation = location
        KailLog.i(null, "LocationHookController", "setMockLocation: lat=${location.latitude}, lng=${location.longitude}")

        // Notify registered listeners
        if (_antiPullback) {
            notifyListeners()
        }
    }

    fun setMockCells(cells: List<CellInfoData>) {
        mockCells = cells
    }

    fun setMockGnss(enable: Boolean) {
        enableMockGnss = enable
    }

    fun setAntiPullback(enable: Boolean) {
        _antiPullback = enable
    }

    fun setAllowList(packages: List<String>) {
        allowList = packages
    }

    fun setBlockList(packages: List<String>) {
        blockList = packages
    }

    fun isAllowMockPackage(pkg: String): Boolean {
        if (blockList.contains(pkg)) return false
        if (allowList.isEmpty()) return true
        return allowList.contains(pkg)
    }

    fun buildFakeLocation(): Location? {
        val loc = mockLocation ?: return null
        val location = Location(loc.provider)
        location.latitude = loc.latitude
        location.longitude = loc.longitude
        location.altitude = loc.altitude
        location.accuracy = loc.accuracy
        location.speed = loc.speed
        location.bearing = loc.bearing
        location.time = loc.time
        return location
    }

    fun buildFakeCellInfo(): List<CellInfo>? {
        if (mockCells.isEmpty()) return null
        return CellInfoBuilder.buildCellInfoList(mockCells)
    }

    fun buildFakeNeighboringCellInfo(): List<NeighboringCellInfo>? {
        if (mockCells.isEmpty()) return null
        return CellInfoBuilder.buildNeighboringCellInfoList(mockCells)
    }

    fun registerLocationListener(listener: Any) {
        if (!locationListeners.contains(listener)) {
            locationListeners.add(listener)
        }
    }

    fun injectGpsStatus(listener: Any) {
        KailLog.i(null, "LocationHookController", "injectGpsStatus for listener: ${listener.javaClass.name}")
        // TODO: Implement GNSS status injection via reflection
    }

    fun injectGnssStatus(callback: Any) {
        KailLog.i(null, "LocationHookController", "injectGnssStatus for callback: ${callback.javaClass.name}")
        // TODO: Implement GNSS status injection via reflection
    }

    private fun notifyListeners() {
        val location = buildFakeLocation() ?: return
        for (listener in locationListeners) {
            try {
                // Try to call onLocationChanged via reflection
                val method = listener.javaClass.getMethod("onLocationChanged", Location::class.java)
                method.invoke(listener, location)
            } catch (e: Exception) {
                // Listener might have been garbage collected
            }
        }
    }

    fun isMocking(): Boolean = isMocking
}
