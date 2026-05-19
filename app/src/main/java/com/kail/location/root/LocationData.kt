package com.kail.location.root

import android.os.Parcel
import android.os.Parcelable

/**
 * Location data parcelable for AIDL IPC
 */
data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Float = 25.0f,
    val speed: Float = 0.0f,
    val bearing: Float = 0.0f,
    val provider: String = "gps",
    val time: Long = System.currentTimeMillis()
) : Parcelable {
    constructor(parcel: Parcel) : this(
        latitude = parcel.readDouble(),
        longitude = parcel.readDouble(),
        altitude = parcel.readDouble(),
        accuracy = parcel.readFloat(),
        speed = parcel.readFloat(),
        bearing = parcel.readFloat(),
        provider = parcel.readString() ?: "gps",
        time = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeDouble(latitude)
        parcel.writeDouble(longitude)
        parcel.writeDouble(altitude)
        parcel.writeFloat(accuracy)
        parcel.writeFloat(speed)
        parcel.writeFloat(bearing)
        parcel.writeString(provider)
        parcel.writeLong(time)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<LocationData> {
        override fun createFromParcel(parcel: Parcel): LocationData = LocationData(parcel)
        override fun newArray(size: Int): Array<LocationData?> = arrayOfNulls(size)
    }
}
