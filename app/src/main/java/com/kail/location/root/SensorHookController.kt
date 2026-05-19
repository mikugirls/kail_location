package com.kail.location.root

import com.kail.location.utils.KailLog

/**
 * Sensor Hook Controller
 * Manages the hooking of sensor pipeline for step simulation
 * Simplified version based on Fake Location's C0058
 */
class SensorHookController {

    private var isMocking = false
    private var stepsPerMinute: Float = 120f
    private var scheme: Int = 0

    fun startMockSteps() {
        KailLog.i(null, "SensorHookController", "startMockSteps")
        isMocking = true
        // TODO: Implement native hooking for sensor injection
        // Hook nativeIsDataInjectionEnabled and sensor event delivery
    }

    fun stopMockSteps() {
        KailLog.i(null, "SensorHookController", "stopMockSteps")
        isMocking = false
        // TODO: Remove hooks
    }

    fun setStepParams(spm: Float, schemeId: Int) {
        stepsPerMinute = spm
        scheme = schemeId
    }

    fun isMocking(): Boolean = isMocking
}
