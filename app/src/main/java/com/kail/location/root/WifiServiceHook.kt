package com.kail.location.root

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WiFi Service Hook - Independent of location simulation.
 * Installs a proxy on the wifi binder to mock getConnectionInfo and getScanResults.
 */
object WifiServiceHook {
    private const val TAG = "KailWifiHook"

    private var wifiProxyInstalled = false

    private val serviceManagerClass by lazy { Class.forName("android.os.ServiceManager") }
    private val getServiceMethod by lazy {
        serviceManagerClass.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
    }
    private val addServiceMethod by lazy {
        serviceManagerClass.getDeclaredMethod("addService", String::class.java, IBinder::class.java).apply { isAccessible = true }
    }

    @JvmStatic
    fun init() {
        Log.i(TAG, "WifiServiceHook.init() pid=${android.os.Process.myPid()}")
        try {
            checkAndHook()
            startConfigPolling()
        } catch (e: Throwable) {
            Log.e(TAG, "init FAILED", e)
        }
    }

    private fun checkAndHook() {
        try {
            val wifiMock = RootConfigManager.isWifiMockEnabled()
            Log.v(TAG, "checkAndHook: wifiMock=$wifiMock installed=$wifiProxyInstalled")
            if (wifiMock && !wifiProxyInstalled) {
                val wifiBinder = getServiceMethod.invoke(null, "wifi") as? IBinder
                if (wifiBinder == null) {
                    Log.w(TAG, "checkAndHook: wifi binder is null")
                    return
                }
                val wifiProxy = WifiServiceProxy(wifiBinder)
                addServiceMethod.invoke(null, "wifi", wifiProxy)
                wifiProxyInstalled = true
                Log.i(TAG, "Wifi proxy installed on binder=${wifiBinder.javaClass.name}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "checkAndHook failed: ${e.message}", e)
        }
    }

    private fun startConfigPolling() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            try {
                RootConfigManager.loadWifiConfig()
                checkAndHook()
            } catch (_: Throwable) {}
        }, 1, 1, TimeUnit.SECONDS)
    }

    private class WifiServiceProxy(private val original: IBinder) : Binder() {
        companion object {
            private const val DESCRIPTOR = "android.net.wifi.IWifiManager"
            private var txnGetConnectionInfo = -1
            private var txnGetScanResults = -1
            private var txnStartScan = -1
            private var initialized = false

            @Synchronized
            fun initTxnCodes() {
                if (initialized) return
                var resolved = false
                try {
                    val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub")
                    for (field in stubClass.declaredFields) {
                        when (field.name) {
                            "TRANSACTION_getConnectionInfo" -> {
                                field.isAccessible = true
                                txnGetConnectionInfo = field.getInt(null)
                            }
                            "TRANSACTION_getScanResults" -> {
                                field.isAccessible = true
                                txnGetScanResults = field.getInt(null)
                            }
                            "TRANSACTION_startScan" -> {
                                field.isAccessible = true
                                txnStartScan = field.getInt(null)
                            }
                        }
                    }
                    if (txnGetConnectionInfo != -1 && txnGetScanResults != -1) {
                        resolved = true
                        Log.i(TAG, "WiFi txn resolved: conn=$txnGetConnectionInfo scan=$txnGetScanResults startScan=$txnStartScan")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "WiFi txn resolve failed (boot cp): ${e.message}")
                }
                if (!resolved) {
                    try {
                        val wifiClass = Class.forName("com.android.server.wifi.WifiServiceImpl")
                        val cl = wifiClass.classLoader
                        val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub", true, cl)
                        for (field in stubClass.declaredFields) {
                            when (field.name) {
                                "TRANSACTION_getConnectionInfo" -> {
                                    field.isAccessible = true
                                    txnGetConnectionInfo = field.getInt(null)
                                }
                                "TRANSACTION_getScanResults" -> {
                                    field.isAccessible = true
                                    txnGetScanResults = field.getInt(null)
                                }
                                "TRANSACTION_startScan" -> {
                                    field.isAccessible = true
                                    txnStartScan = field.getInt(null)
                                }
                            }
                        }
                        if (txnGetConnectionInfo != -1 && txnGetScanResults != -1) {
                            resolved = true
                            Log.i(TAG, "WiFi txn resolved via apex: conn=$txnGetConnectionInfo scan=$txnGetScanResults startScan=$txnStartScan")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "WiFi txn resolve failed (apex): ${e.message}")
                    }
                }
                if (!resolved) {
                    Log.w(TAG, "WiFi txn using fallback codes")
                    txnGetConnectionInfo = 4
                    txnGetScanResults = 1
                    txnStartScan = 8
                }
                initialized = true
            }
        }

        init {
            attachInterface(null, DESCRIPTOR)
        }

        override fun getInterfaceDescriptor(): String = DESCRIPTOR

        private var cachedWifiInfo: android.net.wifi.WifiInfo? = null
        private var cachedWifiInfoTime = 0L
        private var cachedScanResults: List<android.net.wifi.ScanResult>? = null
        private var cachedScanResultsTime = 0L

        init { initTxnCodes() }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            Log.v(TAG, "WiFi onTransact code=$code pid=$callingPid uid=$callingUid txnConn=$txnGetConnectionInfo txnScan=$txnGetScanResults txnStart=$txnStartScan")
            return when (code) {
                txnGetConnectionInfo -> handleGetConnectionInfo(data, reply, flags)
                txnGetScanResults -> handleGetScanResults(data, reply, flags)
                txnStartScan -> handleStartScan(data, reply, flags)
                else -> original.transact(code, data, reply, flags)
            }
        }

        private fun isHookWifi(): Boolean {
            return try {
                RootConfigManager.isWifiMockEnabled()
            } catch (_: Throwable) { false }
        }

        private fun isAllowMockPackage(): Boolean {
            return try {
                if (RootConfigManager.isWifiMockEnabled()) return true
                if (RootConfigManager.isGlobalMode()) return true
                val uid = Binder.getCallingUid()
                val pkg = getPackageNameByUid(uid)
                val result = RootConfigManager.shouldMockPackage(pkg)
                Log.v(TAG, "isAllowMockPackage: uid=$uid pkg=$pkg result=$result")
                result
            } catch (e: Throwable) {
                Log.w(TAG, "isAllowMockPackage failed: ${e.message}")
                false
            }
        }

        private fun getPackageNameByUid(uid: Int): String? {
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val app = atClass.getMethod("currentApplication").invoke(null)
                val pm = app?.javaClass?.getMethod("getPackageManager")?.invoke(app)
                val pkgs = pm?.javaClass?.getMethod("getPackagesForUid", Int::class.java)?.invoke(pm, uid) as? Array<String>
                pkgs?.firstOrNull()?.let { return it }
            } catch (_: Throwable) {}
            try {
                val pid = Binder.getCallingPid()
                val cmdline = File("/proc/$pid/cmdline").readText().trim().replace("\u0000", "")
                if (cmdline.isNotBlank()) return cmdline
            } catch (_: Throwable) {}
            return null
        }

        private fun handleGetConnectionInfo(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            Log.v(TAG, "handleGetConnectionInfo called")
            return try {
                val wifiInfo = mockGetConnectionInfo()
                if (wifiInfo != null) {
                    data.enforceInterface("android.net.wifi.IWifiManager")
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    reply?.let { wifiInfo.writeToParcel(it, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE) }
                    Log.i(TAG, "WiFi getConnectionInfo MOCKED ssid=${wifiInfo.ssid} bssid=${wifiInfo.bssid}")
                    return true
                }
                Log.v(TAG, "handleGetConnectionInfo: not mocking, fallback to original")
                original.transact(txnGetConnectionInfo, data, reply, flags)
            } catch (e: Throwable) {
                Log.w(TAG, "handleGetConnectionInfo failed: ${e.message}", e)
                original.transact(txnGetConnectionInfo, data, reply, flags)
            }
        }

        private fun mockGetConnectionInfo(): android.net.wifi.WifiInfo? {
            try {
                val hookWifi = isHookWifi()
                val allowPkg = isAllowMockPackage()
                val wifiList = RootConfigManager.wifiListRef.get()
                Log.v(TAG, "mockGetConnectionInfo: hookWifi=$hookWifi allowPkg=$allowPkg wifiList=${wifiList.size}")
                if (hookWifi && allowPkg && wifiList.isNotEmpty()) {
                    Log.i(TAG, "mockGetConnectionInfo: returning mock wifi ssid=${wifiList[0].ssid}")
                    return createMockWifiInfo(wifiList[0])
                }
                return null
            } catch (e: Throwable) {
                Log.w(TAG, "mockGetConnectionInfo failed: ${e.message}", e)
                return null
            }
        }

        private fun handleGetScanResults(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            Log.v(TAG, "handleGetScanResults called")
            return try {
                val results = mockGetScanResults()
                if (results != null) {
                    data.enforceInterface("android.net.wifi.IWifiManager")
                    try { data.readString() } catch (_: Throwable) {}
                    try { data.readString() } catch (_: Throwable) {}
                    reply?.writeNoException()
                    reply?.writeTypedList(results)
                    Log.i(TAG, "WiFi getScanResults MOCKED ${results.size} results")
                    return true
                }
                Log.v(TAG, "handleGetScanResults: not mocking, fallback to original")
                original.transact(txnGetScanResults, data, reply, flags)
            } catch (e: Throwable) {
                Log.w(TAG, "handleGetScanResults failed: ${e.message}", e)
                original.transact(txnGetScanResults, data, reply, flags)
            }
        }

        private fun mockGetScanResults(): List<android.net.wifi.ScanResult>? {
            try {
                val hookWifi = isHookWifi()
                val allowPkg = isAllowMockPackage()
                val wifiList = RootConfigManager.wifiListRef.get()
                Log.v(TAG, "mockGetScanResults: hookWifi=$hookWifi allowPkg=$allowPkg wifiList=${wifiList.size}")
                if (hookWifi && allowPkg && wifiList.isNotEmpty()) {
                    Log.i(TAG, "mockGetScanResults: returning ${wifiList.size} mock scan results")
                    return wifiList.mapNotNull { createMockScanResult(it) }
                }
                return null
            } catch (e: Throwable) {
                Log.w(TAG, "mockGetScanResults failed: ${e.message}", e)
                return null
            }
        }

        private fun handleStartScan(data: Parcel, reply: Parcel?, flags: Int): Boolean {
            Log.v(TAG, "handleStartScan called")
            return try {
                if (isHookWifi() && isAllowMockPackage()) {
                    data.enforceInterface("android.net.wifi.IWifiManager")
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    Log.i(TAG, "WiFi startScan MOCKED=true")
                    return true
                }
                Log.v(TAG, "handleStartScan: not mocking, fallback to original")
                original.transact(txnStartScan, data, reply, flags)
            } catch (e: Throwable) {
                Log.w(TAG, "handleStartScan failed: ${e.message}", e)
                original.transact(txnStartScan, data, reply, flags)
            }
        }

        private fun createMockScanResult(entry: RootConfigManager.WifiEntry): android.net.wifi.ScanResult? {
            return try {
                val result = android.net.wifi.ScanResult()
                RootConfigManager.setField(result, "SSID", entry.ssid)
                RootConfigManager.setField(result, "BSSID", entry.bssid)
                RootConfigManager.setField(result, "capabilities", entry.capabilities)
                RootConfigManager.setField(result, "level", entry.rssi)
                RootConfigManager.setField(result, "frequency", entry.frequency)
                RootConfigManager.setField(result, "timestamp", System.currentTimeMillis() * 1000L)
                try { result.SSID = entry.ssid } catch (_: Throwable) {}
                try { result.BSSID = entry.bssid } catch (_: Throwable) {}
                try { result.level = entry.rssi } catch (_: Throwable) {}
                try { result.frequency = entry.frequency } catch (_: Throwable) {}
                try { result.capabilities = entry.capabilities } catch (_: Throwable) {}
                result
            } catch (e: Throwable) {
                Log.w(TAG, "createMockScanResult failed: ${e.message}")
                null
            }
        }

        private fun createMockWifiInfo(entry: RootConfigManager.WifiEntry): android.net.wifi.WifiInfo? {
            return try {
                val ctor = android.net.wifi.WifiInfo::class.java.getDeclaredConstructor()
                ctor.isAccessible = true
                val info = ctor.newInstance() as android.net.wifi.WifiInfo

                setWifiSsid(info, entry.ssid)

                try {
                    val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setBSSID", String::class.java)
                    m.isAccessible = true
                    m.invoke(info, entry.bssid)
                } catch (_: Throwable) { RootConfigManager.setField(info, "mBSSID", entry.bssid) }

                try {
                    val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setMacAddress", String::class.java)
                    m.isAccessible = true
                    m.invoke(info, entry.bssid)
                } catch (_: Throwable) {}

                try {
                    val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setRssi", Int::class.java)
                    m.isAccessible = true
                    m.invoke(info, entry.rssi)
                } catch (_: Throwable) { RootConfigManager.setField(info, "mRssi", entry.rssi) }

                try {
                    val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setLinkSpeed", Int::class.java)
                    m.isAccessible = true
                    m.invoke(info, entry.linkSpeed)
                } catch (_: Throwable) { RootConfigManager.setField(info, "mLinkSpeed", entry.linkSpeed) }

                try {
                    val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setFrequency", Int::class.java)
                    m.isAccessible = true
                    m.invoke(info, entry.frequency)
                } catch (_: Throwable) { RootConfigManager.setField(info, "mFrequency", entry.frequency) }

                RootConfigManager.setField(info, "mNetworkId", 1000)
                RootConfigManager.setField(info, "score", 60)

                try {
                    val suppClass = Class.forName("android.net.wifi.SupplicantState")
                    val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setSupplicantState", suppClass)
                    m.isAccessible = true
                    val completed = suppClass.getDeclaredField("COMPLETED").get(null)
                    m.invoke(info, completed)
                } catch (_: Throwable) {}

                info
            } catch (e: Throwable) {
                Log.w(TAG, "createMockWifiInfo failed: ${e.message}")
                null
            }
        }

        private fun setWifiSsid(info: android.net.wifi.WifiInfo, ssid: String) {
            val methods = listOf(
                Pair("createFromAsciiEncoded", arrayOf(ssid)),
                Pair("createFromByteArray", arrayOf(ssid.toByteArray())),
                Pair("fromBytes", arrayOf(ssid.toByteArray())),
                Pair("fromUtf8Text", arrayOf(ssid.toByteArray()))
            )
            for ((methodName, args) in methods) {
                try {
                    val wifiSsidClass = Class.forName("android.net.wifi.WifiSsid")
                    val method = wifiSsidClass.getDeclaredMethod(methodName, args[0].javaClass)
                    method.isAccessible = true
                    val wifiSsid = method.invoke(null, *args)
                    try {
                        val f = android.net.wifi.WifiInfo::class.java.getDeclaredField("mWifiSsid")
                        f.isAccessible = true
                        f.set(info, wifiSsid)
                        return
                    } catch (_: Throwable) {}
                    try {
                        val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setSSID", wifiSsidClass)
                        m.isAccessible = true
                        m.invoke(info, wifiSsid)
                        return
                    } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            try {
                val m = android.net.wifi.WifiInfo::class.java.getDeclaredMethod("setSSID", String::class.java)
                m.isAccessible = true
                m.invoke(info, ssid)
            } catch (_: Throwable) { RootConfigManager.setField(info, "mSSID", ssid) }
        }
    }
}
