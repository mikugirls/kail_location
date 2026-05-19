package com.kail.location.root

import android.os.Parcel
import android.os.Parcelable

/**
 * WiFi info data parcelable for AIDL IPC
 */
data class WifiInfoData(
    val ssid: String = "",
    val bssid: String = "00:00:00:00:00:00",
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]",
    val level: Int = -50,
    val frequency: Int = 2437,
    val linkSpeed: Int = 72
) : Parcelable {
    constructor(parcel: Parcel) : this(
        ssid = parcel.readString() ?: "",
        bssid = parcel.readString() ?: "00:00:00:00:00:00",
        capabilities = parcel.readString() ?: "[WPA2-PSK-CCMP][ESS]",
        level = parcel.readInt(),
        frequency = parcel.readInt(),
        linkSpeed = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(ssid)
        parcel.writeString(bssid)
        parcel.writeString(capabilities)
        parcel.writeInt(level)
        parcel.writeInt(frequency)
        parcel.writeInt(linkSpeed)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<WifiInfoData> {
        override fun createFromParcel(parcel: Parcel): WifiInfoData = WifiInfoData(parcel)
        override fun newArray(size: Int): Array<WifiInfoData?> = arrayOfNulls(size)
    }
}
