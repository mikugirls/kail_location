package com.kail.location.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kail.location.models.UpdateInfo
import com.kail.location.utils.UpdateChecker
import com.kail.location.utils.KailLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WelcomeViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "WelcomeViewModel"
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _installUri = MutableStateFlow<Uri?>(null)
    val installUri: StateFlow<Uri?> = _installUri.asStateFlow()

    fun checkUpdate(context: Context, isAuto: Boolean = true) {
        UpdateChecker.check(context) { info, _ ->
            _updateInfo.value = info
        }
    }

    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun clearInstallUri() {
        _installUri.value = null
    }

    fun startDownload(context: Context) {
        val info = _updateInfo.value ?: return
        if (_isDownloading.value) return
        _isDownloading.value = true
        _downloadProgress.value = 0
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(info.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
                val body = response.body ?: throw java.io.IOException("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                val dir = java.io.File(context.getExternalFilesDir(null), "Updates")
                if (!dir.exists()) dir.mkdirs()
                val outFile = java.io.File(dir, info.filename)
                body.byteStream().use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var sum = 0L
                        while (true) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            sum += bytesRead
                            if (total > 0) {
                                val pct = ((sum * 100) / total).toInt().coerceIn(0, 100)
                                _downloadProgress.value = pct
                            }
                        }
                        output.flush()
                    }
                }
                _downloadProgress.value = 100
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileProvider",
                    outFile
                )
                _installUri.value = uri
            } catch (e: Exception) {
                KailLog.e(getApplication(), TAG, "startDownload: download update failed", e)
                _isDownloading.value = false
            } finally {
                _isDownloading.value = false
            }
        }
    }
}
