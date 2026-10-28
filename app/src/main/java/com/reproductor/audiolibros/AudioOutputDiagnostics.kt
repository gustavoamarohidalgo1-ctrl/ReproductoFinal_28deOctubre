package com.reproductor.audiolibros

import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
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

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        underruns++
        lastBufferSizeMs = bufferSizeMs
        lastFeedGapMs = elapsedSinceLastFeedMs
        lastMediaBufferMs = eventTime.totalBufferedDurationMs
        val previous = lastUnderrunLogMs
        if (previous == null || eventTime.realtimeMs - previous >= LOG_INTERVAL_MS) {
            lastUnderrunLogMs = eventTime.realtimeMs
            Log.w(TAG, "Audio underruns=$underruns sinkErrors=$sinkErrors bufferMs=$bufferSizeMs " +
                "feedGapMs=$elapsedSinceLastFeedMs mediaBufferMs=$lastMediaBufferMs")
        }
        onChanged()
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        sinkErrors++
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
        onChanged()
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
        private const val TAG = "AudioOutputDiagnostics"
        private const val LOG_INTERVAL_MS = 30_000L
    }
}
