package com.kail.location.root

import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * System Location Hook - Simplified Fake Location architecture.
 *
 * Strategy (matching Fake Location C0041):
 * 1. PRIMARY: Replace location binder in ServiceManager with proxy.
 *    Intercepts getLastLocation, getCurrentLocation, registerLocationListener transactions.
 *    No need to load LocationManagerService class.
 * 2. SECONDARY: If LHooker is available AND LMS class can be found,
 *    also hook ART methods for deeper interception (optional enhancement).
 * 3. Active push: independent thread pushes mock locations to all registered listeners.
 * 4. GNSS status: active push via binder transact.
 */
object SystemLocationHook {
    private const val TAG = "KailSystemHook"

    private var hooked = false

    // ILocationManager transaction codes (resolved via reflection)
    private var txnGetLastLocation = -1
    private var txnGetCurrentLocation = -1
    private var txnRegisterLocationListener = -1
    private var txnUnregisterLocationListener = -1
    private var txnRegisterLocationPendingIntent = -1
    private var txnIsProviderEnabled = -1
    private var txnGetProviders = -1
    private var txnGetBestProvider = -1
    private var txnGetAllProviders = -1

    // GNSS txn code
    private var gnssSvStatusTxnCode = 2

    // Listener tracking (binder -> pkgName)
    private val listenerMap = mutableMapOf<IBinder, String>()
    private val gnssListenerMap = mutableMapOf<IBinder, String>()

    // Android 12+ uses GnssStatus parcelable
    private val gnssUsesParcelable by lazy { Build.VERSION.SDK_INT >= 31 }

    @JvmStatic
    fun init() {
        if (hooked) return
        Log.i(TAG, "SystemLocationHook.init() pid=${android.os.Process.myPid()}")

        try {
            resolveTransactionCodes()
            installLocationBinderProxy()
            startLocationPush()
            startGnssPush()
            startConfigPolling()

            // Optional: try ART method hook if LHooker available
            tryEnhanceWithLHooker()

            // WiFi and Cell hooks
            try { WifiServiceHook.init() } catch (e: Throwable) { Log.w(TAG, "WifiServiceHook init failed: ${e.message}") }
            try { TelephonyServiceHook.init() } catch (e: Throwable) { Log.w(TAG, "TelephonyServiceHook init failed: ${e.message}") }

            hooked = true
            Log.i(TAG, "SystemLocationHook initialized successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "SystemLocationHook.init() FAILED", e)
            throw e
        }
    }

    // ========================================================================
    // Transaction code resolution
    // ========================================================================

    private fun resolveTransactionCodes() {
        try {
            val stubClass = Class.forName("android.location.ILocationManager\$Stub")
            stubClass.declaredFields.forEach { field ->
                field.isAccessible = true
                when (field.name) {
                    "TRANSACTION_getLastLocation" -> txnGetLastLocation = field.getInt(null)
                    "TRANSACTION_getCurrentLocation" -> txnGetCurrentLocation = field.getInt(null)
                    "TRANSACTION_registerLocationListener" -> txnRegisterLocationListener = field.getInt(null)
                    "TRANSACTION_unregisterLocationListener" -> txnUnregisterLocationListener = field.getInt(null)
                    "TRANSACTION_registerLocationPendingIntent" -> txnRegisterLocationPendingIntent = field.getInt(null)
                    "TRANSACTION_isProviderEnabled" -> txnIsProviderEnabled = field.getInt(null)
                    // Android 14+ may use different names
                    "TRANSACTION_isLocationEnabled" -> txnIsProviderEnabled = field.getInt(null)
                    "TRANSACTION_getProviders" -> txnGetProviders = field.getInt(null)
                    "TRANSACTION_getBestProvider" -> txnGetBestProvider = field.getInt(null)
                    "TRANSACTION_getAllProviders" -> txnGetAllProviders = field.getInt(null)
                }
            }
            Log.i(TAG, "ILocationManager txn resolved: getLast=$txnGetLastLocation getCurrent=$txnGetCurrentLocation " +
                "regListener=$txnRegisterLocationListener isProvider=$txnIsProviderEnabled")
            // Debug: log all TRANSACTION_ fields for troubleshooting
            if (txnIsProviderEnabled == -1) {
                val txnFields = stubClass.declaredFields.filter { it.name.startsWith("TRANSACTION_") }
                    .map { "${it.name}=${it.getInt(null)}" }
                Log.w(TAG, "isProviderEnabled not found. Available txns: ${txnFields.joinToString(", ")}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve ILocationManager txn codes: ${e.message}")
        }

        try {
            val gnssStubClass = Class.forName("android.location.IGnssStatusListener\$Stub")
            gnssStubClass.declaredFields.forEach { field ->
                field.isAccessible = true
                if (field.name == "TRANSACTION_onSvStatusChanged") {
                    gnssSvStatusTxnCode = field.getInt(null)
                }
            }
            Log.i(TAG, "GNSS onSvStatusChanged txn=$gnssSvStatusTxnCode")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve GNSS txn code: ${e.message}")
        }
    }

    // ========================================================================
    // Binder Proxy installation (PRIMARY mechanism)
    // ========================================================================

    private fun installLocationBinderProxy() {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
        val addServiceMethod = serviceManagerClass.getDeclaredMethod("addService", String::class.java, IBinder::class.java).apply { isAccessible = true }

        val originalBinder = getServiceMethod.invoke(null, "location") as? IBinder
            ?: throw IllegalStateException("location binder not found")

        val proxy = LocationBinderProxy(originalBinder)

        // Replace in ServiceManager
        addServiceMethod.invoke(null, "location", proxy)

        // Also update sCache
        val sCacheField = serviceManagerClass.getDeclaredField("sCache").apply { isAccessible = true }
        val cache = sCacheField.get(null) as? MutableMap<String, IBinder>
        cache?.put("location", proxy)

        Log.i(TAG, "LocationBinderProxy installed on location service")
    }

    private class LocationBinderProxy(private val original: IBinder) : Binder() {
        companion object {
            private const val DESCRIPTOR = "android.location.ILocationManager"
        }

        init {
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
                txnRegisterLocationListener -> handleRegisterLocationListener(data, reply, flags)
                txnUnregisterLocationListener -> handleUnregisterLocationListener(data, reply, flags)
                txnRegisterLocationPendingIntent -> handleRegisterPendingIntent(data, reply, flags)
                txnIsProviderEnabled -> handleIsProviderEnabled(data, reply, flags)
                txnGetProviders -> handleGetProviders(data, reply, flags)
                txnGetBestProvider -> handleGetBestProvider(data, reply, flags)
                txnGetAllProviders -> handleGetAllProviders(data, reply, flags)
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                else -> original.transact(code, data, reply, flags)
            }
        }

        private fun isAllowMockPackage(): Boolean {
            return try {
                // Global mode: mock all packages - no UID resolution needed
                if (RootConfigManager.isGlobalMode()) return true

                val uid = Binder.getCallingUid()
                val pkg = getPackageNameByUid(uid)

                // If we can't resolve package name, default to allow in global-like behavior
                if (pkg == null) {
                    val targets = RootConfigManager.getTargetPackages()
                    if (targets.isEmpty()) return true // no targets = mock all
                    return false
                }
                RootConfigManager.shouldMockPackage(pkg)
            } catch (_: Throwable) { false }
        }

        private fun getPackageNameByUid(uid: Int): String? {
            // Method 1: ActivityManager.getNameForUid (most reliable in system_server)
            try {
                val amClass = Class.forName("android.app.ActivityManager")
                val getNameForUid = amClass.getMethod("getNameForUid", Int::class.java)
                val name = getNameForUid.invoke(null, uid) as? String
                if (name != null && name.contains(":")) {
                    return name.substringBefore(":")
                }
                if (name != null) return name
            } catch (_: Throwable) {}

            // Method 2: ActivityThread.getSystemContext().packageManager
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAT = atClass.getMethod("currentActivityThread")
                val at = currentAT.invoke(null)
                val getSystemContext = atClass.getMethod("getSystemContext")
                val ctx = getSystemContext.invoke(at)
                val pm = ctx?.javaClass?.getMethod("getPackageManager")?.invoke(ctx)
                val pkgs = pm?.javaClass?.getMethod("getPackagesForUid", Int::class.java)?.invoke(pm, uid) as? Array<String>
                pkgs?.firstOrNull()?.let { return it }
            } catch (_: Throwable) {}

            // Method 3: ActivityThread.currentApplication() (works in app processes)
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val app = atClass.getMethod("currentApplication").invoke(null)
                val pm = app?.javaClass?.getMethod("getPackageManager")?.invoke(app)
                val pkgs = pm?.javaClass?.getMethod("getPackagesForUid", Int::class.java)?.invoke(pm, uid) as? Array<String>
                pkgs?.firstOrNull()?.let { return it }
            } catch (_: Throwable) {}

            // Method 4: /proc/{pid}/cmdline
            try {
                val pid = Binder.getCallingPid()
                val cmdline = java.io.File("/proc/$pid/cmdline").readBytes().decodeToString().trim().replace("\u0000", "")
                if (cmdline.isNotBlank()) return cmdline
            } catch (_: Throwable) {}

            return null
        }

        private fun handleGetLastLocation(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (isAllowMockPackage()) {
                    reply?.writeNoException()
                    reply?.writeTypedObject(createMockLocation("gps"), 0)
                    Log.v(TAG, "getLastLocation MOCKED")
                    true
                } else {
                    original.transact(txnGetLastLocation, data, reply, flags)
                }
            } catch (_: Throwable) {
                original.transact(txnGetLastLocation, data, reply, flags)
            }
        }

        private fun handleGetCurrentLocation(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (isAllowMockPackage()) {
                    reply?.writeNoException()
                    reply?.writeTypedObject(createMockLocation("gps"), 0)
                    Log.v(TAG, "getCurrentLocation MOCKED")
                    true
                } else {
                    original.transact(txnGetCurrentLocation, data, reply, flags)
                }
            } catch (_: Throwable) {
                original.transact(txnGetCurrentLocation, data, reply, flags)
            }
        }

        private fun handleRegisterLocationListener(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                // For now, just pass through. We rely on active push instead of intercepting individual listeners.
                original.transact(txnRegisterLocationListener, data, reply, flags)
            } catch (_: Throwable) {
                original.transact(txnRegisterLocationListener, data, reply, flags)
            }
        }

        private fun handleUnregisterLocationListener(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return original.transact(txnUnregisterLocationListener, data, reply, flags)
        }

        private fun handleRegisterPendingIntent(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return original.transact(txnRegisterLocationPendingIntent, data, reply, flags)
        }

        private fun handleIsProviderEnabled(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (isAllowMockPackage()) {
                    reply?.writeNoException()
                    val provider = data.readString()
                    reply?.writeInt(if (provider in listOf("gps", "network", "passive", "fused")) 1 else 0)
                    true
                } else {
                    original.transact(txnIsProviderEnabled, data, reply, flags)
                }
            } catch (_: Throwable) {
                original.transact(txnIsProviderEnabled, data, reply, flags)
            }
        }

        private fun handleGetProviders(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (isAllowMockPackage()) {
                    reply?.writeNoException()
                    reply?.writeStringList(listOf("gps", "network", "passive"))
                    true
                } else {
                    original.transact(txnGetProviders, data, reply, flags)
                }
            } catch (_: Throwable) {
                original.transact(txnGetProviders, data, reply, flags)
            }
        }

        private fun handleGetBestProvider(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (isAllowMockPackage()) {
                    reply?.writeNoException()
                    reply?.writeString("gps")
                    true
                } else {
                    original.transact(txnGetBestProvider, data, reply, flags)
                }
            } catch (_: Throwable) {
                original.transact(txnGetBestProvider, data, reply, flags)
            }
        }

        private fun handleGetAllProviders(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (isAllowMockPackage()) {
                    reply?.writeNoException()
                    reply?.writeStringList(listOf("gps", "network", "passive", "fused"))
                    true
                } else {
                    original.transact(txnGetAllProviders, data, reply, flags)
                }
            } catch (_: Throwable) {
                original.transact(txnGetAllProviders, data, reply, flags)
            }
        }
    }

    // ========================================================================
    // LHooker enhancement (SECONDARY - 锦上添花)
    // Fake Location C0041: when LMS class is available, hook ART methods
    // for deeper interception (callLocationChangedLocked, requestLocationUpdatesLocked).
    // If LMS class not found, Binder Proxy alone is sufficient.
    // ========================================================================

    private var lmsClass: Class<*>? = null
    private var receiverClass: Class<*>? = null
    private var lhookerEnhanced = false

    private fun tryEnhanceWithLHooker() {
        try {
            if (!LHooker.ensureInit()) {
                Log.w(TAG, "LHooker not available, using Binder Proxy only")
                return
            }

            // CRITICAL: Use boot classloader to find com.android.server.location.* classes.
            // locationBinder.javaClass.classLoader returns our injected DexClassLoader,
            // which doesn't contain framework classes.
            val bootClassLoader = Object::class.java.classLoader
            Log.i(TAG, "Boot classloader: ${bootClassLoader?.javaClass?.name}")

            // Try to find LMS class with the boot classloader
            val possibleNames = listOf(
                "com.android.server.location.LocationManagerService",
                "com.android.server.LocationManagerService",
                "com.android.location.LocationManagerService"
            )

            for (name in possibleNames) {
                try {
                    lmsClass = Class.forName(name, true, bootClassLoader)
                    Log.i(TAG, "LMS class found: $name")
                    break
                } catch (_: Throwable) {}
            }

            if (lmsClass == null) {
                Log.i(TAG, "LMS class not found, using Binder Proxy only (this is normal)")
                return
            }

            // Try to find Receiver inner class
            val lms = lmsClass!!
            try {
                receiverClass = Class.forName("${lms.name}\$Receiver", true, lms.classLoader)
                Log.i(TAG, "Receiver class found: ${receiverClass!!.name}")
            } catch (_: Throwable) {
                Log.w(TAG, "Receiver class not found, active push may not work for all apps")
            }

            // Hook key methods
            var hookedCount = 0

            if (hookLmsMethod(lms, "requestLocationUpdatesLocked",
                    arrayOf(android.location.LocationRequest::class.java, receiverClass!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java),
                    "hook_requestLocationUpdatesLocked")) hookedCount++

            if (hookLmsMethod(lms, "requestLocationUpdatesLocked",
                    arrayOf(android.location.LocationRequest::class.java, receiverClass!!, Int::class.javaPrimitiveType!!, String::class.java),
                    "hook_requestLocationUpdatesLocked_Q")) hookedCount++

            if (hookLmsMethod(lms, "removeUpdatesLocked",
                    arrayOf(receiverClass!!),
                    "hook_removeUpdatesLocked")) hookedCount++

            if (receiverClass != null) {
                if (hookLmsMethod(receiverClass!!, "callLocationChangedLocked",
                        arrayOf(Location::class.java),
                        "hook_callLocationChangedLocked")) hookedCount++
            }

            lhookerEnhanced = hookedCount > 0
            Log.i(TAG, "LHooker enhancement: $hookedCount methods hooked")

        } catch (e: Throwable) {
            Log.w(TAG, "LHooker enhancement failed: ${e.message}, using Binder Proxy only")
        }
    }

    private fun hookLmsMethod(targetClass: Class<*>, methodName: String, paramTypes: Array<Class<*>?>, hookName: String): Boolean {
        return try {
            // Filter out null param types (receiverClass might be null)
            val filteredTypes = paramTypes.filterNotNull().toTypedArray()
            val targetMethod = targetClass.getDeclaredMethod(methodName, *filteredTypes)
            val hookMethod = SystemLocationHook::class.java.getDeclaredMethod(hookName, Any::class.java, *filteredTypes)
            val backupMethod = SystemLocationHook::class.java.getDeclaredMethod("${hookName}_bak", Any::class.java, *filteredTypes)
            val result = LHooker.hookMethod(targetMethod, hookMethod, backupMethod)
            Log.i(TAG, "LHooker hook $methodName result=$result")
            result
        } catch (e: Throwable) {
            Log.v(TAG, "LHooker hook $methodName skipped: ${e.message}")
            false
        }
    }

    // ---- Hook methods for LHooker ----

    @JvmStatic
    fun hook_requestLocationUpdatesLocked(obj: Any, locationRequest: Any, receiver: Any, i1: Int, i2: Int, pkg: String) {
        addReceiver(pkg, receiver)
        try { hook_requestLocationUpdatesLocked_bak(obj, locationRequest, receiver, i1, i2, pkg) } catch (_: Throwable) {}
    }
    @JvmStatic
    fun hook_requestLocationUpdatesLocked_bak(obj: Any, locationRequest: Any, receiver: Any, i1: Int, i2: Int, pkg: String) {}

    @JvmStatic
    fun hook_requestLocationUpdatesLocked_Q(obj: Any, locationRequest: Any, receiver: Any, i: Int, pkg: String) {
        addReceiver(pkg, receiver)
        try { hook_requestLocationUpdatesLocked_Q_bak(obj, locationRequest, receiver, i, pkg) } catch (_: Throwable) {}
    }
    @JvmStatic
    fun hook_requestLocationUpdatesLocked_Q_bak(obj: Any, locationRequest: Any, receiver: Any, i: Int, pkg: String) {}

    @JvmStatic
    fun hook_removeUpdatesLocked(obj: Any, receiver: Any) {
        removeReceiver(receiver)
        try { hook_removeUpdatesLocked_bak(obj, receiver) } catch (_: Throwable) {}
    }
    @JvmStatic
    fun hook_removeUpdatesLocked_bak(obj: Any, receiver: Any) {}

    @JvmStatic
    fun hook_callLocationChangedLocked(obj: Any, location: Location): Boolean {
        if (shouldHook() && location !is MockLocationMarker) {
            val pkg = receiverMap[obj]
            if (shouldHookPackage(pkg)) {
                val loc = createMockLocation("gps")
                return hook_callLocationChangedLocked_bak(obj, loc)
            }
        }
        return hook_callLocationChangedLocked_bak(obj, location)
    }
    @JvmStatic
    fun hook_callLocationChangedLocked_bak(obj: Any, location: Location): Boolean = false

    // ---- Listener tracking for active push ----

    private val receiverMap = mutableMapOf<Any, String>()

    private fun addReceiver(pkg: String, receiver: Any) {
        try { receiverMap[receiver] = pkg } catch (_: Throwable) {}
    }

    private fun removeReceiver(receiver: Any) {
        receiverMap.remove(receiver)
    }

    // ========================================================================
    // Active push
    // ========================================================================

    private fun startLocationPush() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                if (!RootConfigManager.isEnabled()) return@scheduleWithFixedDelay
                if (lhookerEnhanced && receiverClass != null) {
                    pushToReceivers()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "push error", e)
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Location push thread started (lhookerEnhanced=$lhookerEnhanced)")
    }

    private fun pushToReceivers() {
        val receivers = synchronized(receiverMap) { ArrayList(receiverMap.keys) }
        val location = createMockLocation("gps")
        for (receiver in receivers) {
            val pkg = receiverMap[receiver]
            if (!shouldHookPackage(pkg)) continue
            try {
                val method = receiverClass?.getDeclaredMethod("callLocationChangedLocked", Location::class.java)
                method?.isAccessible = true
                method?.invoke(receiver, location)
            } catch (e: Throwable) {
                if (e.isDeadObject()) removeReceiver(receiver)
            }
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private fun shouldHook(): Boolean = RootConfigManager.isEnabled()

    private fun shouldHookPackage(pkg: String?): Boolean {
        if (!RootConfigManager.isEnabled()) return false
        return RootConfigManager.shouldMockPackage(pkg)
    }

    private class MockLocationMarker(provider: String) : Location(provider)

    // ========================================================================
    // GNSS Status Push
    // ========================================================================

    private fun startGnssPush() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                if (!RootConfigManager.isEnabled() || !RootConfigManager.isGnssMockEnabled()) return@scheduleWithFixedDelay
                pushGnssToAll()
            } catch (e: Throwable) {
                Log.e(TAG, "gnss push error", e)
            }
        }, 1000, 2000, TimeUnit.MILLISECONDS)
        Log.i(TAG, "GNSS push started (2000ms)")
    }

    private fun pushGnssToAll() {
        val data = createMockGnssStatusParcel()
        val keys = synchronized(gnssListenerMap) { ArrayList(gnssListenerMap.keys) }
        for (listener in keys) {
            val pkg = gnssListenerMap[listener]
            if (!RootConfigManager.shouldMockPackage(pkg)) continue
            try {
                listener.transact(gnssSvStatusTxnCode, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: Throwable) {
                if (e.isDeadObject()) gnssListenerMap.remove(listener)
            }
        }
        data.recycle()
    }

    // ========================================================================
    // Mock data creation
    // ========================================================================

    private fun createMockLocation(provider: String = "gps"): Location {
        val loc = Location(provider)
        loc.latitude = RootConfigManager.getLatitude()
        loc.longitude = RootConfigManager.getLongitude()
        loc.altitude = RootConfigManager.getAltitude()
        loc.speed = RootConfigManager.getSpeed()
        loc.bearing = RootConfigManager.getBearing()
        loc.accuracy = RootConfigManager.getAccuracy()
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        try {
            Location::class.java.getDeclaredField("mElapsedRealtimeUncertaintyNanos").apply {
                isAccessible = true
                setLong(loc, 100_000_000L)
            }
        } catch (_: Throwable) {}
        try {
            Location::class.java.getDeclaredField("mVerticalAccuracyMeters").apply {
                isAccessible = true
                setFloat(loc, loc.accuracy)
            }
        } catch (_: Throwable) {}
        try {
            Location::class.java.getDeclaredField("mSpeedAccuracyMetersPerSecond").apply {
                isAccessible = true
                setFloat(loc, 0.5f)
            }
        } catch (_: Throwable) {}
        try {
            Location::class.java.getDeclaredField("mBearingAccuracyDegrees").apply {
                isAccessible = true
                setFloat(loc, 5.0f)
            }
        } catch (_: Throwable) {}

        val extras = Bundle()
        extras.putInt("satellites", 20)
        loc.extras = extras

        try {
            Location::class.java.getDeclaredField("mIsFromMockProvider").apply {
                isAccessible = true
                setBoolean(loc, false)
            }
        } catch (_: Throwable) {}
        try {
            Location::class.java.getDeclaredField("mFieldsMask").apply {
                isAccessible = true
                setInt(loc, 0xFFFF)
            }
        } catch (_: Throwable) {}
        try {
            Location::class.java.getDeclaredField("mIsMock").apply {
                isAccessible = true
                setBoolean(loc, false)
            }
        } catch (_: Throwable) {}

        return loc
    }

    private fun createMockGnssStatusParcel(): Parcel {
        val data = Parcel.obtain()
        data.writeInterfaceToken("android.location.IGnssStatusListener")
        try {
            if (gnssUsesParcelable) {
                val gnssStatus = createMockGnssStatus()
                if (gnssStatus != null) {
                    data.writeInt(1)
                    val writeToParcel = gnssStatus.javaClass.getMethod("writeToParcel", Parcel::class.java, Int::class.java)
                    writeToParcel.invoke(gnssStatus, data, 0)
                } else {
                    data.writeInt(0)
                }
            } else {
                writeMockGnssRawArrays(data)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "createMockGnssStatusParcel failed: ${e.message}")
            data.setDataPosition(0)
            data.writeInterfaceToken("android.location.IGnssStatusListener")
            if (gnssUsesParcelable) data.writeInt(0) else writeMockGnssRawArrays(data)
        }
        return data
    }

    private fun createMockGnssStatus(): Any? {
        return try {
            val gnssClass = Class.forName("android.location.GnssStatus")
            val arr = buildGnssStatusArrays()
            val svCount = arr[0] as Int
            val svidWithFlags = arr[1] as IntArray
            val cn0s = arr[2] as FloatArray
            val elevations = arr[3] as FloatArray
            val azimuths = arr[4] as FloatArray
            val carrierFreqs = arr[5] as FloatArray
            val basebandCn0s = FloatArray(svCount) { i -> cn0s[i] - 2f }
            val ctor7 = try {
                gnssClass.getDeclaredConstructor(Int::class.java, IntArray::class.java, FloatArray::class.java, FloatArray::class.java, FloatArray::class.java, FloatArray::class.java, FloatArray::class.java).apply { isAccessible = true }
            } catch (_: Throwable) { null }
            if (ctor7 != null) {
                ctor7.newInstance(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs, basebandCn0s)
            } else {
                val ctor6 = gnssClass.getDeclaredConstructor(Int::class.java, IntArray::class.java, FloatArray::class.java, FloatArray::class.java, FloatArray::class.java, FloatArray::class.java).apply { isAccessible = true }
                ctor6.newInstance(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "createMockGnssStatus failed: ${e.message}")
            null
        }
    }

    private fun buildGnssStatusArrays(): Array<Any> {
        val svCount = (10 until F101).random()
        return arrayOf(svCount, getRandomLength(F95, svCount), getRandomLength(F96, svCount), getRandomLength(F97, svCount), getRandomLength(F98, svCount), getRandomLength(F99, svCount))
    }

    private fun writeMockGnssRawArrays(data: Parcel) {
        val arr = buildGnssStatusArrays()
        val svCount = arr[0] as Int
        val cn0s = arr[2] as FloatArray
        data.writeInt(svCount)
        data.writeIntArray(arr[1] as IntArray)
        data.writeFloatArray(cn0s)
        data.writeFloatArray(arr[3] as FloatArray)
        data.writeFloatArray(arr[4] as FloatArray)
        data.writeFloatArray(arr[5] as FloatArray)
        data.writeFloatArray(FloatArray(svCount) { i -> cn0s[i] - 2f })
    }

    private fun getRandomLength(arr: IntArray, len: Int): IntArray {
        val offset = (0 until arr.size).random()
        return IntArray(len) { i -> arr[(offset + i) % arr.size] }
    }
    private fun getRandomLength(arr: FloatArray, len: Int): FloatArray {
        val offset = (0 until arr.size).random()
        return FloatArray(len) { i -> arr[(offset + i) % arr.size] }
    }

    private val F95 = intArrayOf(41242,49439,74015,94495,98590,102683,127263,131354,41242,102683,58139,8987,74527,54042,13087,17178,5400,13592,17688,21790,29976,34077,38168,42269,54559,66842,79133,795675,799771,516632,524824,561688,795679,799771,5658,46618,50714,79386,87579,112154,136735,50718,87579,136735)
    private val F96 = floatArrayOf(27.4f,35.7f,39.2f,30.4f,25.2f,39.1f,40.5f,26.9f,26.2f,40.1f,46.1f,32.1f,38.9f,21.9f,27.8f,22.3f,42.6f,36.4f,33.3f,33.5f,34.9f,40.1f,28.5f,36.0f,34.5f,26.2f,26.4f,35.0f,42.9f,32.4f,38.6f,36.9f,31.8f,30.4f,41.4f,19.0f,33.8f,18.6f,34.8f,18.7f,35.1f,28.7f,34.0f,36.1f)
    private val F97 = floatArrayOf(74.0f,28.0f,17.0f,61.0f,26.0f,31.0f,20.0f,46.0f,74.0f,31.0f,48.0f,17.0f,12.0f,52.0f,49.0f,31.0f,35.0f,54.0f,22.0f,27.0f,14.0f,48.0f,60.0f,8.0f,58.0f,66.0f,42.0f,43.0f,2.0f,0.0f,0.0f,0.0f,43.0f,2.0f,0.0f,16.0f,36.0f,0.0f,43.0f,25.0f,20.0f,36.0f,43.0f,20.0f)
    private val F98 = floatArrayOf(348.0f,94.0f,181.0f,116.0f,43.0f,130.0f,219.0f,306.0f,348.0f,130.0f,163.0f,90.0f,226.0f,57.0f,36.0f,327.0f,123.0f,172.0f,111.0f,246.0f,165.0f,185.0f,292.0f,177.0f,203.0f,343.0f,91.0f,111.0f,154.0f,0.0f,0.0f,0.0f,111.0f,154.0f,0.0f,310.0f,255.0f,0.0f,116.0f,54.0f,198.0f,255.0f,116.0f,198.0f)
    private val F99 = floatArrayOf(1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.17645e9f,1.17645e9f,1.6003124e9f,1.600875e9f,1.6048125e9f,1.605375e9f,1.602e9f,1.602e9f,1.602e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.561098e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.17645e9f,1.17645e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f,1.57542e9f)
    private val F101 = 36

    // ========================================================================
    // Utility
    // ========================================================================

    private fun Throwable.isDeadObject(): Boolean {
        return this is android.os.DeadObjectException || this.cause is android.os.DeadObjectException || this.toString().contains("DeadObjectException")
    }

    private fun startConfigPolling() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                RootConfigManager.reload()
            } catch (_: Throwable) {}
        }, 1, 1, TimeUnit.SECONDS)
    }
}
