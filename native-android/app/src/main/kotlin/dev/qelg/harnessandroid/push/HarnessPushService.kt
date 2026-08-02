package dev.qelg.harnessandroid.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.qelg.harnessandroid.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class HarnessPushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        PushRegistration.saveEndpoint(this, endpoint.url)
        if (PushRegistration.isEnabled(this)) {
            scope.launch {
                runCatching {
                    PushRegistration.uploadEndpoint(this@HarnessPushService, endpoint.url)
                }
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        scope.launch {
            val plaintext =
                PushCrypto.decrypt(
                    message.content,
                    PushRegistration.instanceId(this@HarnessPushService),
                ) ?: return@launch
            val payload =
                runCatching { Json.parseToJsonElement(plaintext).jsonObject }.getOrNull()
                    ?: return@launch
            val type = payload["type"]?.jsonPrimitive?.contentOrNull
            if (type != "session.finished" && type != "session.secret.ask") return@launch
            val sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull ?: return@launch
            val sessionTitle = payload["title"]?.jsonPrimitive?.contentOrNull ?: "Harness session"
            val secretAsk = type == "session.secret.ask"
            val title = if (secretAsk) "Secret requested · $sessionTitle" else sessionTitle
            val content =
                payload["content"]?.jsonPrimitive?.contentOrNull
                    ?: if (secretAsk) "A secret is required" else "Session finished"
            showNotification(this@HarnessPushService, sessionId, title, content, secretAsk)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = Unit

    override fun onUnregistered(instance: String) {
        scope.launch {
            runCatching { PushRegistration.removeEndpoint(this@HarnessPushService) }
            PushRegistration.clearEndpoint(this@HarnessPushService)
        }
    }
}

private fun showNotification(
    context: Context,
    sessionId: String,
    title: String,
    content: String,
    secretAsk: Boolean,
) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channelId = if (secretAsk) SECRET_CHANNEL_ID else FINISHED_CHANNEL_ID
    val channelName = if (secretAsk) "Secret requests" else "Finished sessions"
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
    )
    val intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
    manager.notify(sessionId.hashCode(), notification)
}

private const val FINISHED_CHANNEL_ID = "finished_sessions"
private const val SECRET_CHANNEL_ID = "secret_requests"
