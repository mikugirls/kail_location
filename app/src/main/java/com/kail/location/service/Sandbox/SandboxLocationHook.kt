package com.kail.location.service.Sandbox

import com.kail.location.utils.KailLog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager

/**
 * 沙盒位置模拟 Hook。
 * 通过 BlackBox 的 BLocationManager 向沙盒内应用注入模拟位置。
 */
object SandboxLocationHook {

    private const val TAG = "SandboxLocationHook"

    @Volatile
    private var isSimulating = false

    @Volatile
    private var currentLat = 0.0
    @Volatile
    private var currentLng = 0.0
    @Volatile
    private var currentAlt = 0.0
    @Volatile
    private var currentBea = 0f
    @Volatile
    private var currentSpeed = 0.0
    private var updateCount = 0

    /**
     * 启用全局沙盒位置模拟。
     * 所有沙盒中的应用都会收到相同的模拟位置。
     */
    fun enableGlobalSimulation() {
        try {
            KailLog.i(null, "[sandbox]SandboxLocationHook", "enableGlobalSimulation: calling BLocationManager.get().setPattern(0, \"\", GLOBAL_MODE)...")
            BLocationManager.get().setPattern(0, "", BLocationManager.GLOBAL_MODE)
            isSimulating = true
            // Verify pattern was set
            val pattern = BLocationManager.get().getPattern(0, "")
            KailLog.i(null, "[sandbox]SandboxLocationHook", "enableGlobalSimulation: done, isSimulating=true, verify pattern=$pattern (expected ${BLocationManager.GLOBAL_MODE})")
        } catch (e: Exception) {
            KailLog.e(null, TAG, "Failed to enable global simulation", e)
        }
    }

    /**
     * 禁用沙盒位置模拟。
     */
    fun disableSimulation() {
        try {
            KailLog.i(null, "[sandbox]SandboxLocationHook", "disableSimulation: calling BLocationManager.get().setPattern(0, \"\", CLOSE_MODE)...")
            BLocationManager.get().setPattern(0, "", BLocationManager.CLOSE_MODE)
            isSimulating = false
            KailLog.i(null, "[sandbox]SandboxLocationHook", "disableSimulation: done")
        } catch (e: Exception) {
            KailLog.e(null, TAG, "Failed to disable simulation", e)
        }
    }

    /**
     * 更新模拟位置并注入到沙盒环境。
     */
    fun updateLocation(lat: Double, lng: Double, alt: Double, bearing: Float, speed: Double) {
        currentLat = lat
        currentLng = lng
        currentAlt = alt
        currentBea = bearing
        currentSpeed = speed

        if (isSimulating) {
            try {
                val bLocation = BLocation(lat, lng)
                updateCount++
                BLocationManager.get().setGlobalLocation(bLocation)
                if (updateCount % 20 == 0) {
                    val stored = BLocationManager.get().getGlobalLocation()
                    if (stored != null) {
                        KailLog.i(null, "[sandbox]SandboxLocationHook", "updateLocation #$updateCount: set($lat, $lng) verify=(${stored.latitude}, ${stored.longitude})")
                    } else {
                        KailLog.e(null, "[sandbox]SandboxLocationHook", "updateLocation #$updateCount: FAILED - getGlobalLocation() returned null!")
                    }
                }
            } catch (e: Exception) {
                KailLog.e(null, TAG, "Failed to update location", e)
            }
        } else {
            KailLog.w(null, "[sandbox]SandboxLocationHook", "updateLocation: SKIPPED - isSimulating=false")
        }
    }

    /**
     * 获取当前模拟位置。
     */
    fun getCurrentLocation(): DoubleArray {
        return doubleArrayOf(currentLng, currentLat, currentAlt, currentBea.toDouble(), currentSpeed)
    }

    /**
     * 是否正在模拟。
     */
    fun isSimulating(): Boolean = isSimulating
}
