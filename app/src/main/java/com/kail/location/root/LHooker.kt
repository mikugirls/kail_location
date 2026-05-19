package com.kail.location.root

import android.util.Log
import java.io.File

/**
 * FakeLocation LHooker JNI Bridge.
 * Replaces Proxy-based hooks with direct ArtMethod entry_point replacement.
 */
object LHooker {
    private const val TAG = "KailLHooker"
    private var initialized = false

    init {
        loadLibrary()
    }

    private fun loadLibrary() {
        val paths = listOf(
            "/data/local/kail-lib/libkail_lhooker.so",
            "/data/local/tmp/libkail_lhooker.so"
        )
        for (path in paths) {
            if (File(path).exists()) {
                try {
                    System.load(path)
                    Log.i(TAG, "Loaded LHooker from $path")
                    return
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to load from $path: ${e.message}")
                }
            }
        }
        // Fallback to System.loadLibrary (works in app process)
        try {
            System.loadLibrary("kail_lhooker")
            Log.i(TAG, "Loaded LHooker via System.loadLibrary")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load LHooker library: ${e.message}")
        }
    }

    @JvmStatic
    external fun init(sdk: Int): Boolean

    /** Find method by class/name/signature and hook it. */
    @JvmStatic
    external fun findAndHookMethod(
        targetClass: Class<*>,
        methodName: String,
        methodSig: String,
        hookMethod: java.lang.reflect.Method,
        backupMethod: java.lang.reflect.Method
    ): Boolean

    /** Hook an already-reflected target Method. */
    @JvmStatic
    external fun hookMethod(
        targetMethod: java.lang.reflect.Method,
        hookMethod: java.lang.reflect.Method,
        backupMethod: java.lang.reflect.Method
    ): Boolean

    @JvmStatic
    fun ensureInit(): Boolean {
        if (initialized) return true
        val sdk = android.os.Build.VERSION.SDK_INT
        val result = try {
            init(sdk)
        } catch (e: Throwable) {
            Log.e(TAG, "LHooker native init failed: ${e.message}")
            false
        }
        initialized = result
        Log.i(TAG, "LHooker init result=$result sdk=$sdk")
        return result
    }
}
