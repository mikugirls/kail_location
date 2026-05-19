package com.kail.location.root

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.kail.location.utils.KailLog

/**
 * WiFi Hook Controller
 * Manages the hooking of WifiServiceImpl methods
 * Simplified version based on Fake Location's C0089
 */
class WifiHookController {

    private var mockWifi: WifiInfoData? = null
    private var mockWifiList: List<WifiInfoData> = emptyList()
    private var isMocking = false
    private var allowList: List<String> = emptyList()
    private var blockList: List<String> = emptyList()

    fun startMockWifi() {
        KailLog.i(null, "WifiHookController", "startMockWifi")
        isMocking = true
    }

    fun stopMockWifi() {
        KailLog.i(null, "WifiHookController", "stopMockWifi")
        isMocking = false
    }

    fun setMockWifi(wifi: WifiInfoData) {
        mockWifi = wifi
    }

    fun setMockWifiList(wifiList: List<WifiInfoData>) {
        mockWifiList = wifiList
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

    fun buildFakeWifiInfo(): WifiInfo? {
        val wifi = mockWifi ?: return null
        return try {
            val wifiInfo = WifiInfo::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            // Use reflection to set private fields
            setField(wifiInfo, "mSSID", wifi.ssid)
            setField(wifiInfo, "mBSSID", wifi.bssid)
            setField(wifiInfo, "mRssi", wifi.level)
            setField(wifiInfo, "mLinkSpeed", wifi.linkSpeed)
            setField(wifiInfo, "mFrequency", wifi.frequency)
            wifiInfo
        } catch (e: Exception) {
            KailLog.e(null, "WifiHookController", "buildFakeWifiInfo failed: ${e.message}")
            null
        }
    }

    fun buildFakeScanResults(): List<ScanResult>? {
        if (mockWifiList.isEmpty()) return null
        return mockWifiList.map { wifi ->
            val scanResult = ScanResult::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            try {
                setField(scanResult, "SSID", wifi.ssid)
                setField(scanResult, "BSSID", wifi.bssid)
                setField(scanResult, "capabilities", wifi.capabilities)
                setField(scanResult, "level", wifi.level)
                setField(scanResult, "frequency", wifi.frequency)
            } catch (e: Exception) {
                KailLog.e(null, "WifiHookController", "buildFakeScanResults failed: ${e.message}")
            }
            scanResult
        }
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (e: Exception) {
            KailLog.e(null, "WifiHookController", "setField $fieldName failed: ${e.message}")
        }
    }

    fun isMocking(): Boolean = isMocking
}
