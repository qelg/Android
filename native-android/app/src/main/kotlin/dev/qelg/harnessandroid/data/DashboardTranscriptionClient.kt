package dev.qelg.harnessandroid.data

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class DashboardTranscriptionClient(
    private val config: ConnectionConfig,
    client: OkHttpClient = OkHttpClient.Builder().cookieJar(MemoryCookieJar()).build(),
    timeoutMillis: Long = TimeUnit.MINUTES.toMillis(2),
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        client
            .newBuilder()
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    suspend fun authenticate() {
        if (config.dashboardToken.isBlank()) {
            require(config.username.isNotBlank() && config.password.isNotBlank()) {
                "Dashboard username and password are required"
            }
            http(
                "/auth/password-login",
                buildJsonObject {
                    put("provider", "basic")
                    put("username", config.username)
                    put("password", config.password)
                    put("next", "")
                },
            )
        }
    }

    suspend fun transcribe(bytes: ByteArray, mimeType: String): String {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val response =
            try {
                http(
                    "/api/audio/transcribe",
                    buildJsonObject {
                        put("data_url", "data:$mimeType;base64,$encoded")
                        put("mime_type", mimeType)
                    },
                )
            } catch (error: InterruptedIOException) {
                throw IllegalStateException(
                    "Voice transcription timed out. Please check the Dashboard backend and try again.",
                    error,
                )
            }
        return response.string("transcript")?.trim()?.takeIf(String::isNotEmpty)
            ?: error("Transcription returned no text")
    }

    private suspend fun http(path: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(config.normalizedDashboardBaseUrl + path)
                    .header("Accept", "application/json")
                    .apply {
                        if (config.dashboardToken.isNotBlank()) {
                            header("X-Hermes-Session-Token", config.dashboardToken)
                        }
                    }
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Hermes Dashboard HTTP ${response.code}: $text" }
                if (text.isBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(text).jsonObject
            }
        }

    override fun close() {
        client.connectionPool.evictAll()
    }
}

private class MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this.cookies) { cookies.forEach { this.cookies[it.name] = it } }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(cookies) { cookies.values.filter { it.matches(url) } }
}
