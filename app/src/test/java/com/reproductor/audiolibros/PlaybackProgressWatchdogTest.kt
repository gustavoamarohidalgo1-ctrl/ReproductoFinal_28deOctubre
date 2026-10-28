package com.reproductor.audiolibros

import com.reproductor.audiolibros.PlaybackProgressWatchdog.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressWatchdogTest {
    @Test
    fun bufferingAndReadyOscillationCannotHideAStall() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 1_000L)

        for (now in 2_000L..18_000L step 2_000L) {
            assertEquals(Decision.NONE, watchdog.observe(now, 0, 1_000L, now % 4_000L == 0L, true))
        }
        assertEquals(Decision.STALLED, watchdog.observe(20_000L, 0, 1_000L, true, true))
    }

    @Test
    fun jitterAndBackwardMovementDoNotCountAsProgress() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 1_000L)

        for (now in 2_000L..18_000L step 2_000L) {
            val position = if (now % 4_000L == 0L) 1_099L else 950L
            assertEquals(Decision.NONE, watchdog.observe(now, 0, position, true, true))
        }
        assertEquals(Decision.STALLED, watchdog.observe(20_000L, 0, 1_099L, true, true))
    }

    @Test
    fun repeatedBackwardBouncesDoNotRenewTheProgressDeadline() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 1_000L)
        assertEquals(Decision.NONE, watchdog.observe(2_000L, 0, 1_100L, true, true))

        for (now in 4_000L..20_000L step 2_000L) {
            val position = if (now % 4_000L == 0L) 900L else 1_100L
            assertEquals(Decision.NONE, watchdog.observe(now, 0, position, true, true))
        }
        assertEquals(Decision.STALLED, watchdog.observe(22_000L, 0, 1_100L, true, true))
    }

    @Test
    fun smallForwardStepsAccumulateAgainstTheConfirmedPosition() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)
        watchdog.observe(5_000L, 0, 40L, true, true)
        watchdog.observe(10_000L, 0, 80L, true, true)
        watchdog.observe(15_000L, 0, 100L, true, true)

        assertEquals(Decision.NONE, watchdog.observe(20_000L, 0, 100L, true, true))
        assertEquals(Decision.STALLED, watchdog.observe(35_000L, 0, 100L, true, true))
    }

    @Test
    fun voluntaryPauseSuspendsTheDeadlineUntilResume() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 1_000L)
        assertEquals(Decision.NONE, watchdog.observe(60_000L, 0, 1_000L, false, false))
        assertEquals(Decision.NONE, watchdog.observe(600_000L, 0, 1_000L, false, true))
        assertEquals(Decision.NONE, watchdog.observe(619_999L, 0, 1_000L, false, true))
        assertEquals(Decision.STALLED, watchdog.observe(620_000L, 0, 1_000L, false, true))
    }

    @Test
    fun aPendingRecoveryGetsItsOwnFreshDeadlineAfterWaiting() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 1_000L)
        assertEquals(Decision.STALLED, watchdog.observe(20_000L, 0, 1_000L, false, true))
        assertEquals(Decision.NONE, watchdog.observe(50_000L, 0, 1_000L, false, true, true))
        assertEquals(Decision.NONE, watchdog.observe(52_000L, 0, 1_000L, false, true))
        assertEquals(Decision.STALLED, watchdog.observe(72_000L, 0, 1_000L, false, true))
    }

    @Test
    fun stablePlaybackRequiresSustainedObservedProgress() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)

        for (now in 2_000L..20_000L step 2_000L) {
            assertEquals(Decision.NONE, watchdog.observe(now, 0, now, true, true))
        }
        assertEquals(Decision.STABLE, watchdog.observe(22_000L, 0, 22_000L, true, true))
        assertEquals(Decision.NONE, watchdog.observe(24_000L, 0, 24_000L, true, true))
    }

    @Test
    fun stalledPositionCannotBeReportedStableDespiteIsPlaying() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)
        watchdog.observe(2_000L, 0, 2_000L, true, true)

        for (now in 4_000L..20_000L step 2_000L) {
            assertEquals(Decision.NONE, watchdog.observe(now, 0, 2_000L, true, true))
        }
        assertEquals(Decision.STALLED, watchdog.observe(22_000L, 0, 2_000L, true, true))
    }

    @Test
    fun bufferingInterruptsEvidenceOfStablePlayback() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)
        for (now in 2_000L..18_000L step 2_000L) watchdog.observe(now, 0, now, true, true)
        watchdog.observe(20_000L, 0, 20_000L, false, true)

        for (now in 22_000L..40_000L step 2_000L) {
            assertEquals(Decision.NONE, watchdog.observe(now, 0, now, true, true))
        }
        assertEquals(Decision.STABLE, watchdog.observe(42_000L, 0, 42_000L, true, true))
    }

    @Test
    fun resetAfterBackwardSeekOrManualRestartStartsAFreshObservationWindow() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 120_000L)
        watchdog.reset(now = 19_000L, index = 0, position = 0L)

        assertEquals(Decision.NONE, watchdog.observe(20_000L, 0, 1_000L, true, true))
        assertEquals(Decision.NONE, watchdog.observe(39_999L, 0, 1_000L, true, true))
        assertEquals(Decision.STALLED, watchdog.observe(40_000L, 0, 1_000L, true, true))
    }

    @Test
    fun changingTrackIndexResetsTheDeadlineAndStableEvidence() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)
        for (now in 2_000L..18_000L step 2_000L) watchdog.observe(now, 0, now, true, true)

        assertEquals(Decision.NONE, watchdog.observe(20_000L, 1, 0L, false, true))
        assertEquals(Decision.NONE, watchdog.observe(39_999L, 1, 0L, true, true))
        assertEquals(Decision.STALLED, watchdog.observe(40_000L, 1, 0L, true, true))
    }

    @Test
    fun staleClockReadingsCannotRenewTheDeadlineOrCreateProgress() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 100_000L, index = 0, position = 1_000L)
        assertEquals(Decision.NONE, watchdog.observe(110_000L, 0, 1_000L, true, true))
        assertEquals(Decision.NONE, watchdog.observe(105_000L, 0, 60_000L, true, true))
        assertEquals(Decision.STALLED, watchdog.observe(120_000L, 0, 1_000L, true, true))
    }

    @Test
    fun anUninitializedWatchdogStartsAtTheFirstObservation() {
        val watchdog = PlaybackProgressWatchdog()
        assertEquals(Decision.NONE, watchdog.observe(1_000_000L, 0, 5_000L, true, true))
        assertEquals(Decision.STALLED, watchdog.observe(1_020_000L, 0, 5_000L, true, true))
    }

    @Test
    fun aFullDayOfProgressWithTrackLoopsHasNoFalseStalls() {
        val watchdog = PlaybackProgressWatchdog()
        val trackDuration = 600_000L
        var stableWindows = 0
        watchdog.reset(now = 0L, index = 0, position = 0L)

        for (now in 2_000L..86_400_000L step 2_000L) {
            val position = now % trackDuration
            if (position == 0L) watchdog.onAutoTransition(now, 0, 0L)
            when (val decision = watchdog.observe(now, 0, position, true, true)) {
                Decision.STABLE -> stableWindows++
                else -> assertEquals(Decision.NONE, decision)
            }
        }
        org.junit.Assert.assertTrue(stableWindows > 4_000)
    }

    @Test
    fun shortAutomaticLoopsAccumulateStablePlaybackAcrossTransitions() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)

        for (now in 1_000L..22_000L step 1_000L) {
            if (now % 3_000L == 0L) watchdog.onAutoTransition(now, 0, 0L)
            if (now % 2_000L == 0L) {
                val expected = if (now == 22_000L) Decision.STABLE else Decision.NONE
                assertEquals(expected, watchdog.observe(now, 0, now % 3_000L, true, true))
            }
        }
    }

    @Test
    fun loopsShorterThanTheHeartbeatStillProvideProgressEvidence() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)

        for (now in 1_000L..22_000L step 1_000L) {
            watchdog.onAutoTransition(now, 0, 0L)
            if (now % 2_000L == 0L) {
                val expected = if (now == 22_000L) Decision.STABLE else Decision.NONE
                assertEquals(expected, watchdog.observe(now, 0, 0L, true, true))
            }
        }
    }

    @Test
    fun pauseAndRecoveryDiscardEvidenceFromAnAutomaticLoop() {
        for (recovering in listOf(false, true)) {
            val watchdog = PlaybackProgressWatchdog()
            watchdog.reset(now = 0L, index = 0, position = 0L)
            for (now in 2_000L..18_000L step 2_000L) watchdog.observe(now, 0, now, true, true)
            watchdog.onAutoTransition(19_000L, 0, 0L)
            assertEquals(Decision.NONE, watchdog.observe(20_000L, 0, 0L, false, recovering, recovering))
            assertEquals(Decision.NONE, watchdog.observe(100_000L, 0, 0L, false, true))

            for (now in 102_000L..120_000L step 2_000L) {
                assertEquals(Decision.NONE, watchdog.observe(now, 0, now - 100_000L, true, true))
            }
            assertEquals(Decision.STABLE, watchdog.observe(122_000L, 0, 22_000L, true, true))
        }
    }

    @Test
    fun automaticTransitionToADifferentIndexStartsANewStableWindow() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 0L, index = 0, position = 0L)
        for (now in 2_000L..18_000L step 2_000L) watchdog.observe(now, 0, now, true, true)
        watchdog.onAutoTransition(20_000L, 1, 0L)

        for (now in 22_000L..40_000L step 2_000L) {
            assertEquals(Decision.NONE, watchdog.observe(now, 1, now - 20_000L, true, true))
        }
        assertEquals(Decision.STABLE, watchdog.observe(42_000L, 1, 22_000L, true, true))
    }

    @Test
    fun aStaleAutomaticTransitionCannotRenewTheDeadline() {
        val watchdog = PlaybackProgressWatchdog()
        watchdog.reset(now = 100_000L, index = 0, position = 1_000L)
        watchdog.observe(110_000L, 0, 1_000L, true, true)
        watchdog.onAutoTransition(105_000L, 0, 0L)

        assertEquals(Decision.STALLED, watchdog.observe(120_000L, 0, 1_000L, true, true))
    }
}
