package com.reproductor.audiolibros

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for Media3 1.5.1's float path, which omitted gapless trimming. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FloatGaplessAudioSinkInstrumentedTest {
    @Test
    fun repeatedFloatAudioTrimsOnlyEncoderPaddingAndPreservesRemainingSamples() {
        val thread = HandlerThread("FloatGaplessSinkTest").apply { start() }
        val task = FutureTask {
            val acceptedBytes = ByteArrayOutputStream()
            val outputConfigurations = mutableListOf<AudioSink.AudioTrackConfig>()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setAudioTrackBufferSizeProvider(continuousPcmBufferSizeProvider())
                .setAudioTrackProvider { configuration, attributes, sessionId ->
                    outputConfigurations.add(configuration)
                    RecordingAudioTrack(configuration, attributes, sessionId, acceptedBytes)
                }
                .build()
            try {
                val format = Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelCount(CHANNELS)
                    .build()

                // The real sink has an initial 20 ms volume ramp. Finish that ramp
                // with silence before checking sample identity across transitions.
                // No play() is needed: these few KB fit in the real AudioTrack buffer.
                val warmup = ByteArray(WARMUP_FRAMES * BYTES_PER_FRAME)
                sink.configure(format, 0, null)
                submit(sink, warmup, 0)

                val source = ByteBuffer.allocate(SOURCE_FRAMES * BYTES_PER_FRAME)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(SOURCE_FRAMES * CHANNELS) { index ->
                    // Low mantissa bits would disappear if converted to PCM16.
                    val value = Float.fromBits(0x3d000001 + index * 113)
                    source.putFloat(if (index % 2 == 0) value else -value)
                }
                val sourceBytes = source.array()
                val expectedLoop = sourceBytes.copyOfRange(
                    DELAY_FRAMES * BYTES_PER_FRAME,
                    (SOURCE_FRAMES - PADDING_FRAMES) * BYTES_PER_FRAME
                )
                val gaplessFormat = format.buildUpon()
                    .setEncoderDelay(DELAY_FRAMES)
                    .setEncoderPadding(PADDING_FRAMES)
                    .build()

                repeat(2) { loop ->
                    sink.configure(gaplessFormat, 0, null)
                    var offsetFrames = 0
                    // Split the leading delay across writes and retain the trailing
                    // padding across writes, as a decoder can do with real MP3 audio.
                    for (frames in intArrayOf(1, 2, 4, 17, 104)) {
                        val bytes = sourceBytes.copyOfRange(
                            offsetFrames * BYTES_PER_FRAME,
                            (offsetFrames + frames) * BYTES_PER_FRAME
                        )
                        val timestampFrames = WARMUP_FRAMES +
                            loop * (SOURCE_FRAMES - DELAY_FRAMES - PADDING_FRAMES) + offsetFrames
                        submit(sink, bytes, timestampFrames.toLong() * 1_000_000 / SAMPLE_RATE)
                        offsetFrames += frames
                    }
                }
                // A natural transition reconfigures before draining; this tells
                // Media3 to discard the previous item's remaining encoder padding.
                sink.configure(gaplessFormat, 0, null)
                submit(sink, byteArrayOf(), 0)

                assertEquals("A gapless transition must reuse its AudioTrack", 1, outputConfigurations.size)
                val output = outputConfigurations.single()
                assertEquals(C.ENCODING_PCM_FLOAT, output.encoding)
                assertEquals(SAMPLE_RATE, output.sampleRate)
                assertEquals(AudioFormat.CHANNEL_OUT_STEREO, output.channelConfig)
                assertFalse(output.offload)
                assertArrayEquals(warmup + expectedLoop + expectedLoop, acceptedBytes.toByteArray())
            } finally {
                sink.reset()
                sink.release()
            }
        }
        try {
            Handler(thread.looper).post(task)
            task.get(15, TimeUnit.SECONDS)
        } finally {
            thread.quitSafely()
            thread.join(2_000)
        }
    }

    private fun submit(sink: AudioSink, bytes: ByteArray, timestampUs: Long) {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(bytes).flip()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!sink.handleBuffer(buffer, timestampUs, 1)) {
            assertTrue("AudioTrack did not accept the test buffer", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(5)
        }
        assertFalse("The sink must consume the complete input buffer", buffer.hasRemaining())
    }

    /** Observes only bytes the actual platform AudioTrack accepted; no fake writes. */
    private class RecordingAudioTrack(
        configuration: AudioSink.AudioTrackConfig,
        attributes: AudioAttributes,
        sessionId: Int,
        private val acceptedBytes: ByteArrayOutputStream
    ) : AudioTrack(
        attributes.audioAttributesV21.audioAttributes,
        AudioFormat.Builder()
            .setEncoding(configuration.encoding)
            .setSampleRate(configuration.sampleRate)
            .setChannelMask(configuration.channelConfig)
            .build(),
        configuration.bufferSize,
        MODE_STREAM,
        sessionId
    ) {
        override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int): Int {
            val original = audioData.duplicate()
            val written = super.write(audioData, sizeInBytes, writeMode)
            if (written > 0) {
                val bytes = ByteArray(written)
                original.get(bytes)
                acceptedBytes.write(bytes)
            }
            return written
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = CHANNELS * 4
        private const val WARMUP_FRAMES = 1_024
        private const val SOURCE_FRAMES = 128
        private const val DELAY_FRAMES = 3
        private const val PADDING_FRAMES = 5
    }
}
