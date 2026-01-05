package com.nanoai.llm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.nanoai.llm.model.ModelManager
import com.nanoai.llm.rag.RagManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * NanoAi Application class - initializes core components.
 */
class NanoAiApplication : Application() {
    companion object {
        private const val TAG = "NanoAiApp"

        const val CHANNEL_INFERENCE = "inference_channel"
        const val CHANNEL_DOWNLOAD = "download_channel"

        lateinit var instance: NanoAiApplication
            private set
    }

    // Application-wide coroutine scope
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Core managers (lazy initialization)
    val modelManager: ModelManager by lazy { ModelManager(this) }
    val ragManager: RagManager by lazy { RagManager(this) }

    // Directory paths
    val modelsDir: File by lazy { File(filesDir, "models").apply { mkdirs() } }
    val ragDataDir: File by lazy { File(filesDir, "rag_data").apply { mkdirs() } }
    val cacheDir: File by lazy { File(this.cacheDir, "temp").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "NanoAi Application starting...")

        // Create notification channels
        createNotificationChannels()

        // Initialize directories
        initializeDirectories()

        // Auto-load last active model (optional)
        applicationScope.launch {
            try {
                modelManager.loadLastActiveModel()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-load model: ${e.message}")
            }
        }

        Log.i(TAG, "NanoAi Application initialized")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Inference channel
            val inferenceChannel = NotificationChannel(
                CHANNEL_INFERENCE,
                getString(R.string.notification_channel_inference),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for AI inference operations"
                setShowBadge(false)
            }
            manager.createNotificationChannel(inferenceChannel)

            // Download channel
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                getString(R.string.notification_channel_download),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for model downloads"
            }
            manager.createNotificationChannel(downloadChannel)

            Log.d(TAG, "Notification channels created")
        }
    }

    private fun initializeDirectories() {
        // Ensure all required directories exist
        modelsDir.mkdirs()
        ragDataDir.mkdirs()
        cacheDir.mkdirs()

        // Create .nomedia file to prevent models appearing in gallery
        val noMedia = File(modelsDir, ".nomedia")
        if (!noMedia.exists()) {
            noMedia.createNewFile()
        }

        Log.d(TAG, "Directories initialized: models=${modelsDir.absolutePath}")
    }

    /**
     * Get available storage space in bytes.
     */
    fun getAvailableStorage(): Long {
        return modelsDir.freeSpace
    }

    /**
     * Get used storage for models in bytes.
     */
    fun getUsedModelStorage(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up resources
        try {
            LlamaBridge.freeAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory warning received")
        // Clear caches
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            Log.w(TAG, "Trim memory: level=$level")
            // Clear non-essential caches
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}

// Extension function for easy access
val Context.nanoAiApp: NanoAiApplication
    get() = applicationContext as NanoAiApplication
