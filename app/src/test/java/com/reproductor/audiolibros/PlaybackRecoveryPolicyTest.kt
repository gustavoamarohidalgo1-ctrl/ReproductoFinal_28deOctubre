package com.reproductor.audiolibros

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun transientFailuresRetryOnlySelectedItemAndRepeatAfterLongWait() {
        val policy = PlaybackRecoveryPolicy()
        repeat(2) {
            for (delay in listOf(1_000L, 2_000L, 5_000L)) {
                assertEquals(PlaybackRecoveryPolicy.Plan(1, delay, false, false), policy.onFailure(1, 3, false))
            }
            assertEquals(PlaybackRecoveryPolicy.Plan(1, 30_000L, false, true), policy.onFailure(1, 3, false))
        }
    }

    @Test
    fun permanentFailureWaitsOnSameItemEvenWithOtherPlayableItems() {
        val policy = PlaybackRecoveryPolicy()
        repeat(3) {
            assertEquals(PlaybackRecoveryPolicy.Plan(2, 30_000L, false, true), policy.onFailure(2, 20_000, true))
        }
    }

    @Test
    fun changingIndexOrResetRestartsTransientBackoff() {
        val policy = PlaybackRecoveryPolicy()
        policy.onFailure(1, 3, false)
        assertEquals(PlaybackRecoveryPolicy.Plan(2, 1_000L, false, false), policy.onFailure(2, 3, false))
        policy.reset()
        assertEquals(PlaybackRecoveryPolicy.Plan(2, 1_000L, false, false), policy.onFailure(2, 3, false))
    }

    @Test
    fun outOfRangeIndicesAreClampedWithoutSkipping() {
        val policy = PlaybackRecoveryPolicy()
        assertEquals(PlaybackRecoveryPolicy.Plan(0, 1_000L, false, false), policy.onFailure(-1, 3, false))
        assertEquals(PlaybackRecoveryPolicy.Plan(2, 30_000L, false, true), policy.onFailure(Int.MAX_VALUE, 3, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyQueueCannotProduceARecoveryPlan() {
        PlaybackRecoveryPolicy().onFailure(0, 0, false)
    }
}
