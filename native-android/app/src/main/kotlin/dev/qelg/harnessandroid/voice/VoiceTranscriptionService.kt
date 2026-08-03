package dev.qelg.harnessandroid.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.qelg.harnessandroid.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatVoiceDuration(samples: Long): String {
    val seconds = samples.coerceAtLeast(0) / LocalAudioRecorder.SAMPLE_RATE
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatElapsedDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal class VoiceTranscriptionService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val store by lazy { VoiceJobStore(this) }
    private var worker: Job? = null
    private var activeTranscriber: IncrementalVoiceTranscriber? = null
    private var whisper: LocalWhisper? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelJob()
            return START_NOT_STICKY
        }
        val job = store.load()
        if (job == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(job, "Preparing Whisper…", null, true))
        if (worker?.isActive != true) worker = serviceScope.launch { runJob(job, startId) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeTranscriber?.cancel()
        worker?.cancel()
        whisper?.close()
        serviceJob.cancel()
        super.onDestroy()
    }

    private suspend fun runJob(job: VoiceJob, startId: Int) {
        val result = CompletableDeferred<Result<String>>()
        val stopped = AtomicBoolean(false)
        val transcriber =
            IncrementalVoiceTranscriber(
                scope = serviceScope,
                transcribe = { samples, prompt, onProgress, onPartial, shouldAbort ->
                    whisper!!.transcribe(
                        samples = samples,
                        model = WhisperModel.fromId(job.modelId),
                        configuredThreadCount = job.threadCount,
                        onStatus = { status -> updateProgress(job.id, status) },
                        initialPrompt = prompt,
                        onProgress = onProgress,
                        onPartial = onPartial,
                        allowEmpty = true,
                        shouldAbort = shouldAbort,
                    )
                },
                onStarted = { updateProgress(job.id, "Transcribing locally with Whisper…") },
                onUpdate = { update ->
                    val current = store.load() ?: job
                    store.save(
                        current.copy(
                            phase = VoiceJobPhase.TRANSCRIBING,
                            completedSamples = update.completedSamples,
                            elapsedMs = update.transcriptionElapsedMs,
                            transcript = update.text.takeIf(String::isNotBlank),
                        )
                    )
                    updateProgress(job.id, null, update)
                },
                onComplete = { text -> result.complete(Result.success(text)) },
                onFailure = { error -> result.complete(Result.failure(error)) },
                onStopped = { stopped.set(true) },
            )
        activeTranscriber = transcriber
        try {
            store.save(job.copy(phase = VoiceJobPhase.TRANSCRIBING, error = null))
            broadcast(store.load() ?: job)
            whisper = LocalWhisper(this)
            val audioFile = File(job.audioPath)
            require(audioFile.isFile) { "Recorded voice audio is no longer available" }
            val audio = withContext(Dispatchers.IO) { audioFile.readBytes() }
            require(audio.isNotEmpty()) { "Recorded voice audio is empty" }
            transcriber.finish(audio, job.totalSamples)
            val outcome = result.await()
            outcome.fold(
                onSuccess = { text ->
                    val completed =
                        (store.load() ?: job).copy(
                            phase = VoiceJobPhase.COMPLETE,
                            completedSamples = job.totalSamples,
                            elapsedMs = (store.load() ?: job).elapsedMs,
                            transcript = text.trim(),
                            error = null,
                        )
                    store.save(completed)
                    broadcast(completed)
                    showCompletedNotification(completed)
                },
                onFailure = { error -> failJob(job, error) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failJob(job, error)
        } finally {
            activeTranscriber = null
            whisper?.close()
            whisper = null
            audioFile(job).delete()
            if (stopped.get()) stopSelfResult(startId)
        }
    }

    private fun cancelJob() {
        val job =
            store.load()
                ?: run {
                    stopSelf()
                    return
                }
        activeTranscriber?.cancel()
        worker?.cancel()
        store.save(job.copy(phase = VoiceJobPhase.CANCELED, error = "Canceled"))
        audioFile(job).delete()
        broadcast(store.load() ?: job)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failJob(job: VoiceJob, error: Throwable) {
        if (error is CancellationException) return
        val failed =
            (store.load() ?: job).copy(
                phase = VoiceJobPhase.FAILED,
                error = error.message ?: error.toString(),
            )
        store.save(failed)
        broadcast(failed)
        showCompletedNotification(failed)
    }

    private fun updateProgress(
        id: String,
        status: String?,
        update: VoiceTranscriptionSnapshot? = null,
    ) {
        val job = store.load()?.takeIf { it.id == id } ?: return
        val current =
            update
                ?: VoiceTranscriptionSnapshot(
                    "",
                    job.completedSamples,
                    job.totalSamples,
                    false,
                    job.elapsedMs,
                )
        val label =
            status
                ?: "Audio ${formatVoiceDuration(current.completedSamples)} of ${formatVoiceDuration(current.recordedSamples)}" +
                    (current.transcriptionElapsedMs?.let {
                        " • ${formatElapsedDuration(it)} elapsed"
                    } ?: "")
        val progress =
            if (current.recordedSamples > 0)
                (current.completedSamples.toFloat() / current.recordedSamples).coerceIn(0f, 1f)
            else null
        val notification = notification(job, label, progress, true)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        broadcast(job)
    }

    private fun showCompletedNotification(job: VoiceJob) {
        val title =
            if (job.phase == VoiceJobPhase.COMPLETE) "Voice transcription complete"
            else "Voice transcription failed"
        val text = job.transcript ?: job.error ?: "Voice transcription finished"
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(job, "$title: $text", null, false))
        stopSelf()
    }

    private fun notification(
        job: VoiceJob,
        text: String,
        progress: Float?,
        ongoing: Boolean,
    ): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancelIntent =
            PendingIntent.getService(
                this,
                REQUEST_CANCEL,
                Intent(this, VoiceTranscriptionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Local Whisper")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (ongoing) builder.addAction(0, "Cancel", cancelIntent)
        if (progress != null) builder.setProgress(100, (progress * 100).toInt(), false)
        return builder.build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Local voice transcription",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    private fun audioFile(job: VoiceJob) = File(job.audioPath)

    private fun broadcast(job: VoiceJob) {
        sendBroadcast(
            Intent(ACTION_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_JOB_ID, job.id)
                .putExtra(EXTRA_PHASE, job.phase.name)
                .putExtra(EXTRA_STORED_SESSION_ID, job.storedSessionId)
                .putExtra(EXTRA_RUNTIME_SESSION_ID, job.runtimeSessionId)
                .putExtra(EXTRA_MODEL_ID, job.modelId)
                .putExtra(EXTRA_COMPLETED_SAMPLES, job.completedSamples)
                .putExtra(EXTRA_TOTAL_SAMPLES, job.totalSamples)
                .putExtra(EXTRA_ELAPSED_MS, job.elapsedMs ?: -1L)
                .putExtra(EXTRA_TRANSCRIPT, job.transcript)
                .putExtra(EXTRA_ERROR, job.error)
        )
    }

    companion object {
        const val ACTION_START = "dev.qelg.harnessandroid.voice.VOICE_JOB_START"
        const val ACTION_UPDATE = "dev.qelg.harnessandroid.voice.VOICE_JOB_UPDATE"
        const val ACTION_CANCEL = "dev.qelg.harnessandroid.voice.VOICE_JOB_CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_STORED_SESSION_ID = "stored_session_id"
        const val EXTRA_RUNTIME_SESSION_ID = "runtime_session_id"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_COMPLETED_SAMPLES = "completed_samples"
        const val EXTRA_TOTAL_SAMPLES = "total_samples"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "local_voice_transcription"
        private const val NOTIFICATION_ID = 401
        private const val REQUEST_OPEN = 402
        private const val REQUEST_CANCEL = 403

        fun start(context: Context, job: VoiceJob) {
            VoiceJobStore(context).save(job.copy(phase = VoiceJobPhase.TRANSCRIBING))
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceTranscriptionService::class.java).setAction(ACTION_START),
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, VoiceTranscriptionService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
