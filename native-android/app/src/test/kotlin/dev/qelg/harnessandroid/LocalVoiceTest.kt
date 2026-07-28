package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.voice.WhisperModel
import dev.qelg.harnessandroid.voice.pcm16ToFloat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalVoiceTest {
    @Test
    fun pcm16LittleEndianConvertsToNormalizedFloats() {
        assertArrayEquals(
            floatArrayOf(-1f, 0f, 32767f / 32768f),
            pcm16ToFloat(byteArrayOf(0, -128, 0, 0, -1, 127)),
            0f,
        )
    }

    @Test
    fun whisperModelsResolvePersistedIdsAndExposeDownloadSizes() {
        assertEquals(WhisperModel.Small, WhisperModel.fromId("small"))
        assertEquals(WhisperModel.Base, WhisperModel.fromId("unknown"))
        assertEquals("32 MB", WhisperModel.Tiny.downloadSize)
        assertEquals("574 MB", WhisperModel.LargeTurbo.downloadSize)
    }
}
