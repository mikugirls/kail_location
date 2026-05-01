package com.kail.location.xposed.utils

import android.location.Location
import de.robv.android.xposed.XposedBridge
import com.kail.location.xposed.base.BaseLocationHook
import com.kail.location.xposed.utils.FakeLoc
import com.kail.location.utils.KailLog

object BlindHookLocation: BaseLocationHook() {
    operator fun invoke(clazz: Class<*>, classLoader: ClassLoader): Int {
        return BlindHook(clazz, classLoader) { method, location: Location? ->
            if (location == null || !FakeLoc.enable) return@BlindHook location

            val newLoc = injectLocation(location)

            if (FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "${method.name} injected: $newLoc")
            }

            newLoc
        }
    }
}