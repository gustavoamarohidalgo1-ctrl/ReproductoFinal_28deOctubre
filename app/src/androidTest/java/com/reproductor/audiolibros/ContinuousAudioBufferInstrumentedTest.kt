package com.reproductor.audiolibros

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ContinuousAudioBufferInstrumentedTest {
    @Test
    fun pcmBufferUsesConfiguredDurationBoundsAcrossFormats() {
        val provider = continuousPcmBufferSizeProvider()
        val defaults = DefaultAudioTrackBufferSizeProvider.Builder().build()
        for ((encoding, sampleRate, frameSize) in listOf(
            Triple(C.ENCODING_PCM_16BIT, 44_100, 4),
            Triple(C.ENCODING_PCM_FLOAT, 96_000, 8),
            Triple(C.ENCODING_PCM_FLOAT, 192_000, 8)
        )) {
            fun size(p: DefaultAudioTrackBufferSizeProvider, minimum: Int) =
                p.getBufferSizeInBytes(
                    minimum, encoding, DefaultAudioSink.OUTPUT_MODE_PCM,
                    frameSize, sampleRate, Format.NO_VALUE, 1.0
                )
            val bytesPerSecond = sampleRate * frameSize
            val smallPlatformMinimum = bytesPerSecond / 20
            assertEquals(bytesPerSecond * 2, size(provider, smallPlatformMinimum))
            assertTrue(size(provider, smallPlatformMinimum) > size(defaults, smallPlatformMinimum))

            val largePlatformMinimum = bytesPerSecond * 3 / 4
            assertEquals(bytesPerSecond * 3, size(provider, largePlatformMinimum))
            assertEquals(bytesPerSecond * 3 / 4, size(defaults, largePlatformMinimum))
        }
    }
}
