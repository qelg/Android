package dev.qelg.harnessandroid.voice

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MODEL_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

internal object WhisperNative {
    init {
        System.loadLibrary("harness-whisper")
    }

    external fun createContext(modelPath: String): Long

    external fun freeContext(pointer: Long)

    external fun transcribe(
        pointer: Long,
        samples: FloatArray,
        threadCount: Int,
        initialPrompt: String?,
        listener: WhisperNativeListener,
    ): String
}

internal interface WhisperNativeListener {
    fun onProgress(percent: Int)

    fun onPartial(text: String)

    fun shouldAbort(): Boolean
}

class LocalWhisper(private val context: Context) : Closeable {
    private val mutex = Mutex()
    private val nativeLock = Any()
    private val closed = AtomicBoolean(false)
    private var nativeContext = 0L
    private var loadedModelId: String? = null

    suspend fun transcribe(
        samples: FloatArray,
        model: WhisperModel,
        onStatus: (String) -> Unit,
        initialPrompt: String? = null,
        onProgress: (Int) -> Unit = {},
        onPartial: (String) -> Unit = {},
        allowEmpty: Boolean = false,
        shouldAbort: () -> Boolean = { false },
    ): String =
        withContext(Dispatchers.IO) {
            require(samples.isNotEmpty()) { "No voice audio was recorded" }
            check(!closed.get()) { "Whisper is closed" }
            mutex.withLock {
                val modelFile = ensureModel(model, onStatus)
                onStatus("Transcribing locally with Whisper ${model.displayName}…")
                synchronized(nativeLock) {
                    check(!closed.get()) { "Whisper is closed" }
                    if (loadedModelId != model.id) {
                        releaseContext()
                        nativeContext = WhisperNative.createContext(modelFile.path)
                        loadedModelId = model.id
                    }
                    val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
                    WhisperNative.transcribe(
                            nativeContext,
                            samples,
                            threads,
                            initialPrompt,
                            object : WhisperNativeListener {
                                override fun onProgress(percent: Int) = onProgress(percent)

                                override fun onPartial(text: String) = onPartial(text.trim())

                                override fun shouldAbort(): Boolean = shouldAbort()
                            },
                        )
                        .trim()
                        .also {
                            if (!allowEmpty)
                                require(it.isNotBlank()) { "Whisper did not detect any speech" }
                        }
                }
            }
        }

    override fun close() {
        closed.set(true)
        synchronized(nativeLock) { releaseContext() }
    }

    fun isDownloaded(model: WhisperModel): Boolean = modelFile(model).isValidModel(model)

    private fun ensureModel(model: WhisperModel, onStatus: (String) -> Unit): File {
        val target = modelFile(model)
        if (target.isValidModel(model)) return target
        target.delete()
        val partial = File(target.parentFile, "${model.fileName}.part").apply { delete() }
        onStatus("Downloading Whisper ${model.displayName}…")
        val connection =
            (URL("$MODEL_BASE_URL/${model.fileName}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
        try {
            check(connection.responseCode in 200..299) {
                "Whisper model download failed (HTTP ${connection.responseCode})"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val percent = (downloaded * 100 / model.sizeBytes).toInt().coerceAtMost(100)
                        if (percent != lastPercent) {
                            onStatus("Downloading Whisper ${model.displayName}… $percent%")
                            lastPercent = percent
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() == model.sizeBytes) {
                "Downloaded Whisper model has the wrong size"
            }
            check(digest.digest().toHex() == model.sha256) {
                "Downloaded Whisper model failed verification"
            }
            check(partial.renameTo(target)) { "Could not install the Whisper model" }
            return target
        } finally {
            connection.disconnect()
            if (!target.exists()) partial.delete()
        }
    }

    private fun modelFile(model: WhisperModel): File =
        File(File(context.noBackupFilesDir, "whisper").apply { mkdirs() }, model.fileName)

    private fun File.isValidModel(model: WhisperModel): Boolean =
        isFile && length() == model.sizeBytes

    private fun releaseContext() {
        if (nativeContext != 0L) WhisperNative.freeContext(nativeContext)
        nativeContext = 0L
        loadedModelId = null
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
