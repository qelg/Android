package dev.qelg.harnessandroid.push

import android.app.Activity
import android.content.Context
import dev.qelg.harnessandroid.data.ConnectionConfig
import dev.qelg.harnessandroid.data.SecureCredentials
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.unifiedpush.android.connector.UnifiedPush

object PushRegistration {
    suspend fun register(activity: Activity) {
        if (!PushCrypto.isSupported()) return
        if (!isEnabled(activity)) return
        val credentials = SecureCredentials(activity)
        credentials.loadPushEndpoint()?.let { endpoint ->
            runCatching { uploadEndpoint(activity, endpoint) }
        }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { available ->
            if (available) {
                UnifiedPush.register(activity, messageForDistributor = "Harness Android")
            }
        }
    }

    fun instanceId(context: Context): String {
        val preferences =
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getString(INSTANCE_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(INSTANCE_ID, it).apply()
            }
    }

    fun saveEndpoint(context: Context, endpoint: String) {
        SecureCredentials(context).savePushEndpoint(endpoint)
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, true)

    suspend fun setEnabled(activity: Activity, enabled: Boolean) {
        activity.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
        if (enabled) {
            SecureCredentials(activity).loadPushEndpoint()?.let { uploadEndpoint(activity, it) }
            register(activity)
        } else {
            removeEndpoint(activity)
        }
    }

    fun clearEndpoint(context: Context) {
        SecureCredentials(context).clearPushEndpoint()
    }

    suspend fun uploadEndpoint(context: Context, endpoint: String) {
        val config = SecureCredentials(context).load() ?: return
        request(
            config,
            "PUT",
            "/push/unifiedpush/subscriptions",
            buildJsonObject {
                    put("instance_id", instanceId(context))
                    put("endpoint", endpoint)
                    put("public_key", PushCrypto.publicKey())
                }
                .toString(),
        )
    }

    suspend fun removeEndpoint(context: Context) {
        val config = SecureCredentials(context).load() ?: return
        request(config, "DELETE", "/push/unifiedpush/subscriptions/${instanceId(context)}", null)
    }

    private suspend fun request(
        config: ConnectionConfig,
        method: String,
        path: String,
        body: String?,
    ) =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(config.normalizedBaseUrl + path)
                    .apply {
                        config.token.takeIf(String::isNotBlank)?.let {
                            header("Authorization", "Bearer $it")
                        }
                    }
                    .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            CLIENT.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "Harness push registration failed with HTTP ${response.code}"
                }
            }
        }

    private const val PREFERENCES = "unifiedpush_registration"
    private const val INSTANCE_ID = "instance_id"
    private const val ENABLED = "enabled"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val CLIENT = OkHttpClient()
}
