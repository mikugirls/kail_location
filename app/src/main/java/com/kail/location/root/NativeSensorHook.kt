package com.kail.location.root

object NativeSensorHook {
    init {
        System.loadLibrary("kail_native_hook")
    }

    external fun nativeSetWriteOffset(offset: Long)
    external fun nativeSetConvertOffset(offset: Long)
    external fun nativeSetRouteSimulation(active: Boolean, spm: Float, mode: Int)
    external fun nativeSetGaitParams(spm: Float, mode: Int, scheme: Int, enable: Boolean)
    external fun nativeReloadConfig(): Boolean
    external fun nativeSetMocking(mocking: Int)
    external fun nativeSetAuthorized(authorized: Int)
    external fun nativeSetStepSimEnabled(enabled: Boolean)
    external fun nativeReset()
    external fun nativeInitHook()
}
