package com.kail.location.service.Sandbox

import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager

/**
 * 沙盒位置模拟 Hook。
 * 通过 BlackBox 的 BLocationManager 向沙盒内应用注入模拟位置。
 */
object SandboxLocationHook {

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

    /**
     * 启用全局沙盒位置模拟。
     * 所有沙盒中的应用都会收到相同的模拟位置。
     */
    fun enableGlobalSimulation() {
        try {
            BLocationManager.get().setPattern(0, "", BLocationManager.GLOBAL_MODE)
            isSimulating = true
        } catch (e: Exception) {
            android.util.Log.e("SandboxLocationHook", "Failed to enable global simulation: ${e.message}")
        }
    }

    /**
     * 禁用沙盒位置模拟。
     */
    fun disableSimulation() {
        try {
            BLocationManager.get().setPattern(0, "", BLocationManager.CLOSE_MODE)
            isSimulating = false
        } catch (e: Exception) {
            android.util.Log.e("SandboxLocationHook", "Failed to disable simulation: ${e.message}")
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
                BLocationManager.get().setGlobalLocation(bLocation)
            } catch (e: Exception) {
                android.util.Log.e("SandboxLocationHook", "Failed to update location: ${e.message}")
            }
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
