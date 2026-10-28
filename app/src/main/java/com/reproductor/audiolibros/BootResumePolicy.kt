package com.reproductor.audiolibros

internal object BootResumePolicy {
    fun canResume(
        enabled: Boolean,
        requestedByBoot: Boolean,
        userInteracted: Boolean,
        savedPlaying: Boolean,
        savedBoot: Int,
        currentBoot: Int,
        hasMedia: Boolean
    ): Boolean = enabled && requestedByBoot && !userInteracted && savedPlaying &&
        savedBoot >= 0 && currentBoot > savedBoot && hasMedia
}
