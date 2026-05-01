package com.kail.location.xposed.hooks.fused

import android.location.Location
import com.kail.location.xposed.base.BaseLocationHook
import com.kail.location.xposed.utils.BlindHookLocation
import com.kail.location.xposed.utils.FakeLoc
import com.kail.location.utils.KailLog
import com.kail.location.xposed.utils.hookMethodAfter
import com.kail.location.xposed.utils.toClass

object AndroidFusedLocationProviderHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        val cFusedLocationProvider = "com.android.location.fused.FusedLocationProvider".toClass(classLoader)
        if (cFusedLocationProvider == null) {
            KailLog.w(null, "Kail_Xposed", "Failed to find FusedLocationProvider")
            return
        }

        if(!initDivineService("AndroidFusedLocationProvider")) {
            KailLog.e(null, "Kail_Xposed", "Failed to init DivineService in AndroidFusedLocationProvider")
            return
        }

        cFusedLocationProvider.hookMethodAfter("chooseBestLocation", Location::class.java, Location::class.java) {
            if (result == null) return@hookMethodAfter

            if (FakeLoc.enable) {
                result = injectLocation(result as Location)
            }
        }

//        cFusedLocationProvider.hookMethodBefore("reportBestLocationLocked") {
//
//        }

        val cChildLocationListener = "com.android.location.fused.FusedLocationProvider\$ChildLocationListener".toClass(classLoader)
        if (cChildLocationListener == null) {
            KailLog.w(null, "Kail_Xposed", "Failed to find ChildLocationListener")
            return
        }

        BlindHookLocation(cChildLocationListener, classLoader)
    }
}