package com.reproductor.audiolibros

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated capacity experiment; it does not reproduce a real device's workload. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioTrackStarvationInstrumentedTest {
    @Test
    fun largerPrefilledPcmBufferToleratesControlledFeedPause() {
        val rate = 48_000
        val frameBytes = 4 // stereo PCM16
        val oldFrames = rate * 3 / 4
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        assumeTrue("Platform minimum must fit the baseline buffer", minimum in 1..oldFrames * frameBytes)
        val newFrames = continuousPcmBufferSizeProvider().getBufferSizeInBytes(
            minimum, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
            frameBytes, rate, Format.NO_VALUE, 1.0
        ) / frameBytes

        fun run(frames: Int): Int {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(frames * frameBytes)
                .build()
            try {
                assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
                assumeTrue("Platform changed the requested buffer capacity", track.bufferSizeInFrames == frames)
                val data = ByteArray(frames * frameBytes)
                assertEquals(data.size, track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING))
                track.play()
                val deadline = SystemClock.elapsedRealtime() + 3_000
                while (track.playbackHeadPosition == 0 && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(5)
                }
                assertTrue("AudioTrack did not start advancing", track.playbackHeadPosition > 0)
                val before = track.underrunCount
                val started = SystemClock.elapsedRealtime()
                SystemClock.sleep(1_200)
                val elapsed = SystemClock.elapsedRealtime() - started
                assumeTrue("Host scheduling delayed the controlled pause", elapsed in 1_200..1_600)
                return track.underrunCount - before
            } finally {
                track.release()
            }
        }

        val baselineUnderruns = run(oldFrames)
        val enlargedUnderruns = run(newFrames)
        Log.i("AudioBufferComparison", "baselineFrames=$oldFrames enlargedFrames=$newFrames " +
            "feedPauseMs=1200 baselineUnderruns=$baselineUnderruns enlargedUnderruns=$enlargedUnderruns")
        assertTrue("Baseline should underrun after the feed pause", baselineUnderruns > 0)
        assertEquals("The prefilled two-second buffer should survive this pause", 0, enlargedUnderruns)
    }
}
