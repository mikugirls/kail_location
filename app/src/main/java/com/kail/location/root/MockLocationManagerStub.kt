package com.kail.location.root

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.kail.location.utils.KailLog

/**
 * Manual Binder Stub for Mock Location Manager
 * Does not depend on AIDL-generated classes
 * Based on Fake Location's BinderC0053 pattern
 */
class MockLocationManagerStub : Binder() {

    companion object {
        const val DESCRIPTOR = "com.kail.location.root.IMockLocationManager"

        // Transaction codes
        const val TRANSACTION_startMockLocation = IBinder.FIRST_CALL_TRANSACTION + 0
        const val TRANSACTION_stopMockLocation = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_setMockLocation = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_setMockCells = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_setMockGnss = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_setAllowList = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_setBlockList = IBinder.FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_setAntiPullback = IBinder.FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_isMocking = IBinder.FIRST_CALL_TRANSACTION + 8
        const val TRANSACTION_addListener = IBinder.FIRST_CALL_TRANSACTION + 9
        const val TRANSACTION_removeListener = IBinder.FIRST_CALL_TRANSACTION + 10
    }

    private var isMocking = false
    private val listeners = mutableListOf<IBinder>()
    private val hookController = LocationHookController()

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            TRANSACTION_startMockLocation -> {
                data.enforceInterface(DESCRIPTOR)
                val result = startMockLocation()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
            TRANSACTION_stopMockLocation -> {
                data.enforceInterface(DESCRIPTOR)
                val result = stopMockLocation()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
            TRANSACTION_setMockLocation -> {
                data.enforceInterface(DESCRIPTOR)
                val location = data.readParcelable<LocationData>(LocationData::class.java.classLoader)
                setMockLocation(location)
                reply?.writeNoException()
                return true
            }
            TRANSACTION_setMockCells -> {
                data.enforceInterface(DESCRIPTOR)
                val cells = data.createTypedArrayList(CellInfoData.CREATOR)
                setMockCells(cells ?: emptyList())
                reply?.writeNoException()
                return true
            }
            TRANSACTION_setMockGnss -> {
                data.enforceInterface(DESCRIPTOR)
                val enable = data.readInt() != 0
                setMockGnss(enable)
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
            TRANSACTION_setAntiPullback -> {
                data.enforceInterface(DESCRIPTOR)
                val enable = data.readInt() != 0
                setAntiPullback(enable)
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
            TRANSACTION_addListener -> {
                data.enforceInterface(DESCRIPTOR)
                val listener = data.readStrongBinder()
                addListener(listener)
                reply?.writeNoException()
                return true
            }
            TRANSACTION_removeListener -> {
                data.enforceInterface(DESCRIPTOR)
                val listener = data.readStrongBinder()
                removeListener(listener)
                reply?.writeNoException()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    fun startMockLocation(): Boolean {
        KailLog.i(null, "MockLocationManager", "startMockLocation called")
        if (isMocking) return true
        isMocking = true
        hookController.startMockLocation()
        return true
    }

    fun stopMockLocation(): Boolean {
        KailLog.i(null, "MockLocationManager", "stopMockLocation called")
        isMocking = false
        hookController.stopMockLocation()
        return true
    }

    fun setMockLocation(location: LocationData?) {
        if (location != null) {
            KailLog.i(null, "MockLocationManager", "setMockLocation: lat=${location.latitude}, lng=${location.longitude}")
            hookController.setMockLocation(location)
        }
    }

    fun setMockCells(cells: List<CellInfoData>) {
        KailLog.i(null, "MockLocationManager", "setMockCells: count=${cells.size}")
        hookController.setMockCells(cells)
    }

    fun setMockGnss(enable: Boolean) {
        KailLog.i(null, "MockLocationManager", "setMockGnss: $enable")
        hookController.setMockGnss(enable)
    }

    fun setAllowList(packages: List<String>) {
        KailLog.i(null, "MockLocationManager", "setAllowList: ${packages.joinToString()}")
        hookController.setAllowList(packages)
    }

    fun setBlockList(packages: List<String>) {
        KailLog.i(null, "MockLocationManager", "setBlockList: ${packages.joinToString()}")
        hookController.setBlockList(packages)
    }

    fun setAntiPullback(enable: Boolean) {
        KailLog.i(null, "MockLocationManager", "setAntiPullback: $enable")
        hookController.setAntiPullback(enable)
    }

    fun isMocking(): Boolean = isMocking

    fun addListener(listener: IBinder?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: IBinder?) {
        if (listener != null) {
            listeners.remove(listener)
        }
    }
}
