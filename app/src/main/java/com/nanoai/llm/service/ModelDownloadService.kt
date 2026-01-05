package com.nanoai.llm.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nanoai.llm.ModelManagerActivity
import com.nanoai.llm.NanoAiApplication
import com.nanoai.llm.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelDownloadService - Background service for downloading GGUF models.
 *
 * Handles large file downloads with progress tracking and resume support.
 */
class ModelDownloadService : Service() {
    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_ID = 1002
        private const val BUFFER_SIZE = 8192

        fun start(context: Context, url: String, filename: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                putExtra("url", url)
                putExtra("filename", filename)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            context.stopService(intent)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    // State
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ModelDownloadService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Preparing...", 0))

        val url = intent?.getStringExtra("url")
        val filename = intent?.getStringExtra("filename")

        if (url != null && filename != null) {
            startDownload(url, filename)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ModelDownloadService destroyed")
        serviceScope.cancel()
        currentJob?.cancel()
    }

    /**
     * Start downloading a model.
     */
    fun startDownload(url: String, filename: String) {
        if (_downloadState.value is DownloadState.Downloading) {
            Log.w(TAG, "Download already in progress")
            return
        }

        val modelsDir = (applicationContext as NanoAiApplication).modelsDir
        val outputFile = File(modelsDir, filename)

        currentJob = serviceScope.launch {
            try {
                _downloadState.value = DownloadState.Downloading(url, filename, 0, 0)
                download(url, outputFile)
                _downloadState.value = DownloadState.Completed(outputFile.absolutePath)
                updateNotification("Download complete", 100)
                delay(2000)
                stopSelf()
            } catch (e: CancellationException) {
                _downloadState.value = DownloadState.Cancelled
                outputFile.delete()
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
                updateNotification("Download failed", 0)
                outputFile.delete()
            }
        }
    }

    /**
     * Cancel current download.
     */
    fun cancelDownload() {
        currentJob?.cancel()
        _downloadState.value = DownloadState.Cancelled
    }

    private suspend fun download(urlString: String, outputFile: File) {
        var connection: HttpURLConnection? = null
        var output: FileOutputStream? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"

            // Support resume
            var downloadedBytes = 0L
            if (outputFile.exists()) {
                downloadedBytes = outputFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in listOf(200, 206)) {
                throw Exception("HTTP error: $responseCode")
            }

            val totalBytes = connection.contentLength.toLong() + downloadedBytes
            val inputStream = connection.inputStream
            output = FileOutputStream(outputFile, downloadedBytes > 0)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var currentBytes = downloadedBytes

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!coroutineContext.isActive) throw CancellationException()

                output.write(buffer, 0, bytesRead)
                currentBytes += bytesRead

                val progress = if (totalBytes > 0) {
                    ((currentBytes * 100) / totalBytes).toInt()
                } else 0

                _downloadState.value = DownloadState.Downloading(
                    urlString,
                    outputFile.name,
                    currentBytes,
                    totalBytes
                )

                updateNotification("Downloading ${outputFile.name}", progress)
            }

            output.flush()
            Log.i(TAG, "Download complete: ${outputFile.absolutePath}")

        } finally {
            output?.close()
            connection?.disconnect()
        }
    }

    private fun createNotification(status: String, progress: Int): Notification {
        val intent = Intent(this, ModelManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NanoAiApplication.CHANNEL_DOWNLOAD)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress > 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun updateNotification(status: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(status, progress))
    }
}

/**
 * Download state sealed class.
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val url: String,
        val filename: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState() {
        val progress: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }
    data class Completed(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
    object Cancelled : DownloadState()
}
