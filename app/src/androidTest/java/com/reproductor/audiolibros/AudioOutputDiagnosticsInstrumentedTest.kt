package com.reproductor.audiolibros

import android.media.AudioFormat
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioOutputDiagnosticsInstrumentedTest {
    @Test
    fun stateAndTrackEventsKeepBoundedCountersAndLastMonotonicTimes() {
        var changes = 0
        val diagnostics = AudioOutputDiagnostics { changes++ }
        fun event(now: Long) = AnalyticsListener.EventTime(
            now, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, C.TIME_UNSET
        )
        val config = AudioSink.AudioTrackConfig(C.ENCODING_PCM_FLOAT, 48_000,
            AudioFormat.CHANNEL_OUT_STEREO, false, false, 384_000)
        repeat(1_000) {
            diagnostics.onPlaybackStateChanged(event(it.toLong()), Player.STATE_BUFFERING)
            diagnostics.onPlaybackStateChanged(event(it.toLong()), Player.STATE_READY)
        }
        diagnostics.onIsPlayingChanged(event(1_001), true)
        diagnostics.onAudioTrackInitialized(event(1_002), config)
        diagnostics.onAudioTrackReleased(event(1_003), config)
        val snapshot = Bundle().also(diagnostics::appendTo)
        assertEquals(2_003, changes)
        assertEquals(1_000L, snapshot.getLong(AudioOutputDiagnostics.BUFFERING_EVENTS))
        assertEquals(1_000L, snapshot.getLong(AudioOutputDiagnostics.READY_EVENTS))
        assertEquals(999L, snapshot.getLong(AudioOutputDiagnostics.LAST_STATE_AT))
        assertEquals(Player.STATE_READY, snapshot.getInt(AudioOutputDiagnostics.PLAYBACK_STATE))
        assertEquals(true, snapshot.getBoolean(AudioOutputDiagnostics.IS_PLAYING))
        assertEquals(1_001L, snapshot.getLong(AudioOutputDiagnostics.LAST_PLAYING_AT))
        assertEquals(1L, snapshot.getLong(AudioOutputDiagnostics.TRACK_INITIALIZATIONS))
        assertEquals(1L, snapshot.getLong(AudioOutputDiagnostics.TRACK_RELEASES))
        assertEquals(1_002L, snapshot.getLong(AudioOutputDiagnostics.LAST_TRACK_INIT_AT))
        assertEquals(1_003L, snapshot.getLong(AudioOutputDiagnostics.LAST_TRACK_RELEASE_AT))
        assertEquals(C.TIME_UNSET, snapshot.getLong(AudioOutputDiagnostics.LAST_PLAYER_ERROR_AT))
    }

    @Test
    fun snapshotsCountEventsPreserveUnsetAndAreIndependent() {
        var changes = 0
        val diagnostics = AudioOutputDiagnostics { changes++ }
        fun event(now: Long) = AnalyticsListener.EventTime(
            now, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, C.TIME_UNSET
        )
        diagnostics.onAudioUnderrun(event(1), 100, 750, 5)
        diagnostics.onAudioUnderrun(event(2), 200, C.TIME_UNSET, 9)
        diagnostics.onAudioSinkError(event(3), IllegalStateException())
        diagnostics.onAudioSinkError(event(4), IllegalStateException())
        val before = Bundle().also(diagnostics::appendTo)
        diagnostics.onAudioTrackInitialized(event(5), AudioSink.AudioTrackConfig(
            C.ENCODING_PCM_FLOAT, 96_000, AudioFormat.CHANNEL_OUT_STEREO, false, false, 576_000
        ))
        val after = Bundle().also(diagnostics::appendTo)
        assertEquals(5, changes)
        assertEquals(2L, after.getLong(AudioOutputDiagnostics.AUDIO_UNDERRUNS))
        assertEquals(2L, after.getLong(AudioOutputDiagnostics.AUDIO_SINK_ERRORS))
        assertEquals(C.TIME_UNSET, after.getLong(AudioOutputDiagnostics.AUDIO_BUFFER_SIZE_MS))
        assertEquals(C.TIME_UNSET, after.getLong(AudioOutputDiagnostics.AUDIO_MEDIA_BUFFER_MS))
        assertEquals(9L, after.getLong(AudioOutputDiagnostics.AUDIO_FEED_GAP_MS))
        assertEquals(576_000, after.getInt(AudioOutputDiagnostics.AUDIO_TRACK_BUFFER_SIZE))
        assertEquals(96_000, after.getInt(AudioOutputDiagnostics.AUDIO_TRACK_SAMPLE_RATE))
        assertEquals(C.ENCODING_PCM_FLOAT, after.getInt(AudioOutputDiagnostics.AUDIO_TRACK_ENCODING))
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, after.getInt(AudioOutputDiagnostics.AUDIO_TRACK_CHANNEL_CONFIG))
        assertEquals(0, before.getInt(AudioOutputDiagnostics.AUDIO_TRACK_BUFFER_SIZE))
        after.putLong(AudioOutputDiagnostics.AUDIO_UNDERRUNS, 99)
        assertEquals(2L, before.getLong(AudioOutputDiagnostics.AUDIO_UNDERRUNS))
        assertEquals(2L, Bundle().also(diagnostics::appendTo).getLong(AudioOutputDiagnostics.AUDIO_UNDERRUNS))
    }
}
