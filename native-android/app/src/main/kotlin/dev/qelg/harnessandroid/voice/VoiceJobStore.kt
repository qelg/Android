package dev.qelg.harnessandroid.voice

import android.content.Context

internal enum class VoiceJobPhase {
    RECORDING,
    TRANSCRIBING,
    COMPLETE,
    FAILED,
    CANCELED,
}

internal data class VoiceJob(
    val id: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val targetModel: String?,
    val modelId: String,
    val threadCount: Int,
    val audioPath: String,
    val totalSamples: Long,
    val phase: VoiceJobPhase,
    val completedSamples: Long = 0,
    val elapsedMs: Long? = null,
    val transcript: String? = null,
    val error: String? = null,
)

internal class VoiceJobStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(job: VoiceJob) {
        preferences
            .edit()
            .apply {
                putString(KEY_ID, job.id)
                putString(KEY_STORED_SESSION_ID, job.storedSessionId)
                putString(KEY_RUNTIME_SESSION_ID, job.runtimeSessionId)
                if (job.targetModel == null) remove(KEY_TARGET_MODEL)
                else putString(KEY_TARGET_MODEL, job.targetModel)
                putString(KEY_MODEL_ID, job.modelId)
                putInt(KEY_THREAD_COUNT, job.threadCount)
                putString(KEY_AUDIO_PATH, job.audioPath)
                putLong(KEY_TOTAL_SAMPLES, job.totalSamples)
                putString(KEY_PHASE, job.phase.name)
                putLong(KEY_COMPLETED_SAMPLES, job.completedSamples)
                if (job.elapsedMs == null) remove(KEY_ELAPSED_MS)
                else putLong(KEY_ELAPSED_MS, job.elapsedMs)
                if (job.transcript == null) remove(KEY_TRANSCRIPT)
                else putString(KEY_TRANSCRIPT, job.transcript)
                if (job.error == null) remove(KEY_ERROR) else putString(KEY_ERROR, job.error)
            }
            .apply()
    }

    @Synchronized
    fun load(): VoiceJob? {
        val id = preferences.getString(KEY_ID, null) ?: return null
        val phase =
            preferences.getString(KEY_PHASE, null)?.let { value ->
                runCatching { VoiceJobPhase.valueOf(value) }.getOrNull()
            } ?: return null
        return VoiceJob(
            id = id,
            storedSessionId = preferences.getString(KEY_STORED_SESSION_ID, null) ?: return null,
            runtimeSessionId = preferences.getString(KEY_RUNTIME_SESSION_ID, null) ?: return null,
            targetModel = preferences.getString(KEY_TARGET_MODEL, null),
            modelId = preferences.getString(KEY_MODEL_ID, null) ?: return null,
            threadCount = preferences.getInt(KEY_THREAD_COUNT, WhisperCpuConfig.AUTOMATIC),
            audioPath = preferences.getString(KEY_AUDIO_PATH, null) ?: return null,
            totalSamples = preferences.getLong(KEY_TOTAL_SAMPLES, 0),
            phase = phase,
            completedSamples = preferences.getLong(KEY_COMPLETED_SAMPLES, 0),
            elapsedMs =
                if (preferences.contains(KEY_ELAPSED_MS)) preferences.getLong(KEY_ELAPSED_MS, 0)
                else null,
            transcript = preferences.getString(KEY_TRANSCRIPT, null),
            error = preferences.getString(KEY_ERROR, null),
        )
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "local_voice_job"
        private const val KEY_ID = "id"
        private const val KEY_STORED_SESSION_ID = "stored_session_id"
        private const val KEY_RUNTIME_SESSION_ID = "runtime_session_id"
        private const val KEY_TARGET_MODEL = "target_model"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_AUDIO_PATH = "audio_path"
        private const val KEY_TOTAL_SAMPLES = "total_samples"
        private const val KEY_PHASE = "phase"
        private const val KEY_COMPLETED_SAMPLES = "completed_samples"
        private const val KEY_ELAPSED_MS = "elapsed_ms"
        private const val KEY_TRANSCRIPT = "transcript"
        private const val KEY_ERROR = "error"
    }
}
