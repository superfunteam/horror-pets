package team.superfun.horrorpets.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseWavDurationTest {
    /** A canonical 44-byte PCM WAV header: RIFF, a 16-byte `fmt ` chunk, then the `data` chunk header. */
    private fun header(byteRate: Int, dataSize: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(byteRate / 2) // sample rate
            putInt(byteRate)
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

    @Test
    fun durationComesFromTheDataSizeAndByteRate() {
        assertEquals(1_000L, parseWavDurationMs(header(88_200, 88_200), 44L + 88_200))
        assertEquals(500L, parseWavDurationMs(header(88_200, 44_100), 44L + 44_100))
    }

    @Test
    fun aStreamingSizeFallsBackToTheFileLength() {
        assertEquals(2_000L, parseWavDurationMs(header(88_200, 0), 44L + 176_400))
    }

    @Test
    fun notAWaveFileIsNull() {
        assertNull(parseWavDurationMs("not a wav file at all".toByteArray(), 100L))
        assertNull(parseWavDurationMs(ByteArray(4), 4L))
    }

    @Test
    fun aFileShorterThanItsHeaderNeverGivesANegativeDuration() {
        // An unknown asset length (AssetFileDescriptor.UNKNOWN_LENGTH is -1) or a truncated file.
        assertEquals(0L, parseWavDurationMs(header(1_000, 0), -1L))
        assertEquals(0L, parseWavDurationMs(header(1_000, 0), 10L))
    }
}
