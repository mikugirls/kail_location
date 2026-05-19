package com.kail.location.root

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * KailLocation Root Hook Entry Point
 * Called from native code when libkail_root_hook.so is injected into a process.
 * Replaces Xposed/LSPosed dependency with pure reflection + binder proxy hooks.
 */
object RootHookEntry {
    private const val TAG = "KailRootHook"
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var initialized = false

    @JvmStatic
    fun init(context: Context?) {
        if (initialized) return
        initialized = true
        Log.i(TAG, "RootHookEntry.init() called in pid=${android.os.Process.myPid()}")

        try {
            val processName = getProcessName()
            Log.i(TAG, "Process: $processName")

            when {
                processName == "android" || processName == "system" || processName == "system_server" -> {
                    hookSystemServer(context)
                }
                processName == "com.android.phone" -> {
                    hookPhoneProcess()
                }
                processName == "com.android.location.fused" -> {
                    hookFusedLocationProcess()
                }
                else -> {
                    hookApplicationProcess(context)
                }
            }

            // Start config polling
            startConfigPolling()

            // Hide injection traces after hooks are installed
            TraceHider.hideTraces(context)
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
        }
    }

    private fun getProcessName(): String {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getDeclaredMethod("currentProcessName")
            method.invoke(null) as String
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * Hook system_server process:
     * Use Binder Proxy interception (SystemLocationHook) instead of ART method hooking.
     * LHooker is kept as a fallback but disabled by default due to ArtMethod offset
     * instability on Android 14+.
     */
    private fun hookSystemServer(context: Context?) {
        Log.i(TAG, "Hooking system_server via Binder Proxy...")
        try {
            SystemLocationHook.init()
            Log.i(TAG, "SystemLocationHook initialized in system_server")
        } catch (e: Throwable) {
            Log.e(TAG, "hookSystemServer SystemLocationHook failed", e)
        }

        // Native sensor hook (step frequency) also needs to run in system_server
        // because sensorservice lives inside system_server.
        try {
            NativeSensorHook.init()
            Log.i(TAG, "NativeSensorHook initialized in system_server")
        } catch (e: Throwable) {
            Log.e(TAG, "hookSystemServer NativeSensorHook failed", e)
        }
    }

    private fun hookPhoneProcess() {
        Log.i(TAG, "Hooking phone process...")
        // TODO: hook telephony service
    }

    private fun hookFusedLocationProcess() {
        Log.i(TAG, "Hooking fused location process...")
        try {
            FusedLocationHook.init(null)
            Log.i(TAG, "FusedLocationHook initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "hookFusedLocationProcess FusedLocationHook failed", e)
        }
    }

    /**
     * Hook regular application process:
     * Replace LocationManager.mService with proxy.
     * Also load native sensor hook for step frequency simulation.
     */
    private fun hookApplicationProcess(context: Context?) {
        Log.i(TAG, "Hooking application process...")

        // Load native sensor hook (step frequency simulation)
        NativeSensorHook.init()

        try {
            val ctx = context ?: return
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try multiple field names for the ILocationManager reference
            val serviceField = try {
                LocationManager::class.java.getDeclaredField("mService")
            } catch (_: NoSuchFieldException) {
                try {
                    LocationManager::class.java.getDeclaredField("sService")
                } catch (_: NoSuchFieldException) {
                    // Android 14+ may use different field names
                    try {
                        LocationManager::class.java.getDeclaredField("mLocationManager")
                    } catch (_: NoSuchFieldException) {
                        try {
                            // Try to find any field of type ILocationManager
                            LocationManager::class.java.declaredFields.firstOrNull { f ->
                                f.type.name.contains("ILocationManager")
                            }
                        } catch (_: Throwable) { null }
                    }
                }
            }

            if (serviceField == null) {
                Log.e(TAG, "Cannot find LocationManager service field, trying direct ServiceManager...")
                // Fallback: directly hook ServiceManager.getService("location")
                hookViaServiceManager(ctx)
                return
            }
            serviceField.isAccessible = true
            val originalService = serviceField.get(lm)

            if (originalService != null) {
                // The service object is an ILocationManager.Stub proxy.
                // Find ILocationManager interface via Stub class hierarchy.
                val iLocMgrClass = tryFindILocationManagerClass(originalService)
                if (iLocMgrClass != null) {
                    val proxy = Proxy.newProxyInstance(
                        originalService.javaClass.classLoader,
                        arrayOf(iLocMgrClass),
                        ILocationManagerHandler(originalService)
                    )
                    serviceField.set(lm, proxy)
                    Log.i(TAG, "Replaced LocationManager.mService in app process (interface: ${iLocMgrClass.name})")
                } else {
                    Log.e(TAG, "Could not find ILocationManager interface class")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "hookApplicationProcess failed", e)
        }
    }

    private fun tryFindILocationManagerClass(service: Any): Class<*>? {
        // Try direct interface implementation
        service.javaClass.interfaces?.forEach { iface ->
            if (iface.name == "android.location.ILocationManager") return iface
        }
        // Try loading by name from service's classloader
        return try {
            Class.forName("android.location.ILocationManager", false, service.javaClass.classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private class ILocationManagerHandler(private val original: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val argsArray = args ?: emptyArray()

            if (!RootConfigManager.isEnabled()) {
                return method.invoke(original, *argsArray)
            }

            when (method.name) {
                "getLastLocation" -> {
                    val result = method.invoke(original, *argsArray)
                    return if (result is Location) injectLocation(result) else result
                }
                "requestLocationUpdates", "registerLocationListener", "requestSingleUpdate" -> {
                    val newArgs = argsArray.map { arg ->
                        if (arg is IInterface) {
                            wrapLocationListener(arg)
                        } else {
                            arg
                        }
                    }.toTypedArray()
                    return method.invoke(original, *newArgs)
                }
                "removeUpdates", "unregisterLocationListener" -> {
                    return method.invoke(original, *argsArray)
                }
                "addGpsStatusListener", "registerGnssStatusCallback",
                "addGnssMeasurementsListener", "registerGnssMeasurementsCallback",
                "addGnssNavigationMessageListener", "registerGnssNavigationMessageCallback" -> {
                    val newArgs = argsArray.map { arg ->
                        if (arg is IInterface) {
                            wrapGnssListener(arg)
                        } else {
                            arg
                        }
                    }.toTypedArray()
                    return method.invoke(original, *newArgs)
                }
                "getCurrentLocation" -> {
                    val newArgs = argsArray.toMutableList()
                    if (newArgs.size > 2 && newArgs[2] != null) {
                        newArgs[2] = wrapLocationCallback(newArgs[2])
                        return method.invoke(original, *newArgs.toTypedArray())
                    }
                    return method.invoke(original, *argsArray)
                }
                "sendExtraCommand" -> {
                    val provider = argsArray.getOrNull(0) as? String
                    if (provider == "kail") {
                        return handleKailCommand(argsArray)
                    }
                    if (RootConfigManager.isEnabled() && provider == "gps") {
                        return false
                    }
                    return method.invoke(original, *argsArray)
                }
                "isProviderEnabled", "isProviderEnabledForUser" -> {
                    val provider = argsArray.getOrNull(0) as? String
                    if (provider == "kail") {
                        return RootConfigManager.isEnabled()
                    }
                    if (RootConfigManager.isEnabled() && provider == "network") {
                        return false
                    }
                    return method.invoke(original, *argsArray)
                }
            }

            return method.invoke(original, *argsArray)
        }

        private fun wrapLocationListener(listener: IInterface): Any {
            return Proxy.newProxyInstance(
                listener.javaClass.classLoader,
                listener.javaClass.interfaces,
                LocationListenerHandler(listener)
            )
        }

        private fun wrapGnssListener(listener: IInterface): Any {
            return Proxy.newProxyInstance(
                listener.javaClass.classLoader,
                listener.javaClass.interfaces,
                GnssListenerHandler(listener)
            )
        }

        private fun wrapLocationCallback(callback: Any): Any {
            return Proxy.newProxyInstance(
                callback.javaClass.classLoader,
                callback.javaClass.interfaces,
                LocationCallbackHandler(callback)
            )
        }

        private fun handleKailCommand(args: Array<out Any>): Boolean {
            val command = args.getOrNull(1) as? String ?: return false
            val bundle = args.getOrNull(2) as? Bundle ?: return false

            return when (command) {
                "exchange_key" -> {
                    bundle.putString("key", "root_${System.currentTimeMillis()}")
                    true
                }
                else -> {
                    val cmdId = bundle.getString("command_id") ?: return false
                    RootCommandHandler.handle(cmdId, bundle)
                }
            }
        }
    }

    private class LocationListenerHandler(private val original: IInterface) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val argsArray = args ?: emptyArray()
            if (method.name == "onLocationChanged" && RootConfigManager.isEnabled()) {
                val newArgs = argsArray.map { arg ->
                    when (arg) {
                        is Location -> injectLocation(arg)
                        is List<*> -> arg.map { if (it is Location) injectLocation(it) else it }
                        else -> arg
                    }
                }.toTypedArray()
                return method.invoke(original, *newArgs)
            }
            return method.invoke(original, *argsArray)
        }
    }

    private class LocationCallbackHandler(private val original: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val argsArray = args ?: emptyArray()
            if (method.name == "onLocation" && RootConfigManager.isEnabled()) {
                val newArgs = argsArray.map { arg ->
                    if (arg is Location) injectLocation(arg) else arg
                }.toTypedArray()
                return method.invoke(original, *newArgs)
            }
            return method.invoke(original, *argsArray)
        }
    }

    private class GnssListenerHandler(private val original: IInterface) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val argsArray = args ?: emptyArray()
            return method.invoke(original, *argsArray)
        }
    }

    private fun hookViaServiceManager(context: Context) {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
            val originalBinder = getServiceMethod.invoke(null, "location") as? IBinder ?: return

            val iLocMgrClass = tryFindILocationManagerClass(originalBinder) ?: return
            val proxy = Proxy.newProxyInstance(
                originalBinder.javaClass.classLoader,
                arrayOf(iLocMgrClass),
                ILocationManagerHandler(originalBinder)
            )

            // Replace in ServiceManager cache
            val sCacheField = serviceManagerClass.getDeclaredField("sCache").apply { isAccessible = true }
            val cache = sCacheField.get(null) as? MutableMap<String, IBinder>
            cache?.put("location", proxy as IBinder)

            // Also replace in LocationManager instances
            replaceLocationManagerService(context, proxy)

            Log.i(TAG, "Hooked LocationManager via ServiceManager fallback")
        } catch (e: Throwable) {
            Log.e(TAG, "hookViaServiceManager failed", e)
        }
    }

    private fun replaceLocationManagerService(context: Context, proxy: Any) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (field in LocationManager::class.java.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(lm)
                    if (value != null && (value.javaClass.name.contains("ILocationManager") || value is IBinder)) {
                        field.set(lm, proxy)
                        Log.i(TAG, "Replaced LocationManager field: ${field.name}")
                    }
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e(TAG, "replaceLocationManagerService failed", e)
        }
    }

    private fun startConfigPolling() {
        executor.scheduleWithFixedDelay({
            RootConfigManager.reload()
        }, 1, 1, TimeUnit.SECONDS)
    }
}

/**
 * Inject location data into a Location object.
 */
fun injectLocation(location: Location): Location {
    val lat = RootConfigManager.getLatitude()
    val lon = RootConfigManager.getLongitude()
    if (lat == 0.0 && lon == 0.0) return location

    val loc = Location(location)
    loc.latitude = lat
    loc.longitude = lon
    loc.altitude = RootConfigManager.getAltitude()
    loc.speed = RootConfigManager.getSpeed()
    loc.bearing = RootConfigManager.getBearing()
    loc.accuracy = RootConfigManager.getAccuracy()
    loc.time = System.currentTimeMillis()
    return loc
}
