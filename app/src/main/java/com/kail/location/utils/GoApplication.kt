package com.kail.location.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.preference.PreferenceManager
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import com.kail.location.sandbox.SandboxManager
import com.kail.location.sandbox.SandboxSettingsManager
import com.kail.location.auth.AuthManager
import com.baidu.mapapi.SDKInitializer
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType

class GoApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        const val APP_NAME = "KailLocation"
        private const val KEY_BAIDU_MAP_KEY = "setting_baidu_map_key"

        private fun isMainProcess(context: Context): Boolean {
            val packageName = context.packageName
            val processName = try {
                val activityThread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName")
                    .invoke(null) as String?
                activityThread
            } catch (e: Exception) {
                null
            }
            return processName == null || processName == packageName
        }
    }

    private var currentActivity: Activity? = null
    private var sandboxInitialized = false
    private var isMainProc = true

    private fun writeCrashToFile(ex: Throwable) {
        try {
            val logPath = getExternalFilesDir("Logs") ?: return
            val crashFile = java.io.File(logPath, "crash_${System.currentTimeMillis()}.txt")
            val pw = java.io.PrintWriter(crashFile)
            ex.printStackTrace(pw)
            pw.flush()
            pw.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        isMainProc = isMainProcess(this)

        try {
            BlackBoxCore.get().closeCodeInit()
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)

            BlackBoxCore.get().doAttachBaseContext(
                this,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = packageName
                    override fun isHideRoot(): Boolean = false
                    override fun isEnableDaemonService(): Boolean = false
                    override fun isUseVpnNetwork(): Boolean = false
                    override fun isDisableFlagSecure(): Boolean = false
                    override fun requestInstallPackage(file: File?, userId: Int): Boolean = false
                }
            )

            BlackBoxCore.get().onAfterMainApplicationAttach(this, base)
            sandboxInitialized = true
        } catch (e: Exception) {
            android.util.Log.e("GoApplication", "Failed to init BlackBoxCore: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (sandboxInitialized) {
            try {
                BlackBoxCore.get().doCreate()
                SandboxManager.init(this)
                SandboxSettingsManager.init(this)
                android.util.Log.d("GoApplication", "BlackBoxCore initialized, isMain=${BlackBoxCore.get().isMainProcess()}, isServer=${BlackBoxCore.get().isServerProcess()}")
            } catch (e: Exception) {
                android.util.Log.e("GoApplication", "Failed to doCreate BlackBoxCore: ${e.message}")
            }
        }

        if (!isMainProc) {
            return
        }

        AuthManager.init(this)

        registerActivityLifecycleCallbacks(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val logEnabled = prefs.getBoolean("setting_log_enabled", false)
        android.util.Log.d("GoApplication", "Log enabled: $logEnabled")

        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashToFile(throwable)
            throwable.printStackTrace()
            mDefaultHandler?.uncaughtException(thread, throwable)
        }

        SDKInitializer.setAgreePrivacy(this, true)
        LocationClient.setAgreePrivacy(true)

        try {
            val customKey = prefs.getString(KEY_BAIDU_MAP_KEY, "")
            if (!customKey.isNullOrEmpty()) {
                SDKInitializer.setApiKey(customKey)
                LocationClient.setKey(customKey)
            }
            SDKInitializer.initialize(this)
            SDKInitializer.setCoordType(CoordType.BD09LL)
        } catch (e: Throwable) {
            KailLog.e(this, APP_NAME, "Baidu Map SDK init failed: ${e.message}")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
