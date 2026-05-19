package com.kail.location.repositories

import android.app.Application
import android.content.Intent
import android.os.Build
import com.kail.location.service.Root.ServiceGoRoot
import com.kail.location.service.Developer.ServiceGoDeveloper
import com.kail.location.service.Xposed.ServiceGoXposed
import com.kail.location.views.locationpicker.LocationPickerActivity

class RootMockRepository(private val app: Application) {
    private fun getServiceClass(mode: String) = when (mode) {
        "root" -> ServiceGoRoot::class.java
        "xposed" -> ServiceGoXposed::class.java
        else -> ServiceGoDeveloper::class.java
    }

    private fun getExtraName(mode: String, rootName: String, devName: String) =
        if (mode == "root" || mode == "xposed") rootName else devName

    fun startMock(lat: Double, lng: Double, runMode: String) {
        val serviceClass = getServiceClass(runMode)
        val extraCoordType = getExtraName(runMode, ServiceGoRoot.EXTRA_COORD_TYPE, ServiceGoDeveloper.EXTRA_COORD_TYPE)
        val intent = Intent(app, serviceClass)
        intent.putExtra(extraCoordType, "BD09")
        intent.putExtra(LocationPickerActivity.LAT_MSG_ID, lat)
        intent.putExtra(LocationPickerActivity.LNG_MSG_ID, lng)
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun stopMock(runMode: String) {
        val serviceClass = getServiceClass(runMode)
        val intent = Intent(app, serviceClass)
        app.stopService(intent)
    }
}
