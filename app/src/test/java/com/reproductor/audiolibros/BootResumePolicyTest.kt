package com.reproductor.audiolibros

import org.junit.Assert.*
import org.junit.Test

class BootResumePolicyTest {
    private fun allowed(enabled: Boolean = true, requested: Boolean = true,
        interacted: Boolean = false, playing: Boolean = true, saved: Int = 5,
        current: Int = 6, media: Boolean = true) = BootResumePolicy.canResume(
        enabled, requested, interacted, playing, saved, current, media)

    @Test fun requiresPriorPlayingStateAndActualNewBoot() {
        assertTrue(allowed())
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(requested = false))
        assertFalse(allowed(interacted = true))
        assertFalse(allowed(playing = false))
        assertFalse(allowed(saved = 5, current = 5))
        assertFalse(allowed(saved = -1))
        assertFalse(allowed(current = -1))
        assertFalse(allowed(saved = 6, current = 5))
        assertFalse(allowed(media = false))
    }
}
