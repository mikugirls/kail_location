package com.kail.location.root

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.kail.location.utils.KailLog

/**
 * Manual Binder Stub for Mock WiFi Manager
 */
class MockWifiManagerStub : Binder() {

    companion object {
        const val DESCRIPTOR = "com.kail.location.root.IMockWifiManager"

        const val TRANSACTION_startMockWifi = IBinder.FIRST_CALL_TRANSACTION + 0
        const val TRANSACTION_stopMockWifi = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_setMockWifi = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_setMockWifiList = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_setAllowList = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_setBlockList = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_isMocking = IBinder.FIRST_CALL_TRANSACTION + 6
    }

    private var isMocking = false
    private val hookController = WifiHookController()

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            TRANSACTION_startMockWifi -> {
                data.enforceInterface(DESCRIPTOR)
                val result = startMockWifi()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
            TRANSACTION_stopMockWifi -> {
                data.enforceInterface(DESCRIPTOR)
                val result = stopMockWifi()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
            TRANSACTION_setMockWifi -> {
                data.enforceInterface(DESCRIPTOR)
                val wifi = data.readParcelable<WifiInfoData>(WifiInfoData::class.java.classLoader)
                setMockWifi(wifi)
                reply?.writeNoException()
                return true
            }
            TRANSACTION_setMockWifiList -> {
                data.enforceInterface(DESCRIPTOR)
                val wifiList = data.createTypedArrayList(WifiInfoData.CREATOR)
                setMockWifiList(wifiList ?: emptyList())
                reply?.writeNoException()
                return true
            }
            TRANSACTION_setAllowList -> {
                data.enforceInterface(DESCRIPTOR)
                val packages = data.createStringArrayList()
                setAllowList(packages ?: emptyList())
                reply?.writeNoException()
                return true
            }
            TRANSACTION_setBlockList -> {
                data.enforceInterface(DESCRIPTOR)
                val packages = data.createStringArrayList()
                setBlockList(packages ?: emptyList())
                reply?.writeNoException()
                return true
            }
            TRANSACTION_isMocking -> {
                data.enforceInterface(DESCRIPTOR)
                val result = isMocking()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    fun startMockWifi(): Boolean {
        KailLog.i(null, "MockWifiManager", "startMockWifi called")
        if (isMocking) return true
        isMocking = true
        hookController.startMockWifi()
        return true
    }

    fun stopMockWifi(): Boolean {
        KailLog.i(null, "MockWifiManager", "stopMockWifi called")
        isMocking = false
        hookController.stopMockWifi()
        return true
    }

    fun setMockWifi(wifi: WifiInfoData?) {
        if (wifi != null) {
            KailLog.i(null, "MockWifiManager", "setMockWifi: ssid=${wifi.ssid}")
            hookController.setMockWifi(wifi)
        }
    }

    fun setMockWifiList(wifiList: List<WifiInfoData>) {
        KailLog.i(null, "MockWifiManager", "setMockWifiList: count=${wifiList.size}")
        hookController.setMockWifiList(wifiList)
    }

    fun setAllowList(packages: List<String>) {
        KailLog.i(null, "MockWifiManager", "setAllowList: ${packages.joinToString()}")
        hookController.setAllowList(packages)
    }

    fun setBlockList(packages: List<String>) {
        KailLog.i(null, "MockWifiManager", "setBlockList: ${packages.joinToString()}")
        hookController.setBlockList(packages)
    }

    fun isMocking(): Boolean = isMocking
}
