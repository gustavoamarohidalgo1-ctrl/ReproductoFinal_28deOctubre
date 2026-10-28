package com.reproductor.audiolibros

import android.media.AudioFormat
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Timeline
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
