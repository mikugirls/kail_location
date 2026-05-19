package com.kail.location.root

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fused Location Provider Hook.
 * Targets com.android.location.fused process with a two-pronged strategy:
 * 1. Poison the input: hook the fused process's own LocationManager client
 *    so that GPS/network fixes it reads are already mocked.
 * 2. Intercept the output: if this process exposes a fused provider binder,
 *    wrap it so outgoing fused locations are also mocked.
 */
object FusedLocationHook {
    private const val TAG = "KailFusedHook"
    private const val LOCATION_DESCRIPTOR = "android.location.ILocationManager"

    @JvmStatic
    fun init() {
        Log.i(TAG, "FusedLocationHook.init() pid=${android.os.Process.myPid()}")
        try {
            hookLocationManagerClient(null)
            hookFusedProviderService()
            startConfigPolling()
            Log.i(TAG, "FusedLocationHook fully initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
        }
    }

    @JvmStatic
    fun init(context: Context?) {
        Log.i(TAG, "FusedLocationHook.init(context) pid=${android.os.Process.myPid()}")
        try {
            hookLocationManagerClient(context)
            hookFusedProviderService()
            startConfigPolling()
            Log.i(TAG, "FusedLocationHook fully initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
        }
    }

    /**
     * Strategy 1: Poison the input.
     * The fused provider reads raw GPS/network locations via LocationManager.
     * If we mock those inputs, the fused output is naturally mocked too.
     */
    private fun hookLocationManagerClient(context: Context?) {
        try {
            val ctx = context ?: run {
                // Attempt to get system context via ActivityThread if no context provided
                val atClass = Class.forName("android.app.ActivityThread")
                val currentApp = atClass.getMethod("currentApplication").invoke(null)
                currentApp as? Context
            } ?: run {
                Log.w(TAG, "No context available for LocationManager hook")
                return
            }

            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val serviceField = findLocationManagerServiceField() ?: run {
                Log.w(TAG, "Cannot find LocationManager service field")
                return
            }
            serviceField.isAccessible = true
            val originalService = serviceField.get(lm)

            if (originalService == null) {
                Log.w(TAG, "LocationManager service is null")
                return
            }

            val iLocMgrClass = findILocationManagerClass(originalService) ?: run {
                Log.w(TAG, "Cannot find ILocationManager interface")
                return
            }

            val proxy = Proxy.newProxyInstance(
                originalService.javaClass.classLoader,
                arrayOf(iLocMgrClass),
                FusedLocationManagerHandler(originalService)
            )
            serviceField.set(lm, proxy)
            Log.i(TAG, "LocationManager client hooked in fused process")
        } catch (e: Throwable) {
            Log.e(TAG, "hookLocationManagerClient failed", e)
        }
    }

    /**
     * Strategy 2: Intercept the output.
     * Some OEMs expose a fused provider via ServiceManager.
     * We wrap any binder that looks like a fused location provider.
     */
    private fun hookFusedProviderService() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
            val addServiceMethod = serviceManagerClass.getDeclaredMethod("addService", String::class.java, IBinder::class.java).apply { isAccessible = true }

            // Check if this process exposes a "fused" service
            val fusedBinder = getServiceMethod.invoke(null, "fused") as? IBinder
            if (fusedBinder != null) {
                Log.i(TAG, "Found fused service binder, wrapping it")
                val wrapped = FusedProviderProxy(fusedBinder)
                addServiceMethod.invoke(null, "fused", wrapped)
                Log.i(TAG, "Fused provider service wrapped")
            }

            // Also check for provider-specific services some OEMs use
            val providerBinder = getServiceMethod.invoke(null, "location_provider") as? IBinder
            if (providerBinder != null) {
                Log.i(TAG, "Found location_provider service binder, wrapping it")
                val wrapped = FusedProviderProxy(providerBinder)
                addServiceMethod.invoke(null, "location_provider", wrapped)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "hookFusedProviderService failed (non-critical): ${e.message}")
        }
    }

    private fun findLocationManagerServiceField(): java.lang.reflect.Field? {
        return try {
            LocationManager::class.java.getDeclaredField("mService")
        } catch (_: NoSuchFieldException) {
            try {
                LocationManager::class.java.getDeclaredField("sService")
            } catch (_: NoSuchFieldException) {
                LocationManager::class.java.declaredFields.firstOrNull { f ->
                    f.type.name.contains("ILocationManager")
                }
            }
        }
    }

    private fun findILocationManagerClass(service: Any): Class<*>? {
        service.javaClass.interfaces?.forEach { iface ->
            if (iface.name == "android.location.ILocationManager") return iface
        }
        return try {
            Class.forName("android.location.ILocationManager", false, service.javaClass.classLoader)
        } catch (_: Throwable) { null }
    }

    private class FusedLocationManagerHandler(private val original: Any) : InvocationHandler {
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
                "requestLocationUpdates", "registerLocationListener" -> {
                    val newArgs = argsArray.map { arg ->
                        if (arg is IInterface) wrapLocationListener(arg) else arg
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
            }
            return method.invoke(original, *argsArray)
        }

        private fun wrapLocationListener(listener: IInterface): Any {
            return Proxy.newProxyInstance(
                listener.javaClass.classLoader,
                listener.javaClass.interfaces,
                FusedLocationListenerHandler(listener)
            )
        }

        private fun wrapLocationCallback(callback: Any): Any {
            return Proxy.newProxyInstance(
                callback.javaClass.classLoader,
                callback.javaClass.interfaces,
                FusedLocationCallbackHandler(callback)
            )
        }
    }

    private class FusedLocationListenerHandler(private val original: IInterface) : InvocationHandler {
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

    private class FusedLocationCallbackHandler(private val original: Any) : InvocationHandler {
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

    /**
     * Proxy for the fused provider service binder (if exposed by this process).
     * Intercepts outgoing location results and replaces them with mock locations.
     */
    private class FusedProviderProxy(private val original: IBinder) : Binder() {
        companion object {
            private const val DESCRIPTOR = "android.location.ILocationProvider"
            private var txnGetLastLocation = -1
            private var txnGetCurrentLocation = -1
            private var initialized = false

            @Synchronized
            fun initTxnCodes() {
                if (initialized) return
                try {
                    val stubClass = Class.forName("android.location.ILocationProvider\$Stub")
                    for (field in stubClass.declaredFields) {
                        when (field.name) {
                            "TRANSACTION_getLastLocation" -> {
                                field.isAccessible = true
                                txnGetLastLocation = field.getInt(null)
                            }
                            "TRANSACTION_getCurrentLocation" -> {
                                field.isAccessible = true
                                txnGetCurrentLocation = field.getInt(null)
                            }
                        }
                    }
                } catch (_: Throwable) {}
                initialized = true
            }
        }

        init {
            initTxnCodes()
            attachInterface(null, DESCRIPTOR)
        }

        override fun getInterfaceDescriptor(): String = DESCRIPTOR

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!RootConfigManager.isEnabled()) {
                return original.transact(code, data, reply, flags)
            }

            return when (code) {
                txnGetLastLocation -> handleGetLastLocation(data, reply, flags)
                txnGetCurrentLocation -> handleGetCurrentLocation(data, reply, flags)
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                else -> original.transact(code, data, reply, flags)
            }
        }

        private fun handleGetLastLocation(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeTypedObject(createMockLocation("fused"), 0)
                true
            } catch (_: Throwable) {
                original.transact(txnGetLastLocation, data, reply, flags)
            }
        }

        private fun handleGetCurrentLocation(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeTypedObject(createMockLocation("fused"), 0)
                true
            } catch (_: Throwable) {
                original.transact(txnGetCurrentLocation, data, reply, flags)
            }
        }
    }

    private fun createMockLocation(provider: String = "fused"): Location {
        val loc = Location(provider)
        loc.latitude = RootConfigManager.getLatitude()
        loc.longitude = RootConfigManager.getLongitude()
        loc.altitude = RootConfigManager.getAltitude()
        loc.speed = RootConfigManager.getSpeed()
        loc.bearing = RootConfigManager.getBearing()
        loc.accuracy = RootConfigManager.getAccuracy()
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

        try {
            val field = Location::class.java.getDeclaredField("mIsFromMockProvider")
            field.isAccessible = true
            field.setBoolean(loc, false)
        } catch (_: Throwable) {}

        try {
            val mockField = Location::class.java.getDeclaredField("mIsMock")
            mockField.isAccessible = true
            mockField.setBoolean(loc, false)
        } catch (_: Throwable) {}

        val extras = android.os.Bundle()
        extras.putInt("satellites", 20)
        loc.extras = extras
        return loc
    }

    private fun injectLocation(location: Location): Location {
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

    private fun startConfigPolling() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try { RootConfigManager.reload() } catch (_: Throwable) {}
        }, 1, 1, TimeUnit.SECONDS)
    }
}
