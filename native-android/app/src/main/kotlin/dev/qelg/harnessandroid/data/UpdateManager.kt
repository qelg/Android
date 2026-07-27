package dev.qelg.harnessandroid.data

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateState(
    val checking: Boolean = false,
    val available: Boolean = false,
    val upToDate: Boolean = false,
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val latestVersionCode: Long? = null,
    val downloadUrl: String? = null,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedFile: File? = null,
    val error: String? = null,
)

class UpdateManager(private val app: Application) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val currentVersionName: String
        get() =
            runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
                }
                .getOrDefault("unknown")

    private val currentVersionCode: Long
        get() =
            runCatching {
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    PackageInfoCompat.getLongVersionCode(info)
                }
                .getOrDefault(0L)

    suspend fun checkForUpdate() {
        _state.value = UpdateState(checking = true)
        try {
            val release = fetchLatestRelease()
            val (versionName, versionCode) = parseVersion(release)
            val downloadUrl =
                release["assets"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject }
                    ?.firstOrNull {
                        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
                    }
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.contentOrNull

            val available =
                versionCode != null && versionCode > currentVersionCode && downloadUrl != null

            _state.value =
                UpdateState(
                    checking = false,
                    available = available,
                    upToDate = !available && versionCode != null,
                    currentVersion = currentVersionName,
                    latestVersion = versionName,
                    latestVersionCode = versionCode,
                    downloadUrl = if (available) downloadUrl else null,
                )
        } catch (e: Exception) {
            _state.value = UpdateState(checking = false, error = e.message ?: "Check failed")
        }
    }

    suspend fun downloadAndInstall() {
        val url = _state.value.downloadUrl ?: return
        _state.value = _state.value.copy(downloading = true, downloadProgress = 0f, error = null)
        try {
            val file =
                downloadApk(url) { progress ->
                    _state.value = _state.value.copy(downloadProgress = progress)
                }
            _state.value =
                _state.value.copy(downloading = false, downloadProgress = 1f, downloadedFile = file)
            installApk(file)
        } catch (e: Exception) {
            _state.value =
                _state.value.copy(downloading = false, error = e.message ?: "Download failed")
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        app.startActivity(intent)
    }

    fun reset() {
        _state.value = UpdateState()
    }

    private suspend fun fetchLatestRelease(): kotlinx.serialization.json.JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("https://api.github.com/repos/qelg/Android/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                error("GitHub API returned ${response.code}")
            }
            val body = response.body?.string() ?: error("Empty response")
            response.close()
            json.parseToJsonElement(body).jsonObject
        }

    private suspend fun downloadApk(url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                error("Download failed with status ${response.code}")
            }
            val body = response.body ?: error("Empty response body")
            val contentLength = body.contentLength()
            val file = File(app.cacheDir, "harness-android-update.apk")
            FileOutputStream(file).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }
            response.close()
            file
        }

    private fun parseVersion(release: kotlinx.serialization.json.JsonObject): Pair<String?, Long?> {
        // Parse versionCode from release body (set by CI): "versionCode 38"
        val body = release["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val versionCodeMatch = Regex("""versionCode\s+(\d+)""").find(body)
        val releaseVersionCode = versionCodeMatch?.groupValues?.get(1)?.toLongOrNull()
        // Parse versionName from release body: "build 0.1.3.dev5 (versionCode"
        val versionNameMatch = Regex("""build\s+(\S+)\s+\(versionCode""").find(body)
        val releaseVersionName = versionNameMatch?.groupValues?.get(1)

        val hasApk =
            release["assets"]?.jsonArray?.any {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
            } == true

        if (releaseVersionCode == null || !hasApk) return null to null
        // Always return the release versionCode so checkForUpdate() can show
        // "Up to date" when the installed version matches the latest release.
        return releaseVersionName to releaseVersionCode
    }
}
