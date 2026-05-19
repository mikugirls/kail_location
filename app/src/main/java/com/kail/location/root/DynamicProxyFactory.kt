package com.kail.location.root

import com.kail.location.utils.KailLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Dynamic AIDL Proxy Factory
 * Based on Fake Location's C0077
 * Creates dynamic proxy objects for system AIDL interfaces at runtime
 */
object DynamicProxyFactory {

    private val proxyCache = mutableMapOf<ClassLoader, MutableMap<String, Any>>()

    /**
     * Create a dynamic proxy for a system AIDL interface
     * @param classLoader The ClassLoader to use
     * @param interfaceName The full name of the AIDL interface (e.g., "android.location.ILocationManager")
     * @param handler The InvocationHandler that intercepts method calls
     * @return The proxy object
     */
    fun createProxy(
        classLoader: ClassLoader,
        interfaceName: String,
        handler: InvocationHandler
    ): Any? {
        return try {
            val iface = Class.forName(interfaceName, true, classLoader)
            val proxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(iface),
                handler
            )
            proxyCache.getOrPut(classLoader) { mutableMapOf() }[interfaceName] = proxy
            proxy
        } catch (e: Exception) {
            KailLog.e(null, "DynamicProxyFactory", "Failed to create proxy for $interfaceName: ${e.message}")
            null
        }
    }

    /**
     * Create a proxy that intercepts LocationManagerService methods
     */
    fun createLocationManagerProxy(
        classLoader: ClassLoader,
        hookController: LocationHookController
    ): Any? {
        return createProxy(classLoader, "android.location.ILocationManager", LocationManagerInvocationHandler(hookController))
    }

    /**
     * Create a proxy that intercepts WifiManager methods
     */
    fun createWifiManagerProxy(
        classLoader: ClassLoader,
        hookController: WifiHookController
    ): Any? {
        return createProxy(classLoader, "android.net.wifi.IWifiManager", WifiManagerInvocationHandler(hookController))
    }

    /**
     * Get cached proxy
     */
    fun getCachedProxy(classLoader: ClassLoader, interfaceName: String): Any? {
        return proxyCache[classLoader]?.get(interfaceName)
    }
}

/**
 * InvocationHandler for LocationManagerService
 */
class LocationManagerInvocationHandler(
    private val hookController: LocationHookController
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodName = method.name

        return when (methodName) {
            "getLastLocation" -> {
                if (hookController.isMocking()) {
                    hookController.buildFakeLocation()
                } else {
                    null
                }
            }
            "requestLocationUpdates" -> {
                if (hookController.isMocking() && hookController.antiPullback) {
                    // Intercept and inject fake location callbacks
                    args?.getOrNull(1)?.let { listener ->
                        hookController.registerLocationListener(listener)
                    }
                }
                null
            }
            "getAllCellInfo" -> {
                if (hookController.isMocking()) {
                    hookController.buildFakeCellInfo()
                } else {
                    null
                }
            }
            "getNeighboringCellInfo" -> {
                if (hookController.isMocking()) {
                    hookController.buildFakeNeighboringCellInfo()
                } else {
                    null
                }
            }
            "addGpsStatusListener" -> {
                if (hookController.isMocking() && hookController.enableMockGnss) {
                    args?.getOrNull(0)?.let { listener ->
                        hookController.injectGpsStatus(listener)
                    }
                    true
                } else {
                    null
                }
            }
            "registerGnssStatusCallback" -> {
                if (hookController.isMocking() && hookController.enableMockGnss) {
                    args?.getOrNull(0)?.let { callback ->
                        hookController.injectGnssStatus(callback)
                    }
                    true
                } else {
                    null
                }
            }
            else -> {
                // For unhandled methods, return default values
                when (method.returnType) {
                    Void.TYPE -> null
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0.0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        }
    }
}

/**
 * InvocationHandler for WifiManager
 */
class WifiManagerInvocationHandler(
    private val hookController: WifiHookController
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodName = method.name

        return when (methodName) {
            "getConnectionInfo" -> {
                if (hookController.isMocking()) {
                    hookController.buildFakeWifiInfo()
                } else {
                    null
                }
            }
            "getScanResults" -> {
                if (hookController.isMocking()) {
                    hookController.buildFakeScanResults()
                } else {
                    null
                }
            }
            "startScan" -> {
                if (hookController.isMocking()) {
                    true
                } else {
                    false
                }
            }
            else -> {
                when (method.returnType) {
                    Void.TYPE -> null
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }
    }
}
