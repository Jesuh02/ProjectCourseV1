package com.example.tareamov.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tareamov.MainActivity
import com.example.tareamov.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val CHANNEL_ID = "deepseek_updates"
    }

    /**
     * Called if the FCM registration token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the
     * FCM registration token is initially generated so this is where you would retrieve
     * the token.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "Refreshed token: $token")

        // If you want to send messages to this application instance or
        // manage this apps subscriptions on the server side, send the
        // FCM registration token to your app server.
        sendRegistrationToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Check if message contains a data payload.
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
            
            // Handle chat-specific notifications with rich UI
            val notificationType = remoteMessage.data["type"]
            if (notificationType == "chat_response") {
                sendChatNotification(
                    title = remoteMessage.notification?.title ?: "🤖 Nueva respuesta del chat",
                    body = remoteMessage.notification?.body ?: "Tu consulta ha sido procesada",
                    data = remoteMessage.data
                )
                return
            }
        }

        // Check if message contains a notification payload (default handling)
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            sendNotification(it.title, it.body, remoteMessage.data)
        }
    }

    /**
     * Persist token to third-party servers.
     *
     * Modify this method to associate the user's FCM InstanceID token with any server-side account
     * maintained by your application.
     *
     * @param token The new token.
     */
    private fun sendRegistrationToServer(token: String) {
        // Get current user ID from SessionManager
        val userId = com.example.tareamov.util.SessionManager.getInstance(applicationContext).getUserId()
        
        if (userId != -1L) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SupabaseClient.registerFcmToken(userId, token)
                    Log.d(TAG, "Token registered for user $userId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register token", e)
                }
            }
        }
    }

    /**
     * Create and show a simple notification containing the received FCM message.
     *
     * @param messageBody FCM message body received.
     */
    private fun sendNotification(title: String?, messageBody: String?, data: Map<String, String>) {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        
        // Pass data to intent
        for ((key, value) in data) {
            intent.putExtra(key, value)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0 /* Request code */, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = CHANNEL_ID
        val defaultSoundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this resource exists
            .setContentTitle(title ?: "Notificación")
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Since android Oreo notification channel is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DeepSeek Updates",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0 /* ID of notification */, notificationBuilder.build())
    }
    
    /**
     * Crea y muestra notificación especial para respuestas del chat
     * Incluye estilo expandido, acciones rápidas y deep linking
     */
    private fun sendChatNotification(title: String, body: String, data: Map<String, String>) {
        // Intent para abrir el chat directamente
        val chatIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("openFragment", "DatabaseQueryFragment")
            putExtra("userId", data["userId"])
            
            // Pass all data
            for ((key, value) in data) {
                putExtra(key, value)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(), // Unique request code
            chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Canal de notificación específico para chat
        val channelId = "chat_notifications"
        val defaultSoundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        
        // Construir notificación con estilo expandido
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$body\n\n💡 Tu asistente de IA ha procesado tu consulta. Toca para ver la respuesta completa en el chat.")
                .setBigContentTitle(title)
            )
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(0xFF6366F1.toInt()) // Indigo color (TareaMov brand)
            .setColorized(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Crear canal de notificaciones para chat (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat y Respuestas de IA",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de respuestas del asistente de IA en el chat"
                enableLights(true)
                lightColor = 0xFF6366F1.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Generar ID único basado en timestamp para múltiples notificaciones
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
        
        Log.d(TAG, "📱 Chat notification displayed with ID: $notificationId")
    }
}
