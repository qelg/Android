package dev.qelg.harnessandroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureCredentials(context: Context) {
    private val appContext = context.applicationContext

    private fun preferences(): SharedPreferences =
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun load(): ConnectionConfig? =
        runCatching {
                val p = preferences()
                ConnectionConfig(
                    p.getString("url", null) ?: return null,
                    p.getString("token", "").orEmpty(),
                )
            }
            .getOrElse {
                appContext.deleteSharedPreferences(FILE_NAME)
                null
            }

    fun loadPushEndpoint(): String? =
        runCatching { preferences().getString("push_endpoint", null) }.getOrNull()

    fun savePushEndpoint(endpoint: String) {
        preferences().edit().putString("push_endpoint", endpoint).apply()
    }

    fun clearPushEndpoint() {
        runCatching { preferences().edit().remove("push_endpoint").apply() }
    }

    fun save(config: ConnectionConfig) {
        runCatching {
                preferences()
                    .edit()
                    .putString("url", config.normalizedBaseUrl)
                    .putString("token", config.token)
                    .apply()
            }
            .getOrElse {
                appContext.deleteSharedPreferences(FILE_NAME)
                throw IllegalStateException("Could not securely store credentials", it)
            }
    }

    fun clear() {
        runCatching { preferences().edit().clear().apply() }
        appContext.deleteSharedPreferences(FILE_NAME)
    }

    private companion object {
        const val FILE_NAME = "harness_credentials"
    }
}
