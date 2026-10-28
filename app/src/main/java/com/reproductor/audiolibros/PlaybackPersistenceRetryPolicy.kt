package com.reproductor.audiolibros

/**
 * Limits repeated disk work after a failed commit, without delaying healthy checkpoints.
 * Owned by the serial persistence worker. Times use the same monotonic clock.
 */
internal class PlaybackPersistenceRetryPolicy {
    data class State(val generation: Long, val index: Int, val desiredPlaying: Boolean)

    private var failedState: State? = null
    private var lastFailureAtMs = 0L
    private var failures = 0
    private var attemptedUrgentRequest = 0L

    fun shouldAttempt(state: State, nowMs: Long, urgentRequest: Long = 0L): Boolean =
        urgentRequest != attemptedUrgentRequest || failedState != state || nowMs < lastFailureAtMs ||
            nowMs - lastFailureAtMs >= RETRY_DELAYS_MS[failures - 1]

    /** Consume priority once, even if disk I/O fails. A newer snapshot carries this same ID. */
    fun attempted(urgentRequest: Long) { attemptedUrgentRequest = urgentRequest }

    fun failed(state: State, nowMs: Long) {
        failures = if (failedState == state) (failures + 1).coerceAtMost(RETRY_DELAYS_MS.size) else 1
        failedState = state
        lastFailureAtMs = nowMs
    }

    fun succeeded() {
        failedState = null
        failures = 0
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
