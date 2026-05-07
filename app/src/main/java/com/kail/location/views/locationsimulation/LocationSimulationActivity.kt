package com.kail.location.views.locationsimulation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kail.location.R
import com.kail.location.views.base.BaseActivity
import com.kail.location.viewmodels.LocationSimulationViewModel
import com.kail.location.views.theme.locationTheme
import com.kail.location.views.routesimulation.RouteSimulationActivity
import com.kail.location.views.settings.SettingsActivity
import com.kail.location.utils.GoUtils
import com.kail.location.views.locationpicker.LocationPickerActivity
import com.kail.location.views.navigationsimulation.NavigationSimulationActivity

/**
 * 位置模拟页面的 Activity。
 * 承载位置模拟的 UI，并监控 ViewModel 状态以启动/停止前台服务与控制摇杆。
 * 增加了权限和 GPS 检查，确保应用功能正常。
 */
class LocationSimulationActivity : BaseActivity() {

    private val viewModel: LocationSimulationViewModel by viewModels()
    private var hasRequestedPermission = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 200
    }

    /**
     * Activity 启动回调：设置 Compose 界面与订阅状态流。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            locationTheme {
                val locationInfo by viewModel.locationInfo.collectAsState()
                val isSimulating by viewModel.isSimulating.collectAsState()
                val isJoystickEnabled by viewModel.isJoystickEnabled.collectAsState()
                val stepSimulationEnabled by viewModel.stepSimulationEnabled.collectAsState()
                val stepCadenceSpm by viewModel.stepCadenceSpm.collectAsState()
                val historyRecords by viewModel.historyRecords.collectAsState()
                val selectedRecordId by viewModel.selectedRecordId.collectAsState()
                val runMode by viewModel.runMode.collectAsState()

                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

                LocationSimulationScreen(
                    locationInfo = locationInfo,
                    isSimulating = isSimulating,
                    isJoystickEnabled = isJoystickEnabled,
                    stepSimulationEnabled = stepSimulationEnabled,
                    stepCadenceSpm = stepCadenceSpm,
                    historyRecords = historyRecords,
                    selectedRecordId = selectedRecordId,
                    onToggleSimulation = viewModel::toggleSimulation,
                    onJoystickToggle = viewModel::setJoystickEnabled,
                    onStepSimulationToggle = viewModel::setStepSimulationEnabled,
                    onStepCadenceChange = viewModel::setStepCadenceSpm,
                    onRecordSelect = viewModel::selectRecord,
                    onRecordDelete = viewModel::deleteRecord,
                    onRecordRename = viewModel::renameRecord,
                    runMode = runMode,
                    onRunModeChange = { viewModel.setRunMode(it) },
                    onNavigate = { id ->
                        when (id) {
                            R.id.nav_location_simulation -> {
                                // Already here
                            }
                            R.id.nav_route_simulation -> {
                                startActivity(Intent(this, RouteSimulationActivity::class.java))
                            }
                            R.id.nav_navigation_simulation -> {
                                startActivity(Intent(this, NavigationSimulationActivity::class.java))
                            }
                            R.id.nav_nfc_simulation -> {
                                startActivity(Intent(this, com.kail.location.views.nfcsimulation.NfcSimulationActivity::class.java))
                            }
                            R.id.nav_settings -> {
                                startActivity(Intent(this, SettingsActivity::class.java))
                            }
                            R.id.nav_sponsor -> {
                                startActivity(Intent(this, com.kail.location.views.sponsor.SponsorActivity::class.java))
                            }
                            R.id.nav_dev -> {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this, getString(R.string.app_error_dev), Toast.LENGTH_SHORT).show()
                                }
                            }
                            R.id.nav_contact -> {
                                try {
                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:kailkali23143@gmail.com")
                                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.nav_menu_contact))
                                    }
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this, getString(R.string.error_cannot_open_email), Toast.LENGTH_SHORT).show()
                                }
                            }
                            R.id.nav_source_code -> {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/noellegazelle6/kail_location"))
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this, getString(R.string.error_cannot_open_browser), Toast.LENGTH_SHORT).show()
                                }
                            }
                            R.id.nav_update -> {
                                viewModel.checkUpdate(this)
                            }
                            else -> {
                                Toast.makeText(this, getString(R.string.error_under_development), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAddLocation = {
                        startActivity(Intent(this, LocationPickerActivity::class.java))
                    },
                    appVersion = version,
                    onCheckUpdate = { viewModel.checkUpdate(this) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadRecords()
        // 每次回到页面都检查权限和 GPS 状态
        checkPermissionsAndGps()
    }

    /**
     * 检查定位权限和 GPS 状态，若未满足则请求权限或引导设置
     */
    private fun checkPermissionsAndGps() {
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasLocationPermission = hasFineLocation || hasCoarseLocation

        // 权限缺失处理
        if (!hasLocationPermission) {
            if (!hasRequestedPermission) {
                requestLocationPermission()
            } else {
                showPermissionSettingsDialog()
            }
            return
        }

        // 权限已授予，检查 GPS
        if (!GoUtils.isGpsOpened(this)) {
            showGpsSettingsDialog()
        }
    }

    /**
     * 请求定位权限
     */
    private fun requestLocationPermission() {
        hasRequestedPermission = true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * 显示引导用户开启 GPS 的弹窗
     */
    private fun showGpsSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.app_error_permission))
            .setPositiveButton(getString(R.string.goutils_settings)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开定位设置，请手动开启", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * 显示引导用户授予权限的弹窗（跳转到应用设置）
     */
    private fun showPermissionSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.app_error_permission))
            .setPositiveButton(getString(R.string.goutils_settings)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "无法打开设置页面，请手动开启", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * 权限请求结果回调
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val hasFineLocation = grantResults.getOrNull(permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = grantResults.getOrNull(permissions.indexOf(Manifest.permission.ACCESS_COARSE_LOCATION)) == PackageManager.PERMISSION_GRANTED
            if (hasFineLocation || hasCoarseLocation) {
                // 授予后再次检查 GPS
                if (!GoUtils.isGpsOpened(this)) {
                    showGpsSettingsDialog()
                }
            } else {
                showPermissionSettingsDialog()
            }
        }
    }
}
