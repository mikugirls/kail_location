package com.kail.location.root

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.telephony.CellInfo
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Telephony Service Hook - Mocks cell location and cell info.
 *
 * Two-pronged strategy (matching FakeLocation C0077):
 * 1. Intercept synchronous queries: getCellLocation, getAllCellInfo.
 * 2. Intercept asynchronous callbacks: replace PhoneStateListener registered via
 *    TelephonyRegistry.listen() with MockPhoneStateListenerProxy, so that
 *    onCellLocationChanged / onCellInfoChanged return mock cell data.
 * 3. Fallback polling: periodically scan TelephonyRegistry internal listener list
 *    and replace target listeners (for cases where listen interception misses).
 * 4. Active dispatch: periodically push mock cell data to all replaced listeners
 *    (like C0077.callbackMockCellLocation).
 */
object TelephonyServiceHook {
    private const val TAG = "KailCellHook"

    private var telephonyProxyInstalled = false

    // ITelephony transaction codes
    private var transactionGetCellLocation = 5
    private var transactionGetAllCellInfo = 85

    // IPhoneStateListener transaction codes
    private var txnOnCellLocationChanged = -1
    private var txnOnCellInfoChanged = -1
    private var listenerTxnResolved = false

    // Active listener proxies (pkgName -> proxy weak ref)
    private val listenerProxies = mutableMapOf<String, WeakReference<MockPhoneStateListenerProxy>>()

    private val serviceManagerClass by lazy { Class.forName("android.os.ServiceManager") }
    private val getServiceMethod by lazy {
        serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
    }
    private val addServiceMethod by lazy {
        serviceManagerClass.getDeclaredMethod("addService", String::class.java, IBinder::class.java).apply { isAccessible = true }
    }

    @JvmStatic
    fun init() {
        Log.i(TAG, "TelephonyServiceHook.init() pid=${android.os.Process.myPid()}")
        try {
            resolveTransactionCodes()
            resolveListenerTransactionCodes()
            checkAndHook()
            startConfigPolling()
            startListenerReplacementPolling()
            startActiveDispatchPolling()
        } catch (e: Throwable) {
            Log.e(TAG, "init FAILED", e)
        }
    }

    private fun resolveTransactionCodes() {
        try {
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            stubClass.declaredFields.forEach { field ->
                field.isAccessible = true
                when (field.name) {
                    "TRANSACTION_getCellLocation" -> {
                        transactionGetCellLocation = field.getInt(null)
                        Log.i(TAG, "Resolved TRANSACTION_getCellLocation = $transactionGetCellLocation")
                    }
                    "TRANSACTION_getAllCellInfo" -> {
                        transactionGetAllCellInfo = field.getInt(null)
                        Log.i(TAG, "Resolved TRANSACTION_getAllCellInfo = $transactionGetAllCellInfo")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve ITelephony codes, using defaults: getCellLocation=$transactionGetCellLocation getAllCellInfo=$transactionGetAllCellInfo", e)
        }
    }

    private fun resolveListenerTransactionCodes() {
        if (listenerTxnResolved) return
        try {
            val stubClass = Class.forName("com.android.internal.telephony.IPhoneStateListener\$Stub")
            stubClass.declaredFields.forEach { field ->
                field.isAccessible = true
                when (field.name) {
                    "TRANSACTION_onCellLocationChanged" -> {
                        txnOnCellLocationChanged = field.getInt(null)
                        Log.i(TAG, "Resolved IPhoneStateListener TRANSACTION_onCellLocationChanged = $txnOnCellLocationChanged")
                    }
                    "TRANSACTION_onCellInfoChanged" -> {
                        txnOnCellInfoChanged = field.getInt(null)
                        Log.i(TAG, "Resolved IPhoneStateListener TRANSACTION_onCellInfoChanged = $txnOnCellInfoChanged")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve IPhoneStateListener codes: ${e.message}")
        }
        listenerTxnResolved = true
    }

    private fun checkAndHook() {
        try {
            val cellMock = RootConfigManager.isCellMockEnabled()
            Log.v(TAG, "checkAndHook: cellMock=$cellMock installed=$telephonyProxyInstalled")
            if (cellMock && !telephonyProxyInstalled) {
                val phoneBinder = getServiceMethod.invoke(null, "phone") as? IBinder
                if (phoneBinder == null) {
                    Log.w(TAG, "checkAndHook: phone binder is null")
                    return
                }
                val phoneProxy = TelephonyServiceProxy(phoneBinder)
                addServiceMethod.invoke(null, "phone", phoneProxy)
                telephonyProxyInstalled = true
                Log.i(TAG, "Telephony proxy installed")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "checkAndHook failed: ${e.message}", e)
        }
    }

    private fun startConfigPolling() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                RootConfigManager.loadCellConfig()
                checkAndHook()
            } catch (_: Throwable) {}
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** Fallback: scan TelephonyRegistry internal listener list and replace targets. */
    private fun startListenerReplacementPolling() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                replaceTelephonyRegistryListeners()
            } catch (_: Throwable) {}
        }, 2, 2, TimeUnit.SECONDS)
    }

    /** Active dispatch: periodically push mock cell data to all replaced listeners. */
    private fun startActiveDispatchPolling() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                dispatchMockCellData()
            } catch (_: Throwable) {}
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun replaceTelephonyRegistryListeners() {
        try {
            val phoneBinder = getServiceMethod.invoke(null, "phone") as? IBinder ?: return
            val realPhoneMgr = (phoneBinder as? TelephonyServiceProxy)?.original ?: phoneBinder

            // Find TelephonyRegistry field in PhoneInterfaceManager
            val regField = findField(realPhoneMgr, "mTelephonyRegistry", "mRegistry", "telephonyRegistry") ?: return
            val registry = regField.get(realPhoneMgr) ?: return

            // Find records list
            val recordsField = findField(registry, "mRecords", "mPhoneStateListenerRecords", "records") ?: return
            val records = recordsField.get(registry) as? List<*> ?: return

            for (record in records) {
                if (record == null) continue

                val pkg = findFieldValue(record, "callingPackage", "pkgForDebug", "packageName", "callingPkg") as? String
                if (!RootConfigManager.shouldMockPackage(pkg)) continue

                val callback = findFieldValue(record, "callback", "binder", "listener", "mCallback") as? IBinder
                if (callback == null || callback is MockPhoneStateListenerProxy) continue

                val proxy = MockPhoneStateListenerProxy(callback, pkg)
                val callbackField = findField(record, "callback", "binder", "listener", "mCallback") ?: continue
                callbackField.set(record, proxy)
                if (pkg != null) {
                    listenerProxies[pkg] = WeakReference(proxy)
                }
                Log.i(TAG, "Replaced PhoneStateListener callback for pkg=$pkg (fallback polling)")
            }
        } catch (e: Throwable) {
            Log.v(TAG, "replaceTelephonyRegistryListeners failed: ${e.message}")
        }
    }

    private fun dispatchMockCellData() {
        if (!RootConfigManager.isCellMockEnabled()) return
        resolveListenerTransactionCodes()

        val iterator = listenerProxies.iterator()
        while (iterator.hasNext()) {
            val (pkgName, weakRef) = iterator.next()
            val proxy = weakRef.get()
            if (proxy == null) {
                iterator.remove()
                continue
            }

            try {
                // Active dispatch: onCellLocationChanged
                if (txnOnCellLocationChanged >= 0) {
                    val data = Parcel.obtain()
                    data.writeInterfaceToken(MockPhoneStateListenerProxy.LISTENER_DESCRIPTOR)
                    data.writeBundle(createMockCellLocationBundle())
                    proxy.transact(txnOnCellLocationChanged, data, null, IBinder.FLAG_ONEWAY)
                    data.recycle()
                    Log.v(TAG, "Active dispatch onCellLocationChanged to pkg=$pkgName")
                }

                // Active dispatch: onCellInfoChanged
                if (txnOnCellInfoChanged >= 0) {
                    val data = Parcel.obtain()
                    data.writeInterfaceToken(MockPhoneStateListenerProxy.LISTENER_DESCRIPTOR)
                    val cellList = RootConfigManager.cellListRef.get()
                    val results = if (RootConfigManager.cellProxyEnabledRef.get() && cellList.isNotEmpty()) {
                        cellList.mapNotNull { createMockCellInfo(it) }
                    } else {
                        emptyList()
                    }
                    data.writeTypedList(results)
                    proxy.transact(txnOnCellInfoChanged, data, null, IBinder.FLAG_ONEWAY)
                    data.recycle()
                    Log.v(TAG, "Active dispatch onCellInfoChanged (size=${results.size}) to pkg=$pkgName")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "dispatchMockCellData failed for pkg=$pkgName: ${e.message}")
            }
        }
    }

    // ===== Utility: Mock data creation =====

    private fun createMockCellLocationBundle(): Bundle {
        val bundle = Bundle()
        val cellList = RootConfigManager.cellListRef.get()

        if (cellList.isEmpty()) {
            bundle.putInt("cid", Integer.MAX_VALUE)
            bundle.putInt("lac", Integer.MAX_VALUE)
            bundle.putInt("psc", Integer.MAX_VALUE)
            bundle.putInt("baseStationId", Integer.MAX_VALUE)
            bundle.putInt("systemId", Integer.MAX_VALUE)
            bundle.putInt("networkId", Integer.MAX_VALUE)
        } else {
            val cell = cellList.random()
            bundle.putInt("cid", cell.cid.toInt())
            bundle.putInt("lac", cell.lac)
            bundle.putInt("psc", cell.psc)
            bundle.putInt("baseStationId", cell.cid.toInt())
            bundle.putInt("systemId", cell.mnc)
            bundle.putInt("networkId", cell.lac)
        }

        // Inject mock location lat/lon (CDMA format: quarter seconds of arc, * 14400)
        val lat = RootConfigManager.getLatitude()
        val lon = RootConfigManager.getLongitude()
        if (lat != 0.0 || lon != 0.0) {
            bundle.putInt("baseStationLatitude", (lat * 14400.0).toInt())
            bundle.putInt("baseStationLongitude", (lon * 14400.0).toInt())
        }

        // Bundle flags matching FakeLocation C0077
        bundle.putBoolean("empty", false)
        bundle.putBoolean("emptyParcel", false)
        bundle.putInt("mFlags", 1536)
        bundle.putBoolean("parcelled", false)
        bundle.putInt("size", 0)

        return bundle
    }

    private fun createMockCellInfo(entry: RootConfigManager.CellEntry): CellInfo? {
        return try {
            when (entry.networkType.uppercase()) {
                "LTE" -> createLteCellInfo(entry)
                "GSM" -> createGsmCellInfo(entry)
                "WCDMA" -> createWcdmaCellInfo(entry)
                "CDMA" -> createCdmaCellInfo(entry)
                else -> createLteCellInfo(entry)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "createMockCellInfo failed: ${e.message}")
            null
        }
    }

    private fun createLteCellInfo(entry: RootConfigManager.CellEntry): CellInfo? {
        return try {
            val cellInfoClass = Class.forName("android.telephony.CellInfoLte")
            val cellInfo = cellInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as CellInfo

            val identityClass = Class.forName("android.telephony.CellIdentityLte")
            val identity = identityClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setCellIdentityField(identity, "mMcc", entry.mcc)
            setCellIdentityField(identity, "mMnc", entry.mnc)
            setCellIdentityField(identity, "mCi", entry.cid.toInt())
            setCellIdentityField(identity, "mPci", entry.psc)
            setCellIdentityField(identity, "mTac", entry.lac)

            val signalClass = Class.forName("android.telephony.CellSignalStrengthLte")
            val signal = signalClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setCellSignalField(signal, "mRsrp", -90)
            setCellSignalField(signal, "mRsrq", -10)

            val cellInfoLte = cellInfo as android.telephony.CellInfoLte
            RootConfigManager.setField(cellInfoLte, "mCellIdentity", identity)
            RootConfigManager.setField(cellInfoLte, "mCellSignalStrength", signal)
            cellInfoLte
        } catch (e: Throwable) {
            Log.w(TAG, "createLteCellInfo failed: ${e.message}")
            null
        }
    }

    private fun createGsmCellInfo(entry: RootConfigManager.CellEntry): CellInfo? {
        return try {
            val cellInfoClass = Class.forName("android.telephony.CellInfoGsm")
            val cellInfo = cellInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as CellInfo

            val identityClass = Class.forName("android.telephony.CellIdentityGsm")
            val identity = identityClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setCellIdentityField(identity, "mMcc", entry.mcc)
            setCellIdentityField(identity, "mMnc", entry.mnc)
            setCellIdentityField(identity, "mLac", entry.lac)
            setCellIdentityField(identity, "mCid", entry.cid.toInt())
            setCellIdentityField(identity, "mPsc", entry.psc)

            val signalClass = Class.forName("android.telephony.CellSignalStrengthGsm")
            val signal = signalClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setCellSignalField(signal, "mSignalStrength", -85)
            setCellSignalField(signal, "mBitErrorRate", 0)

            val cellInfoGsm = cellInfo as android.telephony.CellInfoGsm
            RootConfigManager.setField(cellInfoGsm, "mCellIdentity", identity)
            RootConfigManager.setField(cellInfoGsm, "mCellSignalStrength", signal)
            cellInfoGsm
        } catch (e: Throwable) {
            Log.w(TAG, "createGsmCellInfo failed: ${e.message}")
            null
        }
    }

    private fun createWcdmaCellInfo(entry: RootConfigManager.CellEntry): CellInfo? {
        return try {
            val cellInfoClass = Class.forName("android.telephony.CellInfoWcdma")
            val cellInfo = cellInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as CellInfo

            val identityClass = Class.forName("android.telephony.CellIdentityWcdma")
            val identity = identityClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setCellIdentityField(identity, "mMcc", entry.mcc)
            setCellIdentityField(identity, "mMnc", entry.mnc)
            setCellIdentityField(identity, "mLac", entry.lac)
            setCellIdentityField(identity, "mCid", entry.cid.toInt())
            setCellIdentityField(identity, "mPsc", entry.psc)

            val signalClass = Class.forName("android.telephony.CellSignalStrengthWcdma")
            val signal = signalClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            val cellInfoWcdma = cellInfo as android.telephony.CellInfoWcdma
            RootConfigManager.setField(cellInfoWcdma, "mCellIdentity", identity)
            RootConfigManager.setField(cellInfoWcdma, "mCellSignalStrength", signal)
            cellInfoWcdma
        } catch (e: Throwable) {
            Log.w(TAG, "createWcdmaCellInfo failed: ${e.message}")
            null
        }
    }

    private fun createCdmaCellInfo(entry: RootConfigManager.CellEntry): CellInfo? {
        return try {
            val cellInfoClass = Class.forName("android.telephony.CellInfoCdma")
            val cellInfo = cellInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as CellInfo

            val identityClass = Class.forName("android.telephony.CellIdentityCdma")
            val identity = identityClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setCellIdentityField(identity, "mNetworkId", entry.mnc)
            setCellIdentityField(identity, "mSystemId", entry.mcc)
            setCellIdentityField(identity, "mBasestationId", entry.cid.toInt())
            setCellIdentityField(identity, "mLatitude", (entry.latitude * 14400).toInt())
            setCellIdentityField(identity, "mLongitude", (entry.longitude * 14400).toInt())

            val signalClass = Class.forName("android.telephony.CellSignalStrengthCdma")
            val signal = signalClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            val cellInfoCdma = cellInfo as android.telephony.CellInfoCdma
            RootConfigManager.setField(cellInfoCdma, "mCellIdentity", identity)
            RootConfigManager.setField(cellInfoCdma, "mCellSignalStrength", signal)
            cellInfoCdma
        } catch (e: Throwable) {
            Log.w(TAG, "createCdmaCellInfo failed: ${e.message}")
            null
        }
    }

    private fun setCellIdentityField(obj: Any, fieldName: String, value: Any) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            when (value) {
                is Int -> field.setInt(obj, value)
                is Long -> field.setLong(obj, value)
                is String -> field.set(obj, value)
            }
        } catch (_: Throwable) {}
    }

    private fun setCellSignalField(obj: Any, fieldName: String, value: Int) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.setInt(obj, value)
        } catch (_: Throwable) {}
    }

    // ===== Reflection utilities =====

    private fun findField(obj: Any, vararg names: String): java.lang.reflect.Field? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (name in names) {
                try {
                    return clazz.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: Throwable) {}
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun findFieldValue(obj: Any, vararg names: String): Any? {
        return findField(obj, *names)?.get(obj)
    }

    // ===== TelephonyServiceProxy =====

    private class TelephonyServiceProxy(val original: IBinder) : Binder() {
        companion object {
            private const val DESCRIPTOR = "com.android.internal.telephony.ITelephony"
        }

        init {
            attachInterface(null, DESCRIPTOR)
        }

        override fun getInterfaceDescriptor(): String = DESCRIPTOR

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val enabled = try { RootConfigManager.isCellMockEnabled() } catch (_: Throwable) { false }
            if (!enabled) return original.transact(code, data, reply, flags)

            return when (code) {
                transactionGetCellLocation -> handleGetCellLocation(data, reply, flags)
                transactionGetAllCellInfo -> handleGetAllCellInfo(data, reply, flags)
                else -> original.transact(code, data, reply, flags)
            }
        }

        private fun handleGetCellLocation(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(0)
                Log.d(TAG, "handleGetCellLocation: returned null")
                true
            } catch (_: Throwable) {
                original.transact(transactionGetCellLocation, data, reply, flags)
            }
        }

        private fun handleGetAllCellInfo(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()

                val cellList = RootConfigManager.cellListRef.get()
                if (RootConfigManager.cellProxyEnabledRef.get() && cellList.isNotEmpty()) {
                    val results = cellList.mapNotNull { createMockCellInfo(it) }
                    reply?.writeTypedList(results)
                    Log.d(TAG, "Cell proxy returned ${results.size} cell infos, listSize=${cellList.size}")
                } else {
                    Log.d(TAG, "Cell proxy returned empty list, cellProxy=${RootConfigManager.cellProxyEnabledRef.get()} listSize=${cellList.size}")
                    reply?.writeTypedList(emptyList<CellInfo>())
                }
                true
            } catch (_: Throwable) {
                original.transact(transactionGetAllCellInfo, data, reply, flags)
            }
        }
    }

    // ===== MockPhoneStateListenerProxy =====

    private class MockPhoneStateListenerProxy(
        private val original: IBinder,
        private val pkgName: String?
    ) : Binder() {
        companion object {
            const val LISTENER_DESCRIPTOR = "com.android.internal.telephony.IPhoneStateListener"
        }

        init {
            resolveListenerTransactionCodes()
            attachInterface(null, LISTENER_DESCRIPTOR)
        }

        override fun getInterfaceDescriptor(): String = LISTENER_DESCRIPTOR

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val enabled = try { RootConfigManager.isCellMockEnabled() } catch (_: Throwable) { false }
            if (!enabled) return original.transact(code, data, reply, flags)

            return when (code) {
                txnOnCellLocationChanged -> handleOnCellLocationChanged(data, reply, flags)
                txnOnCellInfoChanged -> handleOnCellInfoChanged(data, reply, flags)
                else -> original.transact(code, data, reply, flags)
            }
        }

        private fun handleOnCellLocationChanged(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(LISTENER_DESCRIPTOR)
                // Consume original bundle (we replace it with mock)
                data.readBundle()

                val bundle = createMockCellLocationBundle()

                val newData = Parcel.obtain()
                newData.writeInterfaceToken(LISTENER_DESCRIPTOR)
                newData.writeBundle(bundle)

                val result = original.transact(txnOnCellLocationChanged, newData, reply, flags)
                newData.recycle()
                Log.v(TAG, "MockPhoneStateListenerProxy: sent mock cell location to pkg=$pkgName")
                result
            } catch (e: Throwable) {
                Log.w(TAG, "handleOnCellLocationChanged failed: ${e.message}")
                original.transact(txnOnCellLocationChanged, data, reply, flags)
            }
        }

        private fun handleOnCellInfoChanged(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(LISTENER_DESCRIPTOR)
                // Consume original list
                try { data.createTypedArrayList(CellInfo.CREATOR) } catch (_: Throwable) {}

                val cellList = RootConfigManager.cellListRef.get()
                val results = if (RootConfigManager.cellProxyEnabledRef.get() && cellList.isNotEmpty()) {
                    cellList.mapNotNull { createMockCellInfo(it) }
                } else {
                    emptyList()
                }

                val newData = Parcel.obtain()
                newData.writeInterfaceToken(LISTENER_DESCRIPTOR)
                newData.writeTypedList(results)

                val result = original.transact(txnOnCellInfoChanged, newData, reply, flags)
                newData.recycle()
                Log.v(TAG, "MockPhoneStateListenerProxy: sent mock cell info list (size=${results.size}) to pkg=$pkgName")
                result
            } catch (e: Throwable) {
                Log.w(TAG, "handleOnCellInfoChanged failed: ${e.message}")
                original.transact(txnOnCellInfoChanged, data, reply, flags)
            }
        }
    }
}
