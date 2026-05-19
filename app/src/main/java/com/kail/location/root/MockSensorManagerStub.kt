package com.kail.location.root

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.kail.location.utils.KailLog

/**
 * Manual Binder Stub for Mock Sensor Manager
 */
class MockSensorManagerStub : Binder() {

    companion object {
        const val DESCRIPTOR = "com.kail.location.root.IMockSensorManager"

        const val TRANSACTION_startMockSteps = IBinder.FIRST_CALL_TRANSACTION + 0
        const val TRANSACTION_stopMockSteps = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_setStepParams = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_isMocking = IBinder.FIRST_CALL_TRANSACTION + 3
    }

    private var isMocking = false
    private val hookController = SensorHookController()

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            TRANSACTION_startMockSteps -> {
                data.enforceInterface(DESCRIPTOR)
                val result = startMockSteps()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
            TRANSACTION_stopMockSteps -> {
                data.enforceInterface(DESCRIPTOR)
                val result = stopMockSteps()
                reply?.writeNoException()
                reply?.writeInt(if (result) 1 else 0)
                return true
            }
            TRANSACTION_setStepParams -> {
                data.enforceInterface(DESCRIPTOR)
                val spm = data.readFloat()
                val scheme = data.readInt()
                setStepParams(spm, scheme)
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

    fun startMockSteps(): Boolean {
        KailLog.i(null, "MockSensorManager", "startMockSteps called")
        if (isMocking) return true
        isMocking = true
        hookController.startMockSteps()
        return true
    }

    fun stopMockSteps(): Boolean {
        KailLog.i(null, "MockSensorManager", "stopMockSteps called")
        isMocking = false
        hookController.stopMockSteps()
        return true
    }

    fun setStepParams(spm: Float, scheme: Int) {
        KailLog.i(null, "MockSensorManager", "setStepParams: spm=$spm, scheme=$scheme")
        hookController.setStepParams(spm, scheme)
    }

    fun isMocking(): Boolean = isMocking
}
