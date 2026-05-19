package com.kail.location.root

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * Injection trace hiding utilities.
 * Attempts to reduce detectable artifacts left by the hooking framework.
 *
 * Techniques:
 * 1. Binder descriptor normalization: proxies override getInterfaceDescriptor()
 *    so that IBinder.getInterfaceDescriptor() returns the expected string
 *    instead of null (which many detection frameworks flag).
 * 2. Disk cleanup: delete SO files from disk after they are mapped into memory.
 *    The code remains resident but the file disappears from filesystem inspection.
 * 3. (Future) Native /proc/self/maps filtering via syscall hooking.
 */
object TraceHider {
    private const val TAG = "KailTraceHider"

    // Known SO names that may be loaded in the target process
    private val SO_NAMES = listOf(
        "libkail_root_hook.so",
        "libkail_native_hook.so",
        "libkail_lhooker.so",
        "libdobby.so",
        "libshadowhook.so"
    )

    // Common injection paths
    private val INJECTION_PATHS = listOf(
        "/data/local/kail-lib/",
        "/data/data/com.kail.location/files/",
        "/data/user/0/com.kail.location/files/",
        "/data/app/",
        "/system/lib/",
        "/system/lib64/"
    )

    @JvmStatic
    fun hideTraces(context: Context?) {
        Log.i(TAG, "TraceHider.hideTraces() pid=${android.os.Process.myPid()}")
        try {
            deleteSoFilesFromDisk()
            clearClassLoaderCache()
            Log.i(TAG, "Trace hiding completed")
        } catch (e: Throwable) {
            Log.w(TAG, "hideTraces failed: ${e.message}")
        }
    }

    /**
     * Delete SO files from disk after they are memory-mapped.
     * The mapped pages remain valid (ref-counted by the kernel),
     * but filesystem inspection (ls, find) will no longer see them.
     */
    private fun deleteSoFilesFromDisk() {
        var deleted = 0
        for (path in INJECTION_PATHS) {
            for (soName in SO_NAMES) {
                val file = File(path, soName)
                if (file.exists()) {
                    try {
                        val deleted_ok = file.delete()
                        if (deleted_ok) {
                            deleted++
                            Log.i(TAG, "Deleted SO from disk: ${file.absolutePath}")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to delete ${file.absolutePath}: ${e.message}")
                    }
                }
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "Deleted $deleted SO files from disk")
        }
    }

    /**
     * Clear DexPathList / nativeLibraryPathElements caches in the classloader
     * to remove references to our SO paths from Java-level reflection.
     */
    private fun clearClassLoaderCache() {
        try {
            val cl = TraceHider::class.java.classLoader ?: return
            // Try to clear BaseDexClassLoader pathList
            val pathListField = cl.javaClass.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl)

            // Clear nativeLibraryDirectories
            try {
                val nativeLibDirsField = pathList.javaClass.getDeclaredField("nativeLibraryDirectories")
                nativeLibDirsField.isAccessible = true
                val dirs = nativeLibDirsField.get(pathList) as? MutableList<File>
                dirs?.removeAll { dir ->
                    SO_NAMES.any { name -> dir.absolutePath.contains(name) }
                }
            } catch (_: Throwable) {}

            // Clear nativeLibraryPathElements (Android 8+)
            try {
                val nativeLibElementsField = pathList.javaClass.getDeclaredField("nativeLibraryPathElements")
                nativeLibElementsField.isAccessible = true
                val elements = nativeLibElementsField.get(pathList) as? Array<*>
                if (elements != null) {
                    val filtered = elements.filter { elem ->
                        if (elem == null) true
                        else {
                            val path = try {
                                elem.javaClass.getDeclaredField("path").apply { isAccessible = true }.get(elem) as? String
                            } catch (_: Throwable) { null }
                            path == null || SO_NAMES.none { path.contains(it) }
                        }
                    }
                    nativeLibElementsField.set(pathList, filtered.toTypedArray())
                }
            } catch (_: Throwable) {}

            Log.i(TAG, "Classloader native library cache cleaned")
        } catch (e: Throwable) {
            Log.w(TAG, "clearClassLoaderCache failed: ${e.message}")
        }
    }

    /**
     * Normalize a binder proxy so that getInterfaceDescriptor() returns the
     * expected descriptor string. This prevents detection frameworks from
     * spotting null descriptors (a tell-tale sign of proxy replacement).
     *
     * Usage: call attachInterface(null, descriptor) in the proxy constructor,
     * then override getInterfaceDescriptor() to return descriptor.
     */
    fun normalizeBinderDescriptor(proxy: Binder, descriptor: String) {
        try {
            proxy.attachInterface(null, descriptor)
        } catch (e: Throwable) {
            Log.w(TAG, "normalizeBinderDescriptor failed: ${e.message}")
        }
    }
}
