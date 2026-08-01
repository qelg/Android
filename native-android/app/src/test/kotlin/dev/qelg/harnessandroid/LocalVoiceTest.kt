package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.voice.PcmChunkAccumulator
import dev.qelg.harnessandroid.voice.WhisperCpuConfig
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
    fun pcmAccumulatorEmitsCompleteChunksAndKeepsOnlyTheTail() {
        val accumulator = PcmChunkAccumulator(chunkBytes = 4)

        assertEquals(emptyList<ByteArray>(), accumulator.add(byteArrayOf(1, 2, 3), 3))
        val chunks = accumulator.add(byteArrayOf(4, 5, 6, 7, 8, 9), 6)

        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunks[0])
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), chunks[1])
        assertArrayEquals(byteArrayOf(9), accumulator.remaining())
        assertEquals(9, accumulator.totalBytes)
    }

    @Test
    fun whisperModelsResolvePersistedIdsAndExposeDownloadSizes() {
        assertEquals(WhisperModel.Small, WhisperModel.fromId("small"))
        assertEquals(WhisperModel.Base, WhisperModel.fromId("unknown"))
        assertEquals("32 MB", WhisperModel.Tiny.downloadSize)
        assertEquals("574 MB", WhisperModel.LargeTurbo.downloadSize)
    }

    @Test
    fun whisperAutomaticThreadsUseConservativeFourThreadDefault() {
        assertEquals(1, WhisperCpuConfig.automaticThreadCountFor(1))
        assertEquals(2, WhisperCpuConfig.automaticThreadCountFor(2))
        assertEquals(4, WhisperCpuConfig.automaticThreadCountFor(8))
        assertEquals(4, WhisperCpuConfig.automaticThreadCountFor(16))
    }

    @Test
    fun whisperThreadSettingAcceptsAutomaticAndOneThroughEight() {
        assertEquals(true, WhisperCpuConfig.isValid(WhisperCpuConfig.AUTOMATIC))
        assertEquals(true, WhisperCpuConfig.isValid(1))
        assertEquals(true, WhisperCpuConfig.isValid(8))
        assertEquals(false, WhisperCpuConfig.isValid(-1))
        assertEquals(false, WhisperCpuConfig.isValid(9))
        (1..8).forEach { configured ->
            assertEquals(configured, WhisperCpuConfig.resolve(configured))
        }
    }
}
