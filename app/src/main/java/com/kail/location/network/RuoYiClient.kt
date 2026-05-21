package com.kail.location.network

import android.util.Log
import com.kail.location.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RuoYiClient {

    private const val TAG = "PocketBaseClient"
    private const val JSON_TYPE = "application/json"

    var baseUrl: String = BuildConfig.POCKETBASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class AuthResult(
        val token: String,
        val email: String,
        val id: String,
        val verified: Boolean
    )

    fun login(email: String, password: String): Result<AuthResult> {
        return runCatching {
            val url = "$baseUrl/admin-api/system/auth/login"
            val json = JSONObject().apply {
                put("username", email)
                put("password", password)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "Login failed"))
            }

            val data = root.getJSONObject("data")
            AuthResult(
                token = data.getString("accessToken"),
                email = email,
                id = data.optString("userId", ""),
                verified = true
            )
        }
    }

    fun sendVerificationCode(email: String): Result<Unit> {
        return runCatching {
            val url = "$baseUrl/admin-api/system/auth/send-sms-code"
            val json = JSONObject().apply {
                put("mobile", email)
                put("scene", 1)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "Failed to send verification code"))
            }
        }
    }

    fun registerWithCode(
        email: String,
        password: String,
        passwordConfirm: String,
        verificationCode: String
    ): Result<AuthResult> {
        return runCatching {
            val url = "$baseUrl/admin-api/system/user/create"
            val json = JSONObject().apply {
                put("username", email)
                put("nickname", email.substringBefore("@"))
                put("email", email)
                put("password", password)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "Registration failed"))
            }

            login(email, password).getOrThrow()
        }
    }

    fun register(email: String, password: String, passwordConfirm: String): Result<AuthResult> {
        return runCatching {
            val url = "$baseUrl/admin-api/system/user/create"
            val json = JSONObject().apply {
                put("username", email)
                put("nickname", email.substringBefore("@"))
                put("email", email)
                put("password", password)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "Registration failed"))
            }

            login(email, password).getOrThrow()
        }
    }

    fun requestPasswordReset(email: String): Result<Unit> {
        return runCatching {
            val url = "$baseUrl/admin-api/system/auth/reset-password"
            val json = JSONObject().apply {
                put("email", email)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "Failed to send reset email"))
            }
        }
    }
}
