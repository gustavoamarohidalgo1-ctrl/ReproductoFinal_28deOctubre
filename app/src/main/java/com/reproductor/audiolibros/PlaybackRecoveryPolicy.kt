package com.reproductor.audiolibros

/** Retries the selected item without automatically changing the user's selection. */
class PlaybackRecoveryPolicy {
    data class Plan(val index: Int, val delayMs: Long, val restartFromBeginning: Boolean, val waitingForFiles: Boolean)

    private var selectedIndex = -1
    private var attempts = 0

    fun onFailure(index: Int, itemCount: Int, permanent: Boolean): Plan {
        require(itemCount > 0) { "La recuperación requiere al menos una pista" }
        val currentIndex = index.coerceIn(0, itemCount - 1)
        if (currentIndex != selectedIndex) {
            selectedIndex = currentIndex
            attempts = 0
        }
        if (!permanent && attempts < RETRY_DELAYS_MS.size) {
            return Plan(currentIndex, RETRY_DELAYS_MS[attempts++], false, false)
        }
        attempts = 0
        return Plan(currentIndex, FILES_RECHECK_DELAY_MS, false, true)
    }

    fun reset() {
        selectedIndex = -1
        attempts = 0
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L)
        const val FILES_RECHECK_DELAY_MS = 30_000L
    }
}
