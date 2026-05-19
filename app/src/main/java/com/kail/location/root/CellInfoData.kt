package com.kail.location.root

import android.os.Parcel
import android.os.Parcelable

/**
 * Cell info data parcelable for AIDL IPC
 */
data class CellInfoData(
    val mcc: Int = 0,
    val mnc: Int = 0,
    val lac: Int = 0,
    val cid: Int = 0,
    val psc: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val signal: Int = -85,
    val networkType: String = "LTE"
) : Parcelable {
    constructor(parcel: Parcel) : this(
        mcc = parcel.readInt(),
        mnc = parcel.readInt(),
        lac = parcel.readInt(),
        cid = parcel.readInt(),
        psc = parcel.readInt(),
        latitude = parcel.readDouble(),
        longitude = parcel.readDouble(),
        signal = parcel.readInt(),
        networkType = parcel.readString() ?: "LTE"
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(mcc)
        parcel.writeInt(mnc)
        parcel.writeInt(lac)
        parcel.writeInt(cid)
        parcel.writeInt(psc)
        parcel.writeDouble(latitude)
        parcel.writeDouble(longitude)
        parcel.writeInt(signal)
        parcel.writeString(networkType)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CellInfoData> {
        override fun createFromParcel(parcel: Parcel): CellInfoData = CellInfoData(parcel)
        override fun newArray(size: Int): Array<CellInfoData?> = arrayOfNulls(size)
    }
}
