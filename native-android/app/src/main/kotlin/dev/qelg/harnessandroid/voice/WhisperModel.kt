package dev.qelg.harnessandroid.voice

import android.content.Context

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val downloadSize: String
        get() =
            when {
                sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
                else -> "${sizeBytes / 1_000_000} MB"
            }

    companion object {
        val Tiny =
            WhisperModel(
                id = "tiny",
                displayName = "Tiny",
                fileName = "ggml-tiny-q5_1.bin",
                sizeBytes = 32_152_673L,
                sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
            )
        val Base =
            WhisperModel(
                id = "base",
                displayName = "Base",
                fileName = "ggml-base-q5_1.bin",
                sizeBytes = 59_707_625L,
                sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            )
        val Small =
            WhisperModel(
                id = "small",
                displayName = "Small",
                fileName = "ggml-small-q5_1.bin",
                sizeBytes = 190_085_487L,
                sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            )
        val Medium =
            WhisperModel(
                id = "medium",
                displayName = "Medium",
                fileName = "ggml-medium-q5_0.bin",
                sizeBytes = 539_212_467L,
                sha256 = "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
            )
        val LargeTurbo =
            WhisperModel(
                id = "large-turbo",
                displayName = "Large v3 Turbo",
                fileName = "ggml-large-v3-turbo-q5_0.bin",
                sizeBytes = 574_041_195L,
                sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
            )

        val All = listOf(Tiny, Base, Small, Medium, LargeTurbo)

        fun fromId(id: String?): WhisperModel = All.firstOrNull { it.id == id } ?: Base
    }
}

class WhisperModelStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_whisper", Context.MODE_PRIVATE)

    fun load(): WhisperModel = WhisperModel.fromId(preferences.getString(MODEL_KEY, null))

    fun save(model: WhisperModel) {
        preferences.edit().putString(MODEL_KEY, model.id).apply()
    }

    fun loadThreadCount(): Int {
        val configured = preferences.getInt(THREAD_COUNT_KEY, WhisperCpuConfig.AUTOMATIC)
        return configured.takeIf(WhisperCpuConfig::isValid) ?: WhisperCpuConfig.AUTOMATIC
    }

    fun saveThreadCount(configured: Int) {
        require(WhisperCpuConfig.isValid(configured))
        preferences.edit().putInt(THREAD_COUNT_KEY, configured).apply()
    }

    private companion object {
        const val MODEL_KEY = "model"
        const val THREAD_COUNT_KEY = "thread_count"
    }
}
