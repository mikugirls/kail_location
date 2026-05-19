package com.kail.location.service.Xposed

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.kail.location.R
import com.kail.location.geo.GeoPredict
import com.kail.location.utils.service.ServiceConstants
import com.kail.location.utils.service.ServiceNotificationHelper
import com.kail.location.utils.service.RouteEngine
import com.kail.location.utils.GoUtils
import com.kail.location.utils.KailLog
import com.kail.location.utils.MapUtils
import com.kail.location.root.NativeSensorHook
import com.kail.location.viewmodels.JoystickViewModel
import com.kail.location.views.joystick.JoystickWindowManager
import com.kail.location.views.locationpicker.LocationPickerActivity

class ServiceGoXposed : Service() {

    private var mCurLat = ServiceConstants.DEFAULT_LAT
    private var mCurLng = ServiceConstants.DEFAULT_LNG
    private var mCurAlt = ServiceConstants.DEFAULT_ALT
    private var mCurBea = ServiceConstants.DEFAULT_BEA
    private var mSpeed = 1.2

    private lateinit var mLocManager: LocationManager
    private lateinit var mLocHandlerThread: HandlerThread
    private lateinit var mLocHandler: Handler
    private var isStop = false

    private lateinit var mJoystickManager: JoystickWindowManager
    private lateinit var mJoystickViewModel: JoystickViewModel

    private val mBinder = ServiceGoXposedBinder()
    private val mRouteEngine = RouteEngine()
    private val mNotificationHelper by lazy {
        ServiceNotificationHelper(
            service = this,
            channelId = "SERVICE_GO_XPOSED_NOTE",
            channelName = "SERVICE_GO_XPOSED_NOTE",
            noteId = SERVICE_GO_NOTE_ID,
            onShowJoystick = { mJoystickManager.show() },
            onHideJoystick = { mJoystickManager.hide() }
        )
    }

    private var locationLoopStarted: Boolean = false
    private var speedFluctuation: Boolean = false
    private var stepEnabled: Boolean = false
    private var stepCadence: Float = 120f
    private var stepMode: Int = 0
    private var stepScheme: Int = 0

    private var xposedKey: String? = null

    companion object {
        const val DEFAULT_LAT = ServiceConstants.DEFAULT_LAT
        const val DEFAULT_LNG = ServiceConstants.DEFAULT_LNG
        const val DEFAULT_ALT = ServiceConstants.DEFAULT_ALT
        const val DEFAULT_BEA = ServiceConstants.DEFAULT_BEA

        private const val HANDLER_MSG_ID = 0
        private const val SERVICE_GO_HANDLER_NAME = "ServiceGoXposedLocation"
        private const val SERVICE_GO_NOTE_ID = 2
        const val SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = ServiceNotificationHelper.ACTION_JOYSTICK_SHOW
        const val SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = ServiceNotificationHelper.ACTION_JOYSTICK_HIDE

        const val EXTRA_ROUTE_POINTS = ServiceConstants.EXTRA_ROUTE_POINTS
        const val EXTRA_ROUTE_LOOP = ServiceConstants.EXTRA_ROUTE_LOOP
        const val EXTRA_JOYSTICK_ENABLED = ServiceConstants.EXTRA_JOYSTICK_ENABLED
        const val EXTRA_ROUTE_SPEED = ServiceConstants.EXTRA_ROUTE_SPEED
        const val EXTRA_COORD_TYPE = ServiceConstants.EXTRA_COORD_TYPE
        const val EXTRA_CONTROL_ACTION = ServiceConstants.EXTRA_CONTROL_ACTION
        const val EXTRA_SPEED_FLUCTUATION = ServiceConstants.EXTRA_SPEED_FLUCTUATION
        const val EXTRA_SEEK_RATIO = ServiceConstants.EXTRA_SEEK_RATIO
        const val EXTRA_STEP_ENABLED = "EXTRA_STEP_ENABLED"
        const val EXTRA_STEP_FREQ = "EXTRA_STEP_FREQ"
        const val EXTRA_STEP_MODE = "EXTRA_STEP_MODE"
        const val EXTRA_STEP_SCHEME = "EXTRA_STEP_SCHEME"
        const val EXTRA_WIFI_ONLY = "EXTRA_WIFI_ONLY"
        const val EXTRA_CELL_ONLY = "EXTRA_CELL_ONLY"
        const val CONTROL_PAUSE = ServiceConstants.CONTROL_PAUSE
        const val CONTROL_RESUME = ServiceConstants.CONTROL_RESUME
        const val CONTROL_STOP = ServiceConstants.CONTROL_STOP
        const val CONTROL_SEEK = ServiceConstants.CONTROL_SEEK
        const val CONTROL_SET_SPEED = ServiceConstants.CONTROL_SET_SPEED
        const val CONTROL_SET_SPEED_FLUCTUATION = ServiceConstants.CONTROL_SET_SPEED_FLUCTUATION
        const val CONTROL_SET_STEP = "set_step"
        const val COORD_WGS84 = ServiceConstants.COORD_WGS84
        const val COORD_BD09 = ServiceConstants.COORD_BD09
        const val COORD_GCJ02 = ServiceConstants.COORD_GCJ02
        const val ACTION_STATUS_CHANGED = ServiceConstants.ACTION_STATUS_CHANGED
        const val EXTRA_IS_SIMULATING = ServiceConstants.EXTRA_IS_SIMULATING
        const val EXTRA_IS_PAUSED = ServiceConstants.EXTRA_IS_PAUSED
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_SIMULATING, locationLoopStarted && !isStop)
            putExtra(EXTRA_IS_PAUSED, isStop)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent): IBinder = mBinder

    override fun onCreate() {
        super.onCreate()
        KailLog.i(this, "ServiceGoXposed", "onCreate started")
        try {
            mNotificationHelper.initAndStartForeground()
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGoXposed", "Error in initNotification: ${e.message}")
        }
        try {
            mLocManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGoXposed", "Error in LocationManager init: ${e.message}")
        }
        try {
            initGoLocation()
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGoXposed", "Error in initGoLocation: ${e.message}")
        }
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val joystickEnabledPref = prefs.getBoolean("setting_joystick_enabled", false)
            initJoyStick()
            if (joystickEnabledPref) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    mJoystickManager.show()
                }
            } else {
                mJoystickManager.hide()
            }
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGoXposed", "Error initializing JoyStick: ${e.message}")
            GoUtils.DisplayToast(applicationContext, getString(R.string.service_overlay_failed, e.message))
        }
        broadcastStatus()
        KailLog.i(this, "ServiceGoXposed", "onCreate finished")
    }

    private fun exchangeKey(): Boolean {
        return try {
            val extras = Bundle()
            val success = mLocManager.sendExtraCommand("kail", "exchange_key", extras)
            if (success) {
                xposedKey = extras.getString("key")
                KailLog.i(this, "ServiceGoXposed", "Key exchanged successfully")
            }
            success
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGoXposed", "exchangeKey failed: ${e.message}")
            false
        }
    }

    private fun sendXposedCommand(commandId: String, extras: Bundle = Bundle()): Boolean {
        val key = xposedKey
        if (key == null) {
            KailLog.e(this, "ServiceGoXposed", "No Xposed key available")
            return false
        }
        return try {
            extras.putString("command_id", commandId)
            val result = mLocManager.sendExtraCommand("kail", key, extras)
            KailLog.i(this, "ServiceGoXposed", "sendXposedCommand '$commandId' -> $result, key=$key")
            result
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGoXposed", "sendXposedCommand $commandId failed: ${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val ctrl = intent.getStringExtra(EXTRA_CONTROL_ACTION)
            if (!ctrl.isNullOrBlank()) {
                when (ctrl) {
                    CONTROL_PAUSE -> {
                        try {
                            isStop = true
                            mJoystickManager.setRoutePauseState(true)
                            broadcastStatus()
                        } catch (e: Exception) {
                            KailLog.log(this, "ServiceGoXposed", "Pause error: ${e.message}", isHighFrequency = false)
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_RESUME -> {
                        try {
                            isStop = false
                            mJoystickManager.setRoutePauseState(false)
                            broadcastStatus()
                        } catch (e: Exception) {
                            KailLog.log(this, "ServiceGoXposed", "Resume error: ${e.message}", isHighFrequency = false)
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_STOP -> {
                        try {
                            stopSelf()
                            broadcastStatus()
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGoXposed", "stop error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SEEK -> {
                        val ratio = intent.getFloatExtra(EXTRA_SEEK_RATIO, 0f).coerceIn(0f, 1f)
                        mRouteEngine.seekToRatio(ratio)
                        mCurLng = mRouteEngine.currentLng
                        mCurLat = mRouteEngine.currentLat
                        mCurBea = mRouteEngine.currentBea
                        updateJoystickStatus()
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_SPEED -> {
                        val kmh = intent.getFloatExtra(EXTRA_ROUTE_SPEED, (mSpeed * 3.6).toFloat())
                        mSpeed = kmh.toDouble() / 3.6
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_SPEED_FLUCTUATION -> {
                        speedFluctuation = intent.getBooleanExtra(EXTRA_SPEED_FLUCTUATION, speedFluctuation)
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_STEP -> {
                        stepEnabled = intent.getBooleanExtra(EXTRA_STEP_ENABLED, stepEnabled)
                        stepCadence = intent.getFloatExtra(EXTRA_STEP_FREQ, stepCadence)
                        stepMode = intent.getIntExtra(EXTRA_STEP_MODE, stepMode)
                        stepScheme = intent.getIntExtra(EXTRA_STEP_SCHEME, stepScheme)
                        applyStepSimulation()
                        return super.onStartCommand(intent, flags, startId)
                    }
                }
            }
        }

        mNotificationHelper.startForegroundIfReady()

        if (intent != null) {
            val coordType = intent.getStringExtra(EXTRA_COORD_TYPE) ?: COORD_BD09
            mCurLng = intent.getDoubleExtra(LocationPickerActivity.LNG_MSG_ID, DEFAULT_LNG)
            mCurLat = intent.getDoubleExtra(LocationPickerActivity.LAT_MSG_ID, DEFAULT_LAT)
            try {
                when (coordType) {
                    COORD_WGS84 -> { /* keep */ }
                    COORD_GCJ02 -> {
                        val wgs = MapUtils.gcj02towgs84(mCurLng, mCurLat)
                        mCurLng = wgs[0]
                        mCurLat = wgs[1]
                    }
                    else -> {
                        val wgs = MapUtils.bd2wgs(mCurLng, mCurLat)
                        mCurLng = wgs[0]
                        mCurLat = wgs[1]
                    }
                }
            } catch (_: Exception) {}
            mCurAlt = intent.getDoubleExtra(LocationPickerActivity.ALT_MSG_ID, DEFAULT_ALT)
            val joystickEnabled = intent.getBooleanExtra(EXTRA_JOYSTICK_ENABLED, false)
            mSpeed = intent.getFloatExtra(EXTRA_ROUTE_SPEED, mSpeed.toFloat()).toDouble() / 3.6

            val routeArray = intent.getDoubleArrayExtra(EXTRA_ROUTE_POINTS)
            if (routeArray != null && routeArray.size >= 2) {
                mRouteEngine.setupFromArray(routeArray, coordType)
                mRouteEngine.setLoop(intent.getBooleanExtra(EXTRA_ROUTE_LOOP, false))
            }

            // Read step simulation parameters
            stepEnabled = intent.getBooleanExtra(EXTRA_STEP_ENABLED, stepEnabled)
            stepCadence = intent.getFloatExtra(EXTRA_STEP_FREQ, stepCadence)
            stepMode = intent.getIntExtra(EXTRA_STEP_MODE, stepMode)
            stepScheme = intent.getIntExtra(EXTRA_STEP_SCHEME, stepScheme)

            KailLog.i(this, "ServiceGoXposed", "onStartCommand received lat=$mCurLat, lng=$mCurLng")

            // Exchange key and start Xposed module
            if (this::mLocHandler.isInitialized) {
                mLocHandler.post {
                    if (exchangeKey()) {
                        startXposedMock()
                    }
                }
            }

            startLocationLoop()

            try {
                mJoystickViewModel.setCurrentPosition(mCurLng, mCurLat, mCurAlt)
                if (joystickEnabled) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                        if (mRouteEngine.isActive) {
                            mJoystickManager.showRouteControl(mSpeed * 3.6)
                        } else {
                            mJoystickManager.show()
                        }
                    } else {
                        GoUtils.DisplayToast(applicationContext, getString(R.string.service_grant_overlay))
                    }
                } else {
                    mJoystickManager.hide()
                }
            } catch (e: Exception) {
                KailLog.e(this, "ServiceGoXposed", "Error setting current position or showing joystick: ${e.message}")
            }
        }

        return START_STICKY
    }

    private fun startXposedMock() {
        // Start the Xposed module
        val startExtras = Bundle().apply {
            putDouble("altitude", mCurAlt)
        }
        if (!sendXposedCommand("start", startExtras)) {
            KailLog.e(this, "ServiceGoXposed", "Failed to start Xposed mock")
            return
        }

        // Update location
        val locExtras = Bundle().apply {
            putDouble("lat", mCurLat)
            putDouble("lon", mCurLng)
        }
        sendXposedCommand("update_location", locExtras)

        // Apply config from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val configExtras = Bundle().apply {
            putBoolean("enableMockGnss", prefs.getBoolean("setting_gps_satellite_sim", true))
            putBoolean("disableFusedLocation", prefs.getBoolean("setting_disable_fused", false))
            putBoolean("hideMock", prefs.getBoolean("setting_hide_mock", false))
            putBoolean("hookWifi", prefs.getBoolean("setting_disable_wifi_scan", false))
            putBoolean("needDowngradeToCdma", prefs.getBoolean("setting_downgrade_cdma", false))
            putBoolean("loopBroadcastLocation", prefs.getBoolean("setting_anti_pullback", false))
            putInt("minSatellites", prefs.getString("setting_min_satellites", "12")?.toIntOrNull() ?: 12)
            putFloat("accuracy", 25.0f)
            putInt("reportIntervalMs", prefs.getString("setting_report_interval", "100")?.toIntOrNull() ?: 100)
        }
        sendXposedCommand("set_config", configExtras)

        // Apply step simulation settings
        applyStepSimulation()

        KailLog.i(this, "ServiceGoXposed", "Xposed mock started")
    }

    private fun applyStepSimulation() {
        KailLog.i(this, "ServiceGoXposed", ">>> applyStepSimulation START: enabled=$stepEnabled, cadence=$stepCadence, mode=$stepMode, scheme=$stepScheme")
        val selinuxRestored = {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "setenforce 1")).waitFor()
                KailLog.i(this, "ServiceGoXposed", "SELinux restored to enforcing")
            } catch (e: Exception) {
                KailLog.w(this, "ServiceGoXposed", "Failed to restore SELinux: ${e.message}")
            }
        }

        try {
            if (stepEnabled) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "setenforce 0")).waitFor()
                KailLog.i(this, "ServiceGoXposed", "SELinux set to permissive for step simulation")

                copyNativeSoForStepSimulation()
                resolveAndCacheSensorOffsets()

                val loadOk = waitForLibraryLoaded()
                if (!loadOk) {
                    KailLog.e(this, "ServiceGoXposed", "SO not loaded in system_server")
                }
            } else {
                KailLog.i(this, "ServiceGoXposed", ">>> Step disabled, sending disable command")
                NativeSensorHook.setStepSimEnabled(false)
            }

            val stepExtras = Bundle().apply {
                putBoolean("enabled", stepEnabled)
                putInt("mode", stepMode)
                putInt("scheme", stepScheme)
                putFloat("cadence", stepCadence)
            }
            KailLog.i(this, "ServiceGoXposed", ">>> Sending set_step_enabled: enabled=$stepEnabled, cadence=$stepCadence, mode=$stepMode, scheme=$stepScheme")
            sendXposedCommand("set_step_enabled", stepExtras)

            KailLog.i(this, "ServiceGoXposed", "Step simulation applied: enabled=$stepEnabled, cadence=$stepCadence, mode=$stepMode, scheme=$stepScheme")
        } finally {
            selinuxRestored()
        }
    }

    private fun resolveAndCacheSensorOffsets() {
        val offsetCacheFile = "/data/local/tmp/kail_sensor_offsets.txt"
        val cache = java.io.File(offsetCacheFile)
        if (cache.exists()) {
            KailLog.i(this, "ServiceGoXposed", "Sensor offset cache already exists")
            return
        }

        try {
            val readelfCmds = listOf("toybox readelf", "readelf", "/system/bin/toybox readelf")
            var sendOffset = ""
            var convertOffset = ""

            for (cmd in readelfCmds) {
                val test = runSuCommand("$cmd 2>&1")
                if (test.contains("not found") || (test.isEmpty() && !cmd.startsWith("/"))) continue

                if (sendOffset.isEmpty()) {
                    val out = runSuCommand("$cmd -Ws /system/lib64/libsensor.so 2>/dev/null | grep _ZN7android7BitTube11sendObjects")
                    if (out.isNotEmpty()) {
                        sendOffset = out.trim().lines().firstOrNull()?.trim()
                            ?.split(Regex("\\s+"))
                            ?.firstOrNull { it.matches(Regex("^[0-9a-fA-F]{8,16}$")) } ?: ""
                    }
                }
                if (convertOffset.isEmpty()) {
                    val out = runSuCommand("$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors14implementation20convertToSensorEvent[^4V1]'")
                    val outV1 = runSuCommand("$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEvent'")
                    val raw = if (out.isNotEmpty()) out else outV1
                    if (raw.isNotEmpty()) {
                        convertOffset = raw.trim().lines().firstOrNull()?.trim()
                            ?.split(Regex("\\s+"))
                            ?.firstOrNull { it.matches(Regex("^[0-9a-fA-F]{8,16}$")) } ?: ""
                    }
                }
                if (sendOffset.isNotEmpty() && convertOffset.isNotEmpty()) break
            }

            if (sendOffset.isNotEmpty() && convertOffset.isNotEmpty()) {
                val sendHex = if (sendOffset.startsWith("0x")) sendOffset else "0x$sendOffset"
                val convertHex = if (convertOffset.startsWith("0x")) convertOffset else "0x$convertOffset"
                val content = "send_objects=$sendHex\nconvert_to_sensor_event=$convertHex\n"
                runSuCommand("echo '$content' > $offsetCacheFile")
                runSuCommand("chmod 777 $offsetCacheFile")
                KailLog.i(this, "ServiceGoXposed", "Cached sensor offsets: send=$sendHex, convert=$convertHex")
            } else {
                KailLog.w(this, "ServiceGoXposed", "Could not resolve sensor offsets, step sim may not work")
            }
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGoXposed", "Failed to resolve sensor offsets: ${e.message}")
        }
    }

    private fun runSuCommand(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }

    private fun waitForLibraryLoaded(): Boolean {
        val destSo = java.io.File("/data/local/kail-lib/libkail_native_hook.so")
        if (!destSo.exists()) {
            KailLog.e(this, "ServiceGoXposed", "SO file not found at ${destSo.absolutePath}")
            return false
        }

        val loadExtras = Bundle().apply {
            putString("path", destSo.absolutePath)
        }

        var retries = 3
        while (retries > 0) {
            val result = sendXposedCommand("load_library", loadExtras)
            if (result) {
                KailLog.i(this, "ServiceGoXposed", "SO loaded successfully in system_server")
                return true
            }
            retries--
            if (retries > 0) {
                Thread.sleep(500)
            }
        }

        KailLog.e(this, "ServiceGoXposed", "Failed to load SO after retries")
        return false
    }

    private fun copyNativeSoForStepSimulation() {
        val destDir = java.io.File("/data/local/kail-lib")
        val destSo = java.io.File(destDir, "libkail_native_hook.so")
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        try {
            if (!destDir.exists()) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p $destDir && chmod 777 $destDir")).waitFor()
            }

            val apkPath = applicationInfo.sourceDir
            val soInApk = "lib/$abi/libkail_native_hook.so"

            val apkFile = java.util.zip.ZipFile(apkPath)
            val entry = apkFile.getEntry(soInApk)

            if (entry != null) {
                apkFile.getInputStream(entry).use { input ->
                    destSo.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 777 ${destSo.absolutePath}")).waitFor()
                KailLog.i(this, "ServiceGoXposed", "Copied $soInApk to ${destSo.absolutePath}")
            } else {
                KailLog.w(this, "ServiceGoXposed", "$soInApk not found in APK")
            }
            apkFile.close()
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGoXposed", "Failed to copy native SO: ${e.message}")
        }
    }

    override fun onDestroy() {
        KailLog.i(this, "ServiceGoXposed", "onDestroy started")
        try {
            broadcastStatusStopped()
            isStop = true
            locationLoopStarted = false
            if (this::mLocHandler.isInitialized) mLocHandler.removeMessages(HANDLER_MSG_ID)
            if (this::mLocHandlerThread.isInitialized) mLocHandlerThread.quit()
            if (this::mJoystickManager.isInitialized) mJoystickManager.destroy()

            // Stop Xposed module
            sendXposedCommand("stop")

            mNotificationHelper.stopForeground()
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGoXposed", "Error in onDestroy: ${e.message}")
        }
        super.onDestroy()
        KailLog.i(this, "ServiceGoXposed", "onDestroy finished")
    }

    private fun broadcastStatusStopped() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_SIMULATING, false)
            putExtra(EXTRA_IS_PAUSED, false)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun initJoyStick() {
        mJoystickViewModel = JoystickViewModel(application)
        mJoystickManager = JoystickWindowManager(this, mJoystickViewModel, object : JoystickViewModel.ActionListener {
            override fun onMoveInfo(speed: Double, disLng: Double, disLat: Double, angle: Double) {
                mSpeed = speed
                val next = GeoPredict.nextByDisplacementKm(mCurLng, mCurLat, disLng, disLat)
                mCurLng = next.first
                mCurLat = next.second
                mCurBea = angle.toFloat()
            }

            override fun onPositionInfo(lng: Double, lat: Double, alt: Double) {
                mCurLng = lng
                mCurLat = lat
                mCurAlt = alt
            }

            override fun onRouteControl(action: String) {
                val intent = Intent(this@ServiceGoXposed, ServiceGoXposed::class.java)
                intent.putExtra(EXTRA_CONTROL_ACTION, action)
                startService(intent)
            }

            override fun onRouteSeek(progress: Float) {
                val intent = Intent(this@ServiceGoXposed, ServiceGoXposed::class.java)
                intent.putExtra(EXTRA_CONTROL_ACTION, CONTROL_SEEK)
                intent.putExtra(EXTRA_SEEK_RATIO, progress)
                startService(intent)
            }

            override fun onRouteSpeedChange(speed: Double) {
                mSpeed = speed / 3.6
            }
        })
    }

    private fun initGoLocation() {
        mLocHandlerThread = HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND)
        mLocHandlerThread.start()
        mLocHandler = object : Handler(mLocHandlerThread.looper) {
            override fun handleMessage(msg: Message) {
                try {
                    Thread.sleep(50)
                    if (!isStop) {
                        if (mRouteEngine.isActive) {
                            val speedForStep = if (speedFluctuation) {
                                GeoPredict.randomInRangeWithMean(mSpeed * 0.5, mSpeed * 1.5, mSpeed)
                            } else {
                                mSpeed
                            }
                            mRouteEngine.advance(speedForStep * 0.185)
                            mCurLng = mRouteEngine.currentLng
                            mCurLat = mRouteEngine.currentLat
                            mCurBea = mRouteEngine.currentBea
                            updateJoystickStatus()
                        }

                        // Update Xposed module with new location
                        val locExtras = Bundle().apply {
                            putDouble("lat", mCurLat)
                            putDouble("lon", mCurLng)
                        }
                        sendXposedCommand("update_location", locExtras)
                    }
                    sendEmptyMessage(HANDLER_MSG_ID)
                } catch (e: InterruptedException) {
                    KailLog.e(this@ServiceGoXposed, "ServiceGoXposed", "handleMessage interrupted: ${e.message}")
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    KailLog.e(this@ServiceGoXposed, "ServiceGoXposed", "handleMessage exception: ${e.message}")
                    if (!isStop) sendEmptyMessageDelayed(HANDLER_MSG_ID, 100)
                }
            }
        }
    }

    private fun startLocationLoop() {
        if (!this::mLocHandler.isInitialized) return
        isStop = false
        if (locationLoopStarted) return
        locationLoopStarted = true
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID)
    }

    private fun updateJoystickStatus() {
        if (this::mJoystickManager.isInitialized && mRouteEngine.isActive) {
            val status = mRouteEngine.buildStatusString()
            if (status != null) {
                mJoystickManager.updateRouteStatus(mRouteEngine.progressRatio, status.first, status.second)
            }
        }
    }

    inner class ServiceGoXposedBinder : Binder() {
        fun getService(): ServiceGoXposed = this@ServiceGoXposed
    }
}
