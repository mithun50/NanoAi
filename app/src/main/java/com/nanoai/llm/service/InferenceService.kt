package com.nanoai.llm.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nanoai.llm.GenerationParams
import com.nanoai.llm.LlamaBridge
import com.nanoai.llm.MainActivity
import com.nanoai.llm.NanoAiApplication
import com.nanoai.llm.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * InferenceService - Background service for long-running LLM inference.
 *
 * Runs as a foreground service to prevent system from killing the app
 * during generation.
 */
class InferenceService : Service() {
    companion object {
        private const val TAG = "InferenceService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "NanoAi:InferenceWakeLock"

        fun start(context: Context) {
            val intent = Intent(context, InferenceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, InferenceService::class.java)
            context.stopService(intent)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentJob: Job? = null

    // State
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentPrompt = MutableStateFlow<String?>(null)
    val currentPrompt: StateFlow<String?> = _currentPrompt.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InferenceService created")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Ready"))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "InferenceService destroyed")
        serviceScope.cancel()
        releaseWakeLock()
        currentJob?.cancel()
    }

    /**
     * Run inference in the background.
     */
    fun runInference(
        prompt: String,
        params: GenerationParams = GenerationParams.BALANCED,
        onResult: (Result<String>) -> Unit
    ) {
        if (_isProcessing.value) {
            onResult(Result.failure(IllegalStateException("Already processing")))
            return
        }

        _isProcessing.value = true
        _currentPrompt.value = prompt
        updateNotification("Generating...")

        currentJob = serviceScope.launch {
            try {
                val result = LlamaBridge.generateAsync(prompt, params)
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            } finally {
                _isProcessing.value = false
                _currentPrompt.value = null
                updateNotification("Ready")
            }
        }
    }

    /**
     * Stop current inference.
     */
    fun stopInference() {
        if (_isProcessing.value) {
            LlamaBridge.stopGeneration()
            currentJob?.cancel()
            _isProcessing.value = false
            _currentPrompt.value = null
            updateNotification("Stopped")
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NanoAiApplication.CHANNEL_INFERENCE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Inference: $status")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            acquire(30 * 60 * 1000L) // 30 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
