package com.kail.location.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 独立模拟页面的 ViewModel
 * 只负责选择目标APP和要模拟的模式类型
 * 具体的模拟值（位置/WiFi/基站等）在各自界面设置
 */
class IndependentSimulationViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    companion object {
        const val KEY_INDEPENDENT_ENABLED = "independent_enabled"
        const val KEY_INDEPENDENT_TARGET_PACKAGES = "independent_target_packages"
        // Mode flags: location, route, wifi, cell, gnss, jitter
        const val KEY_INDEPENDENT_MODE_LOCATION = "independent_mode_location"
        const val KEY_INDEPENDENT_MODE_ROUTE = "independent_mode_route"
        const val KEY_INDEPENDENT_MODE_WIFI = "independent_mode_wifi"
        const val KEY_INDEPENDENT_MODE_CELL = "independent_mode_cell"
        const val KEY_INDEPENDENT_MODE_GNSS = "independent_mode_gnss"
        const val KEY_INDEPENDENT_MODE_JITTER = "independent_mode_jitter"
    }

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _targetPackages = MutableStateFlow(prefs.getString(KEY_INDEPENDENT_TARGET_PACKAGES, "") ?: "")
    val targetPackages: StateFlow<String> = _targetPackages.asStateFlow()

    // Mode selections
    private val _modeLocation = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_MODE_LOCATION, true))
    val modeLocation: StateFlow<Boolean> = _modeLocation.asStateFlow()

    private val _modeRoute = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_MODE_ROUTE, false))
    val modeRoute: StateFlow<Boolean> = _modeRoute.asStateFlow()

    private val _modeWifi = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_MODE_WIFI, false))
    val modeWifi: StateFlow<Boolean> = _modeWifi.asStateFlow()

    private val _modeCell = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_MODE_CELL, false))
    val modeCell: StateFlow<Boolean> = _modeCell.asStateFlow()

    private val _modeGnss = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_MODE_GNSS, true))
    val modeGnss: StateFlow<Boolean> = _modeGnss.asStateFlow()

    private val _modeJitter = MutableStateFlow(prefs.getBoolean(KEY_INDEPENDENT_MODE_JITTER, false))
    val modeJitter: StateFlow<Boolean> = _modeJitter.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_ENABLED, value).apply()
        _isEnabled.value = value
    }

    fun setTargetPackages(value: String) {
        prefs.edit().putString(KEY_INDEPENDENT_TARGET_PACKAGES, value).apply()
        _targetPackages.value = value
    }

    fun setModeLocation(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_MODE_LOCATION, value).apply()
        _modeLocation.value = value
    }

    fun setModeRoute(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_MODE_ROUTE, value).apply()
        _modeRoute.value = value
    }

    fun setModeWifi(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_MODE_WIFI, value).apply()
        _modeWifi.value = value
    }

    fun setModeCell(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_MODE_CELL, value).apply()
        _modeCell.value = value
    }

    fun setModeGnss(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_MODE_GNSS, value).apply()
        _modeGnss.value = value
    }

    fun setModeJitter(value: Boolean) {
        prefs.edit().putBoolean(KEY_INDEPENDENT_MODE_JITTER, value).apply()
        _modeJitter.value = value
    }

    /** Check if any mode is selected */
    fun hasAnyModeSelected(): Boolean {
        return _modeLocation.value || _modeRoute.value || _modeWifi.value ||
               _modeCell.value || _modeGnss.value || _modeJitter.value
    }
}
