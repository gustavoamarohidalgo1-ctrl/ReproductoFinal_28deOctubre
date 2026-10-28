package com.reproductor.audiolibros

import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/** In-memory output diagnostics for the lifetime of the service. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioOutputDiagnostics(private val onChanged: () -> Unit) : AnalyticsListener {
    private var underruns = 0L
    private var sinkErrors = 0L
    private var lastBufferSizeMs = C.TIME_UNSET
    private var lastFeedGapMs = C.TIME_UNSET
    private var lastMediaBufferMs = C.TIME_UNSET
    private var trackBufferSize = 0
    private var trackSampleRate = 0
    private var trackEncoding = C.ENCODING_INVALID
    private var trackChannelConfig = 0
    private var lastUnderrunLogMs: Long? = null
    private var lastEventLogMs: Long? = null
    private var lastSinkErrorLogMs: Long? = null
    private var lastPlayerErrorLogMs: Long? = null
    private var lastUnderrunAt = C.TIME_UNSET
    private var lastSinkErrorAt = C.TIME_UNSET
    private var trackInitializations = 0L
    private var trackReleases = 0L
    private var lastTrackInitAt = C.TIME_UNSET
    private var lastTrackReleaseAt = C.TIME_UNSET
    private var bufferingEvents = 0L
    private var readyEvents = 0L
    private var lastStateAt = C.TIME_UNSET
    private var playbackState = Player.STATE_IDLE
    private var playingChanges = 0L
    private var lastPlayingAt = C.TIME_UNSET
    private var isPlaying = false
    private var playerErrors = 0L
    private var lastPlayerErrorAt = C.TIME_UNSET
    private var lastPlayerErrorCode = 0

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        underruns++
        lastUnderrunAt = eventTime.realtimeMs
        lastBufferSizeMs = bufferSizeMs
        lastFeedGapMs = elapsedSinceLastFeedMs
        lastMediaBufferMs = eventTime.totalBufferedDurationMs
        val previous = lastUnderrunLogMs
        if (previous == null || eventTime.realtimeMs - previous >= LOG_INTERVAL_MS) {
            lastUnderrunLogMs = eventTime.realtimeMs
            Log.w(TAG, "Audio underruns=$underruns elapsedMs=${eventTime.realtimeMs} positionMs=${eventTime.currentPlaybackPositionMs} sinkErrors=$sinkErrors bufferMs=$bufferSizeMs " +
                "feedGapMs=$elapsedSinceLastFeedMs mediaBufferMs=$lastMediaBufferMs")
        }
        onChanged()
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        sinkErrors++
        lastSinkErrorAt = eventTime.realtimeMs
        if (shouldLog(eventTime.realtimeMs, lastSinkErrorLogMs)) {
            lastSinkErrorLogMs = eventTime.realtimeMs
            writeEventLog(eventTime, "sinkError count=$sinkErrors")
        }
        onChanged()
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        trackBufferSize = audioTrackConfig.bufferSize
        trackSampleRate = audioTrackConfig.sampleRate
        trackEncoding = audioTrackConfig.encoding
        trackChannelConfig = audioTrackConfig.channelConfig
        trackInitializations++
        lastTrackInitAt = eventTime.realtimeMs
        logEvent(eventTime, "trackInit count=$trackInitializations rate=$trackSampleRate encoding=$trackEncoding bufferBytes=$trackBufferSize")
        onChanged()
    }

    override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        trackReleases++
        lastTrackReleaseAt = eventTime.realtimeMs
        logEvent(eventTime, "trackRelease count=$trackReleases")
        onChanged()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        playbackState = state
        lastStateAt = eventTime.realtimeMs
        if (state == Player.STATE_BUFFERING) bufferingEvents++
        if (state == Player.STATE_READY) readyEvents++
        logEvent(eventTime, "state=$state buffering=$bufferingEvents ready=$readyEvents")
        onChanged()
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, playing: Boolean) {
        isPlaying = playing
        playingChanges++
        lastPlayingAt = eventTime.realtimeMs
        logEvent(eventTime, "isPlaying=$playing changes=$playingChanges")
        onChanged()
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        playerErrors++
        lastPlayerErrorAt = eventTime.realtimeMs
        lastPlayerErrorCode = error.errorCode
        if (shouldLog(eventTime.realtimeMs, lastPlayerErrorLogMs)) {
            lastPlayerErrorLogMs = eventTime.realtimeMs
            writeEventLog(eventTime, "playerError count=$playerErrors code=$lastPlayerErrorCode")
        }
        onChanged()
    }

    private fun logEvent(eventTime: AnalyticsListener.EventTime, message: String) {
        val previous = lastEventLogMs
        if (shouldLog(eventTime.realtimeMs, previous)) {
            lastEventLogMs = eventTime.realtimeMs
            writeEventLog(eventTime, message)
        }
    }

    private fun shouldLog(now: Long, previous: Long?): Boolean =
        previous == null || now < previous || now - previous >= EVENT_LOG_INTERVAL_MS

    private fun writeEventLog(eventTime: AnalyticsListener.EventTime, message: String) {
        Log.i(TAG, "elapsedMs=${eventTime.realtimeMs} positionMs=${eventTime.currentPlaybackPositionMs} $message")
    }

    fun appendTo(bundle: Bundle) {
        bundle.putLong(AUDIO_UNDERRUNS, underruns)
        bundle.putLong(AUDIO_SINK_ERRORS, sinkErrors)
        bundle.putLong(AUDIO_BUFFER_SIZE_MS, lastBufferSizeMs)
        bundle.putLong(AUDIO_FEED_GAP_MS, lastFeedGapMs)
        bundle.putLong(AUDIO_MEDIA_BUFFER_MS, lastMediaBufferMs)
        bundle.putInt(AUDIO_TRACK_BUFFER_SIZE, trackBufferSize)
        bundle.putInt(AUDIO_TRACK_SAMPLE_RATE, trackSampleRate)
        bundle.putInt(AUDIO_TRACK_ENCODING, trackEncoding)
        bundle.putInt(AUDIO_TRACK_CHANNEL_CONFIG, trackChannelConfig)
        bundle.putLong(LAST_UNDERRUN_AT, lastUnderrunAt)
        bundle.putLong(LAST_SINK_ERROR_AT, lastSinkErrorAt)
        bundle.putLong(TRACK_INITIALIZATIONS, trackInitializations)
        bundle.putLong(TRACK_RELEASES, trackReleases)
        bundle.putLong(LAST_TRACK_INIT_AT, lastTrackInitAt)
        bundle.putLong(LAST_TRACK_RELEASE_AT, lastTrackReleaseAt)
        bundle.putLong(BUFFERING_EVENTS, bufferingEvents)
        bundle.putLong(READY_EVENTS, readyEvents)
        bundle.putLong(LAST_STATE_AT, lastStateAt)
        bundle.putInt(PLAYBACK_STATE, playbackState)
        bundle.putLong(PLAYING_CHANGES, playingChanges)
        bundle.putLong(LAST_PLAYING_AT, lastPlayingAt)
        bundle.putBoolean(IS_PLAYING, isPlaying)
        bundle.putLong(PLAYER_ERRORS, playerErrors)
        bundle.putLong(LAST_PLAYER_ERROR_AT, lastPlayerErrorAt)
        bundle.putInt(LAST_PLAYER_ERROR_CODE, lastPlayerErrorCode)
    }

    companion object {
        const val AUDIO_UNDERRUNS = "com.reproductor.audiolibros.AUDIO_UNDERRUNS"
        const val AUDIO_SINK_ERRORS = "com.reproductor.audiolibros.AUDIO_SINK_ERRORS"
        const val AUDIO_BUFFER_SIZE_MS = "com.reproductor.audiolibros.AUDIO_BUFFER_SIZE_MS"
        const val AUDIO_FEED_GAP_MS = "com.reproductor.audiolibros.AUDIO_FEED_GAP_MS"
        const val AUDIO_MEDIA_BUFFER_MS = "com.reproductor.audiolibros.AUDIO_MEDIA_BUFFER_MS"
        const val AUDIO_TRACK_BUFFER_SIZE = "com.reproductor.audiolibros.AUDIO_BUFFER_BYTES"
        const val AUDIO_TRACK_SAMPLE_RATE = "com.reproductor.audiolibros.AUDIO_SAMPLE_RATE"
        const val AUDIO_TRACK_ENCODING = "com.reproductor.audiolibros.AUDIO_ENCODING"
        const val AUDIO_TRACK_CHANNEL_CONFIG = "com.reproductor.audiolibros.AUDIO_CHANNEL_MASK"
        const val LAST_UNDERRUN_AT = "diag.lastUnderrunAtMs"
        const val LAST_SINK_ERROR_AT = "diag.lastSinkErrorAtMs"
        const val TRACK_INITIALIZATIONS = "diag.trackInitializations"
        const val TRACK_RELEASES = "diag.trackReleases"
        const val LAST_TRACK_INIT_AT = "diag.lastTrackInitAtMs"
        const val LAST_TRACK_RELEASE_AT = "diag.lastTrackReleaseAtMs"
        const val BUFFERING_EVENTS = "diag.bufferingEvents"
        const val READY_EVENTS = "diag.readyEvents"
        const val LAST_STATE_AT = "diag.lastStateAtMs"
        const val PLAYBACK_STATE = "diag.playbackState"
        const val PLAYING_CHANGES = "diag.playingChanges"
        const val LAST_PLAYING_AT = "diag.lastPlayingAtMs"
        const val IS_PLAYING = "diag.isPlaying"
        const val PLAYER_ERRORS = "diag.playerErrors"
        const val LAST_PLAYER_ERROR_AT = "diag.lastPlayerErrorAtMs"
        const val LAST_PLAYER_ERROR_CODE = "diag.lastPlayerErrorCode"
        private const val TAG = "AudioOutputDiagnostics"
        private const val LOG_INTERVAL_MS = 30_000L
        private const val EVENT_LOG_INTERVAL_MS = 1_000L
    }
}
