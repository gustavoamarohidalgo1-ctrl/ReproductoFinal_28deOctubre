package com.reproductor.audiolibros

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks decoded PCM reaching the production sink, not physical speaker output. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HighResolutionAudioInstrumentedTest {
    @Test fun wav24AndFlac24RetainTheirLowBitsThroughTheProductionDecoderPath() {
        for (name in listOf("sentinels-24.wav", "sentinels-24.flac")) verify(name)
    }

    private fun verify(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "fidelity-$name")
        instrumentation.context.assets.open("high_resolution/$name").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val originalHash = sha256(file)
        val captured = ByteArrayOutputStream()
        val format = AtomicReference<Format>()
        val error = AtomicReference<PlaybackException>()
        val finished = CountDownLatch(1)
        val factory = object : ContinuousAudioRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                return object : ForwardingAudioSink(sink) {
                    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                        format.set(inputFormat)
                        super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    }

                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long,
                        encodedAccessUnitCount: Int): Boolean {
                        val start = buffer.position()
                        val copy = buffer.duplicate()
                        val result = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                        val count = buffer.position() - start
                        if (count > 0) {
                            val bytes = ByteArray(count)
                            copy.get(bytes)
                            captured.write(bytes)
                        }
                        return result
                    }
                }
            }
        }
        factory.setEnableAudioFloatOutput(true)
        factory.setEnableDecoderFallback(true)
        val playerRef = AtomicReference<ExoPlayer>()
        try {
            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(context, factory)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                    .build()
                playerRef.set(player)
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) finished.countDown()
                    }
                    override fun onPlayerError(e: PlaybackException) { error.set(e); finished.countDown() }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
            }
            assertTrue("$name decoder timed out", finished.await(20, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("$name decoder failed", it) }
            val decoded = format.get() ?: throw AssertionError("$name produced no PCM format")
            assertEquals("$name sample rate", 48_000, decoded.sampleRate)
            assertEquals("$name channels", 2, decoded.channelCount)
            val bytes = captured.toByteArray()
            val width = when (decoded.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AssertionError("$name unsupported decoded encoding ${decoded.pcmEncoding}")
            }
            assertEquals("$name decoded byte count", 12_000 * 2 * width, bytes.size)
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(12_000) { i ->
                val expected = intArrayOf(((i * 7919) % 1000001) - 500000,
                    ((i * 3571) % 900001) - 450000)
                for (sample in expected) {
                    val value = when (decoded.pcmEncoding) {
                        C.ENCODING_PCM_16BIT -> data.short.toDouble() / 32768.0
                        C.ENCODING_PCM_24BIT -> {
                            val x = (data.get().toInt() and 255) or ((data.get().toInt() and 255) shl 8) or (data.get().toInt() shl 16)
                            x.toDouble() / 8388608.0
                        }
                        C.ENCODING_PCM_32BIT -> data.int.toDouble() / 2147483648.0
                        else -> data.float.toDouble()
                    }
                    assertEquals("$name frame $i sample $sample encoding ${decoded.pcmEncoding}",
                        sample.toDouble() / 8388608.0, value, 0.0)
                }
            }
            assertArrayEquals("$name source changed", originalHash, sha256(file))
        } finally {
            instrumentation.runOnMainSync { playerRef.get()?.release() }
            file.delete()
        }
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
