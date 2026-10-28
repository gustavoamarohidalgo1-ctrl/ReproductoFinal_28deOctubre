package com.reproductor.audiolibros

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {

    @Test
    fun transientFailuresBackOffThreeTimesThenSkipTheTrack() {
        val policy = PlaybackRecoveryPolicy()

        for (delay in listOf(1_000L, 2_000L, 5_000L)) {
            assertEquals(
                PlaybackRecoveryPolicy.Plan(1, delay, false, false),
                policy.onFailure(index = 1, itemCount = 3, permanent = false)
            )
        }
        assertEquals(
            PlaybackRecoveryPolicy.Plan(2, 250L, true, false),
            policy.onFailure(index = 1, itemCount = 3, permanent = false)
        )
    }

    @Test
    fun permanentFailuresSkipImmediatelyAndWrapTheQueue() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 250L, true, false),
            policy.onFailure(index = 2, itemCount = 3, permanent = true)
        )
    }

    @Test
    fun previouslyFailedTracksAreSkippedDuringTheSameRound() {
        val policy = PlaybackRecoveryPolicy()
        policy.onFailure(index = 2, itemCount = 4, permanent = true)
        policy.onFailure(index = 0, itemCount = 4, permanent = true)

        assertEquals(
            PlaybackRecoveryPolicy.Plan(3, 250L, true, false),
            policy.onFailure(index = 1, itemCount = 4, permanent = true)
        )
    }

    @Test
    fun failingEveryFileWaitsAndStartsAFreshRecoveryRound() {
        val policy = PlaybackRecoveryPolicy()
        policy.onFailure(index = 0, itemCount = 2, permanent = true)

        assertEquals(
            PlaybackRecoveryPolicy.Plan(1, 30_000L, false, true),
            policy.onFailure(index = 1, itemCount = 2, permanent = true)
        )
        assertEquals(
            PlaybackRecoveryPolicy.Plan(1, 1_000L, false, false),
            policy.onFailure(index = 1, itemCount = 2, permanent = false)
        )
        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 250L, true, false),
            policy.onFailure(index = 1, itemCount = 2, permanent = true)
        )
    }

    @Test
    fun resetAfterStablePlaybackRestoresRetriesAndPreviouslyFailedTracks() {
        val policy = PlaybackRecoveryPolicy()
        policy.onFailure(index = 0, itemCount = 2, permanent = true)
        policy.onFailure(index = 1, itemCount = 2, permanent = false)
        policy.onFailure(index = 1, itemCount = 2, permanent = false)

        policy.reset()

        assertEquals(
            PlaybackRecoveryPolicy.Plan(1, 1_000L, false, false),
            policy.onFailure(index = 1, itemCount = 2, permanent = false)
        )
        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 250L, true, false),
            policy.onFailure(index = 1, itemCount = 2, permanent = true)
        )
    }

    @Test
    fun aSingleTrackWaitsAfterExhaustingRetriesThenCanRetryAgain() {
        val policy = PlaybackRecoveryPolicy()
        repeat(3) { policy.onFailure(index = 0, itemCount = 1, permanent = false) }

        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 30_000L, false, true),
            policy.onFailure(index = 0, itemCount = 1, permanent = false)
        )
        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 1_000L, false, false),
            policy.onFailure(index = 0, itemCount = 1, permanent = false)
        )
    }

    @Test
    fun indicesOutsideTheQueueAreClamped() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 1_000L, false, false),
            policy.onFailure(index = -1, itemCount = 3, permanent = false)
        )
        assertEquals(
            PlaybackRecoveryPolicy.Plan(2, 1_000L, false, false),
            policy.onFailure(index = Int.MAX_VALUE, itemCount = 3, permanent = false)
        )
    }

    @Test
    fun permanentFailureOfTheOnlyTrackWaitsImmediately() {
        assertEquals(
            PlaybackRecoveryPolicy.Plan(0, 30_000L, false, true),
            PlaybackRecoveryPolicy().onFailure(index = 10, itemCount = 1, permanent = true)
        )
    }

    @Test
    fun shrinkingThenGrowingTheQueueDiscardsOnlyRemovedTracksFailureHistory() {
        val policy = PlaybackRecoveryPolicy()
        policy.onFailure(index = 0, itemCount = 5, permanent = true)
        policy.onFailure(index = 4, itemCount = 5, permanent = true)
        repeat(3) { policy.onFailure(index = 3, itemCount = 5, permanent = false) }

        // Removing the tail must discard both permanent and transient failures there.
        // Index 0 still exists, so its failure must survive the size change.
        policy.onFailure(index = 1, itemCount = 3, permanent = false)
        assertEquals(
            PlaybackRecoveryPolicy.Plan(1, 250L, true, false),
            policy.onFailure(index = 2, itemCount = 3, permanent = true)
        )

        assertEquals(
            PlaybackRecoveryPolicy.Plan(3, 1_000L, false, false),
            policy.onFailure(index = 3, itemCount = 5, permanent = false)
        )
        assertEquals(
            PlaybackRecoveryPolicy.Plan(4, 250L, true, false),
            policy.onFailure(index = 3, itemCount = 5, permanent = true)
        )
    }

    @Test
    fun aLargeUnavailableLibraryPreservesOrderAndRetriesEveryTrackOnTheNextRound() {
        val policy = PlaybackRecoveryPolicy()
        val itemCount = 20_000

        // A detached storage volume can make the entire large library unavailable.
        // Every item must still be visited, and the wait must reset the next round.
        var index = 0
        repeat(2) {
            repeat(itemCount - 1) {
                val nextIndex = (index + 1) % itemCount
                assertEquals(
                    PlaybackRecoveryPolicy.Plan(nextIndex, 250L, true, false),
                    policy.onFailure(index, itemCount, permanent = true)
                )
                index = nextIndex
            }
            assertEquals(
                PlaybackRecoveryPolicy.Plan(index, 30_000L, false, true),
                policy.onFailure(index, itemCount, permanent = true)
            )
        }
        assertEquals(
            PlaybackRecoveryPolicy.Plan(index, 1_000L, false, false),
            policy.onFailure(index, itemCount, permanent = false)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyQueueCannotProduceARecoveryPlan() {
        PlaybackRecoveryPolicy().onFailure(index = 0, itemCount = 0, permanent = false)
    }
}
