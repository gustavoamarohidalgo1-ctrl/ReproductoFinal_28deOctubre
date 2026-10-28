package com.reproductor.audiolibros

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/** Gives decoded PCM more room to tolerate brief scheduling delays. */
@androidx.annotation.OptIn(UnstableApi::class)
internal open class ContinuousAudioRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioTrackBufferSizeProvider(continuousPcmBufferSizeProvider())
        .build()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun continuousPcmBufferSizeProvider(): DefaultAudioTrackBufferSizeProvider =
    DefaultAudioTrackBufferSizeProvider.Builder()
        .setMinPcmBufferDurationUs(2_000_000)
        .setMaxPcmBufferDurationUs(3_000_000)
        .build()
