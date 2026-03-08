package com.example.tareamov.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.example.tareamov.MainActivity
import com.example.tareamov.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        serviceScope.launch {
            try {
                BackendApiService.registerFCMToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Message received from: ${remoteMessage.from}")

        val notification = remoteMessage.notification
        val data = remoteMessage.data

        val title = notification?.title ?: data["title"] ?: "Nueva notificación"
        val body = notification?.body ?: data["body"] ?: data["message"] ?: ""

        serviceScope.launch {
            showNotification(title, body, data)
        }
    }

    private suspend fun showNotification(title: String, body: String, data: Map<String, String>) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = buildNavigationIntent(data)
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        loadLargeIcon(data)?.let { bitmap ->
            builder.setLargeIcon(bitmap)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun buildNavigationIntent(data: Map<String, String>): Intent {
        return Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

            data["type"]?.let { putExtra("notification_type", it) }
            data["relatedId"]?.let { putExtra("related_id", it.toLongOrNull() ?: -1L) }
            data["courseId"]?.let { putExtra("course_id", it.toLongOrNull() ?: -1L) }
            data["taskId"]?.let { putExtra("task_id", it.toLongOrNull() ?: -1L) }
            data["videoId"]?.let { putExtra("video_id", it.toLongOrNull() ?: -1L) }
            data["topicId"]?.let { putExtra("topic_id", it.toLongOrNull() ?: -1L) }
            data["notificationId"]?.let { putExtra("notification_id", it.toLongOrNull() ?: -1L) }
        }
    }

    private suspend fun loadLargeIcon(data: Map<String, String>): Bitmap? = withContext(Dispatchers.IO) {
        val imageUrl = data["thumbnailUrl"] ?: data["senderAvatarUrl"] ?: return@withContext null
        if (imageUrl.isBlank()) return@withContext null

        try {
            Glide.with(applicationContext)
                .asBitmap()
                .load(imageUrl)
                .circleCrop()
                .submit(150, 150)
                .get()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load notification image", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notificaciones",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de CourseV"
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "FCMService"
        const val CHANNEL_ID = "deepseek_updates"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Notificaciones",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notificaciones de CourseV"
                    enableVibration(true)
                    enableLights(true)
                }
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
