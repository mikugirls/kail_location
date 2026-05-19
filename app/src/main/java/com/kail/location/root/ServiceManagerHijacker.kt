package com.kail.location.root

import android.os.IBinder
import com.kail.location.utils.KailLog

/**
 * ServiceManager Hijacker
 * Replaces system Binder services with custom implementations using reflection
 * Based on Fake Location's C0073
 */
object ServiceManagerHijacker {

    private val hijackedServices = mutableMapOf<String, IBinder>()

    /**
     * Replace a system service in ServiceManager
     * @param name Service name (e.g., "location", "wifi", "sensor")
     * @param binder Custom Binder implementation
     */
    fun replaceService(name: String, binder: IBinder): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")

            // Method 1: Replace in sCache
            try {
                val sCacheField = serviceManagerClass.getDeclaredField("sCache")
                sCacheField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val sCache = sCacheField.get(null) as? MutableMap<String, IBinder>

                if (sCache != null) {
                    val original = sCache[name]
                    if (original != null) {
                        hijackedServices[name] = original
                    }
                    sCache[name] = binder
                    KailLog.i(null, "ServiceManagerHijacker", "Replaced service '$name' in sCache")
                }
            } catch (e: Exception) {
                KailLog.w(null, "ServiceManagerHijacker", "sCache replacement failed: ${e.message}")
            }

            // Method 2: Call addService directly
            try {
                val addServiceMethod = serviceManagerClass.getDeclaredMethod(
                    "addService",
                    String::class.java,
                    IBinder::class.java
                )
                addServiceMethod.invoke(null, name, binder)
                KailLog.i(null, "ServiceManagerHijacker", "Added service '$name' via addService")
            } catch (e: Exception) {
                KailLog.w(null, "ServiceManagerHijacker", "addService failed for '$name': ${e.message}")
            }

            true
        } catch (e: Exception) {
            KailLog.e(null, "ServiceManagerHijacker", "Failed to replace service '$name': ${e.message}")
            false
        }
    }

    /**
     * Restore original system service
     */
    fun restoreService(name: String): Boolean {
        val original = hijackedServices[name] ?: return false
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val sCacheField = serviceManagerClass.getDeclaredField("sCache")
            sCacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val sCache = sCacheField.get(null) as? MutableMap<String, IBinder>
            sCache?.put(name, original)
            hijackedServices.remove(name)
            KailLog.i(null, "ServiceManagerHijacker", "Restored service '$name'")
            true
        } catch (e: Exception) {
            KailLog.e(null, "ServiceManagerHijacker", "Failed to restore service '$name': ${e.message}")
            false
        }
    }

    /**
     * Restore all hijacked services
     */
    fun restoreAll() {
        val names = hijackedServices.keys.toList()
        for (name in names) {
            restoreService(name)
        }
    }
}
