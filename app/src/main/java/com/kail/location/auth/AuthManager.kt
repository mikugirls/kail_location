package com.kail.location.auth

import android.content.Context
import android.content.SharedPreferences

object AuthManager {

    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_EMAIL = "auth_email"
    private const val KEY_USER_ID = "auth_user_id"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        private set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        private set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        private set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    fun saveAuth(token: String, email: String, userId: String) {
        this.token = token
        this.email = email
        this.userId = userId
        isLoggedIn = true
    }

    fun clearAuth() {
        token = null
        email = null
        userId = null
        isLoggedIn = false
    }
}
