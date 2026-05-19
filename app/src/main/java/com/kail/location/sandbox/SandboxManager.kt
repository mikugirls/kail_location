package com.kail.location.sandbox

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import java.io.File

/**
 * 沙盒管理器 - 提供给宿主应用使用的简化 API。
 */
object SandboxManager {

    private var hostContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        hostContext = context.applicationContext
    }

    data class SandboxAppInfo(
        val name: String,
        val icon: Drawable?,
        val packageName: String,
        val sourceDir: String
    )

    data class SystemAppInfo(
        val name: String,
        val icon: Drawable?,
        val packageName: String,
        val sourceDir: String
    )

    /**
     * 获取沙盒中已安装的应用列表。
     */
    fun getSandboxApps(userId: Int = 0): List<SandboxAppInfo> {
        return try {
            val installedApps = BlackBoxCore.get().getInstalledApplications(0, userId)
            android.util.Log.d("SandboxManager", "Found ${installedApps.size} sandbox apps")
            installedApps.map { appInfo ->
                SandboxAppInfo(
                    name = safeLoadAppLabel(appInfo),
                    icon = safeLoadAppIcon(appInfo),
                    packageName = appInfo.packageName,
                    sourceDir = appInfo.sourceDir
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SandboxManager", "Error getting sandbox apps: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取系统中可克隆的应用列表。
     */
    fun getSystemApps(): List<SystemAppInfo> {
        return try {
            val ctx = hostContext ?: BlackBoxCore.getContext()
            val pm = ctx.packageManager
            val installedApplications = pm.getInstalledApplications(0)
            val hostPkg = BlackBoxCore.getHostPkg()
            android.util.Log.d("SandboxManager", "hostContext=$hostContext, pm=$pm")
            android.util.Log.d("SandboxManager", "Found ${installedApplications.size} installed apps, hostPkg=$hostPkg")
            if (installedApplications.isNotEmpty()) {
                android.util.Log.d("SandboxManager", "First app: ${installedApplications[0].packageName}")
            }
            val result = installedApplications
                .filter { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isHost = app.packageName == hostPkg
                    val isInstalled = BlackBoxCore.get().isInstalled(app.packageName, 0)
                    val isAbiSupported = AbiUtils.isSupport(File(app.sourceDir))
                    if (!isSystem && !isHost && !isInstalled && isAbiSupported) {
                        android.util.Log.d("SandboxManager", "  + ${app.packageName} (cloneable)")
                    }
                    !isSystem && !isHost && !isInstalled && isAbiSupported
                }
                .map { app ->
                    SystemAppInfo(
                        name = safeLoadSystemAppLabel(app),
                        icon = safeLoadSystemAppIcon(app),
                        packageName = app.packageName,
                        sourceDir = app.sourceDir
                    )
                }
            android.util.Log.d("SandboxManager", "Filtered to ${result.size} cloneable apps")
            result
        } catch (e: Exception) {
            android.util.Log.e("SandboxManager", "Error getting system apps: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 克隆系统应用到沙盒。
     */
    fun cloneApp(packageName: String, userId: Int = 0): Pair<Boolean, String> {
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(packageName, userId)
            if (result.success) {
                Pair(true, "安装成功")
            } else {
                Pair(false, "安装失败: ${result.msg}")
            }
        } catch (e: Exception) {
            Pair(false, "安装失败: ${e.message}")
        }
    }

    /**
     * 卸载沙盒应用。
     */
    fun uninstallApp(packageName: String, userId: Int = 0): Pair<Boolean, String> {
        return try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userId)
            Pair(true, "卸载成功")
        } catch (e: Exception) {
            Pair(false, "卸载失败: ${e.message}")
        }
    }

    /**
     * 启动沙盒应用。
     */
    fun launchApp(packageName: String, userId: Int = 0): Pair<Boolean, String> {
        return try {
            val success = BlackBoxCore.get().launchApk(packageName, userId)
            if (success) {
                Pair(true, "")
            } else {
                Pair(false, "启动失败")
            }
        } catch (e: Exception) {
            Pair(false, "启动失败: ${e.message}")
        }
    }

    /**
     * 清除沙盒应用数据。
     */
    fun clearAppData(packageName: String, userId: Int = 0): Pair<Boolean, String> {
        return try {
            BlackBoxCore.get().clearPackage(packageName, userId)
            Pair(true, "清除成功")
        } catch (e: Exception) {
            Pair(false, "清除失败: ${e.message}")
        }
    }

    /**
     * 停止沙盒应用运行。
     */
    fun stopApp(packageName: String, userId: Int = 0): Pair<Boolean, String> {
        return try {
            BlackBoxCore.get().stopPackage(packageName, userId)
            Pair(true, "已停止运行")
        } catch (e: Exception) {
            Pair(false, "停止失败: ${e.message}")
        }
    }

    private fun safeLoadAppLabel(appInfo: ApplicationInfo): String {
        return try {
            BlackBoxCore.getPackageManager().getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            appInfo.packageName
        }
    }

    private fun safeLoadAppIcon(appInfo: ApplicationInfo): Drawable? {
        return try {
            BlackBoxCore.getPackageManager().getApplicationIcon(appInfo)
        } catch (e: Exception) {
            null
        }
    }

    private fun safeLoadSystemAppLabel(appInfo: ApplicationInfo): String {
        return try {
            val ctx = hostContext ?: BlackBoxCore.getContext()
            ctx.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            appInfo.packageName
        }
    }

    private fun safeLoadSystemAppIcon(appInfo: ApplicationInfo): Drawable? {
        return try {
            val ctx = hostContext ?: BlackBoxCore.getContext()
            ctx.packageManager.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            null
        }
    }
}
