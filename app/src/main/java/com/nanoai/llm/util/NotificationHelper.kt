package com.nanoai.llm.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nanoai.llm.MainActivity
import com.nanoai.llm.NanoAiApplication
import com.nanoai.llm.R

/**
 * NotificationHelper - Manages app notifications for various operations.
 */
object NotificationHelper {

    // Notification IDs
    const val NOTIFICATION_GENERATION = 2001
    const val NOTIFICATION_WEB_FETCH = 2002
    const val NOTIFICATION_MODEL_LOADED = 2003

    /**
     * Show notification when AI is generating response.
     */
    fun showGeneratingNotification(context: Context, prompt: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Generating response..."
        val content = prompt?.take(50)?.let { "\"$it...\"" } ?: "AI is thinking"

        val notification = NotificationCompat.Builder(context, NanoAiApplication.CHANNEL_INFERENCE)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()

        getNotificationManager(context).notify(NOTIFICATION_GENERATION, notification)
    }

    /**
     * Update generation notification with progress.
     */
    fun updateGeneratingNotification(context: Context, tokensGenerated: Int) {
        val notification = NotificationCompat.Builder(context, NanoAiApplication.CHANNEL_INFERENCE)
            .setContentTitle("Generating response...")
            .setContentText("$tokensGenerated tokens generated")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()

        getNotificationManager(context).notify(NOTIFICATION_GENERATION, notification)
    }

    /**
     * Cancel generation notification.
     */
    fun cancelGeneratingNotification(context: Context) {
        getNotificationManager(context).cancel(NOTIFICATION_GENERATION)
    }

    /**
     * Show notification when fetching web page.
     */
    fun showWebFetchNotification(context: Context, url: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val domain = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url.take(30)
        }

        val notification = NotificationCompat.Builder(context, NanoAiApplication.CHANNEL_INFERENCE)
            .setContentTitle("Fetching web page...")
            .setContentText(domain)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()

        getNotificationManager(context).notify(NOTIFICATION_WEB_FETCH, notification)
    }

    /**
     * Cancel web fetch notification.
     */
    fun cancelWebFetchNotification(context: Context) {
        getNotificationManager(context).cancel(NOTIFICATION_WEB_FETCH)
    }

    /**
     * Show notification when model is loaded.
     */
    fun showModelLoadedNotification(context: Context, modelName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NanoAiApplication.CHANNEL_INFERENCE)
            .setContentTitle("Model Ready")
            .setContentText("$modelName loaded successfully")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        getNotificationManager(context).notify(NOTIFICATION_MODEL_LOADED, notification)
    }

    /**
     * Show download progress notification.
     */
    fun showDownloadProgressNotification(
        context: Context,
        fileName: String,
        progress: Int,
        downloadedMB: Double,
        totalMB: Double
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressText = if (totalMB > 0) {
            "%.1f / %.1f MB".format(downloadedMB, totalMB)
        } else {
            "%.1f MB".format(downloadedMB)
        }

        return NotificationCompat.Builder(context, NanoAiApplication.CHANNEL_DOWNLOAD)
            .setContentTitle("Downloading $fileName")
            .setContentText(progressText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress, false)
            .build()
    }

    /**
     * Show download complete notification.
     */
    fun showDownloadCompleteNotification(context: Context, fileName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NanoAiApplication.CHANNEL_DOWNLOAD)
            .setContentTitle("Download Complete")
            .setContentText("$fileName is ready to use")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        getNotificationManager(context).notify(NOTIFICATION_WEB_FETCH, notification)
    }

    private fun getNotificationManager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
}
