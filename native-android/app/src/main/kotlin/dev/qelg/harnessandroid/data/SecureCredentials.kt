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
                val prefs = preferences()
                val url = prefs.getString("url", null) ?: return null
                ConnectionConfig(
                    baseUrl = url,
                    username = prefs.getString("username", "").orEmpty(),
                    password = prefs.getString("password", "").orEmpty(),
                    token = prefs.getString("token", "").orEmpty(),
                    dashboardBaseUrl = prefs.getString("dashboard_url", "").orEmpty(),
                    dashboardToken = prefs.getString("dashboard_token", "").orEmpty(),
                    transcriptionBackend =
                        prefs.getString("transcription_backend", null)?.let { value ->
                            runCatching { TranscriptionBackend.valueOf(value) }.getOrNull()
                        } ?: TranscriptionBackend.DISABLED,
                )
            }
            .getOrElse {
                appContext.deleteSharedPreferences(FILE_NAME)
                null
            }

    fun save(config: ConnectionConfig) {
        runCatching {
                preferences()
                    .edit()
                    .putString("url", config.normalizedBaseUrl)
                    .putString("username", config.username)
                    .putString("password", config.password)
                    .putString("token", config.token)
                    .putString("dashboard_url", config.normalizedDashboardBaseUrl)
                    .putString("dashboard_token", config.dashboardToken)
                    .putString("transcription_backend", config.transcriptionBackend.name)
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
        const val FILE_NAME = "hermes_credentials"
    }
}
