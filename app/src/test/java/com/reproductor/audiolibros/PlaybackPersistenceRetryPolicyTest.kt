package com.reproductor.audiolibros

import org.junit.Assert.*
import org.junit.Test

class PlaybackPersistenceRetryPolicyTest {
    private val playing = PlaybackPersistenceRetryPolicy.State(42, 3, true)

    @Test fun healthyWritesAreNeverThrottled() {
        val policy = PlaybackPersistenceRetryPolicy()
        repeat(100) {
            assertTrue(policy.shouldAttempt(playing, it.toLong()))
            policy.succeeded()
        }
    }

    @Test fun failedCommitsBackOffButSkippedHeartbeatsDoNotPostponeRecovery() {
        val policy = PlaybackPersistenceRetryPolicy()
        policy.failed(playing, 1_000)
        assertFalse(policy.shouldAttempt(playing, 2_999))
        assertTrue(policy.shouldAttempt(playing, 3_000))
        policy.failed(playing, 3_000)
        for (time in listOf(4_000L, 5_000L, 6_999L)) assertFalse(policy.shouldAttempt(playing, time))
        assertTrue(policy.shouldAttempt(playing, 7_000))
        policy.succeeded()
        assertTrue(policy.shouldAttempt(playing, 7_001))
        policy.failed(playing, 7_001)
        assertTrue(policy.shouldAttempt(playing, 9_001)) // Success resets the failure count.
    }

    @Test fun userSeekAndNewPlaybackIntentBypassTheCooldown() {
        val policy = PlaybackPersistenceRetryPolicy()
        policy.failed(playing, 10_000)
        assertFalse(policy.shouldAttempt(playing, 10_001))
        assertTrue(policy.shouldAttempt(playing, 10_001, urgentRequest = 1))
        assertTrue(policy.shouldAttempt(playing.copy(desiredPlaying = false), 10_001))
        assertTrue(policy.shouldAttempt(playing.copy(index = 4), 10_001))
        assertTrue(policy.shouldAttempt(playing.copy(generation = 43), 10_001))
        assertTrue(policy.shouldAttempt(PlaybackPersistenceRetryPolicy.State(43, -1, false), 10_001))
    }

    @Test fun urgentRequestSurvivesCoalescingButIsConsumedOnceOnFailure() {
        val policy = PlaybackPersistenceRetryPolicy()
        policy.failed(playing, 10_000)
        // Both the manual seek and its newer periodic snapshot carry request 1.
        assertTrue(policy.shouldAttempt(playing, 10_001, urgentRequest = 1))
        assertTrue(policy.shouldAttempt(playing, 10_002, urgentRequest = 1))
        policy.attempted(1)
        policy.failed(playing, 10_002)
        assertFalse(policy.shouldAttempt(playing, 10_003, urgentRequest = 1))
        assertTrue(policy.shouldAttempt(playing, 10_004, urgentRequest = 2))
    }

    @Test fun newStateStartsItsOwnRetryWindowEvenAfterManyFailures() {
        val policy = PlaybackPersistenceRetryPolicy()
        repeat(20) { policy.failed(playing, 0) }
        val paused = playing.copy(desiredPlaying = false)
        policy.failed(paused, 10_000)
        assertFalse(policy.shouldAttempt(paused, 11_999))
        assertTrue(policy.shouldAttempt(paused, 12_000))
    }

    @Test fun dayOfDiskFailureHasBoundedAttemptsAndStillRetriesUntilItRecovers() {
        val policy = PlaybackPersistenceRetryPolicy()
        var attempts = 0
        var previousAttempt = 0L
        var largestInterval = 0L
        for (time in 0L..86_400_000L step 2_000) {
            if (policy.shouldAttempt(playing, time)) {
                largestInterval = maxOf(largestInterval, time - previousAttempt)
                previousAttempt = time
                attempts++
                policy.failed(playing, time)
            }
        }
        assertTrue(attempts in 2_800..2_900) // Approximately one retry per 30 s after ramp-up.
        assertEquals(30_000L, largestInterval)
        assertTrue(policy.shouldAttempt(playing, 86_400_001L, urgentRequest = 1))
        policy.succeeded()
        assertTrue(policy.shouldAttempt(playing, 86_400_002L))
    }

    @Test fun monotonicClockResetCannotLeavePersistenceBlocked() {
        val policy = PlaybackPersistenceRetryPolicy()
        policy.failed(playing, Long.MAX_VALUE - 1_000)
        assertFalse(policy.shouldAttempt(playing, Long.MAX_VALUE))
        assertTrue(policy.shouldAttempt(playing, 10))
    }
}
