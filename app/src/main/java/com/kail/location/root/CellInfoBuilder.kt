package com.kail.location.root

import android.telephony.*
import com.kail.location.utils.KailLog

/**
 * CellInfo Builder
 * Converts CellInfoData to real Android CellInfo objects via reflection
 * Based on Fake Location's C0008
 */
object CellInfoBuilder {

    fun buildCellInfoList(cells: List<CellInfoData>): List<CellInfo> {
        return cells.mapNotNull { buildCellInfo(it) }
    }

    fun buildNeighboringCellInfoList(cells: List<CellInfoData>): List<NeighboringCellInfo> {
        return cells.mapNotNull { buildNeighboringCellInfo(it) }
    }

    private fun buildCellInfo(data: CellInfoData): CellInfo? {
        return try {
            when (data.networkType.uppercase()) {
                "GSM" -> buildGsmCellInfo(data)
                "LTE" -> buildLteCellInfo(data)
                "WCDMA" -> buildWcdmaCellInfo(data)
                "CDMA" -> buildCdmaCellInfo(data)
                else -> buildLteCellInfo(data)
            }
        } catch (e: Exception) {
            KailLog.e(null, "CellInfoBuilder", "buildCellInfo failed: ${e.message}")
            null
        }
    }

    private fun buildGsmCellInfo(data: CellInfoData): CellInfoGsm {
        val cellInfo = CellInfoGsm::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val identity = CellIdentityGsm::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val signalStrength = CellSignalStrengthGsm::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        // Set identity fields via reflection
        setField(identity, "mMcc", data.mcc)
        setField(identity, "mMnc", data.mnc)
        setField(identity, "mLac", data.lac)
        setField(identity, "mCid", data.cid)
        setField(identity, "mArfcn", Int.MAX_VALUE)
        setField(identity, "mBsic", Int.MAX_VALUE)

        setSignalLevel(signalStrength, data.signal)

        setField(cellInfo, "mCellIdentityGsm", identity)
        setField(cellInfo, "mCellSignalStrengthGsm", signalStrength)
        return cellInfo
    }

    private fun buildLteCellInfo(data: CellInfoData): CellInfoLte {
        val cellInfo = CellInfoLte::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val identity = CellIdentityLte::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val signalStrength = CellSignalStrengthLte::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        setField(identity, "mMcc", data.mcc)
        setField(identity, "mMnc", data.mnc)
        setField(identity, "mCi", data.cid)
        setField(identity, "mPci", data.psc)
        setField(identity, "mTac", data.lac)
        setField(identity, "mEarfcn", Int.MAX_VALUE)

        setSignalLevel(signalStrength, data.signal)

        setField(cellInfo, "mCellIdentityLte", identity)
        setField(cellInfo, "mCellSignalStrengthLte", signalStrength)
        return cellInfo
    }

    private fun buildWcdmaCellInfo(data: CellInfoData): CellInfoWcdma {
        val cellInfo = CellInfoWcdma::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val identity = CellIdentityWcdma::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val signalStrength = CellSignalStrengthWcdma::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        setField(identity, "mMcc", data.mcc)
        setField(identity, "mMnc", data.mnc)
        setField(identity, "mLac", data.lac)
        setField(identity, "mCid", data.cid)
        setField(identity, "mPsc", data.psc)
        setField(identity, "mUarfcn", Int.MAX_VALUE)

        setSignalLevel(signalStrength, data.signal)

        setField(cellInfo, "mCellIdentityWcdma", identity)
        setField(cellInfo, "mCellSignalStrengthWcdma", signalStrength)
        return cellInfo
    }

    private fun buildCdmaCellInfo(data: CellInfoData): CellInfoCdma {
        val cellInfo = CellInfoCdma::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val identity = CellIdentityCdma::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val signalStrength = CellSignalStrengthCdma::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        setField(identity, "mNetworkId", data.lac)
        setField(identity, "mSystemId", data.cid)
        setField(identity, "mBasestationId", data.psc)

        setSignalLevel(signalStrength, data.signal)

        setField(cellInfo, "mCellIdentityCdma", identity)
        setField(cellInfo, "mCellSignalStrengthCdma", signalStrength)
        return cellInfo
    }

    private fun buildNeighboringCellInfo(data: CellInfoData): NeighboringCellInfo? {
        return try {
            NeighboringCellInfo(data.signal, data.cid.toString(), data.networkType.hashCode())
        } catch (e: Exception) {
            KailLog.e(null, "CellInfoBuilder", "buildNeighboringCellInfo failed: ${e.message}")
            null
        }
    }

    private fun setSignalLevel(strength: CellSignalStrength, level: Int) {
        try {
            val fields = arrayOf("mLevel", "mRssi", "mDbm", "mAsuLevel")
            for (fieldName in fields) {
                try {
                    val field = strength.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(strength, level)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            KailLog.e(null, "CellInfoBuilder", "setSignalLevel failed: ${e.message}")
        }
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (e: Exception) {
            KailLog.e(null, "CellInfoBuilder", "setField $fieldName failed: ${e.message}")
        }
    }
}
