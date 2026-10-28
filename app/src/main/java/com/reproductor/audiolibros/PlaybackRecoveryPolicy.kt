package com.reproductor.audiolibros

/** Decide cuándo reintentar y qué pista probar sin depender del reproductor ni de Android. */
class PlaybackRecoveryPolicy {

    data class Plan(
        val index: Int,
        val delayMs: Long,
        val restartFromBeginning: Boolean,
        val waitingForFiles: Boolean
    )

    private val failedIndexes = mutableSetOf<Int>()
    private val transientAttempts = mutableMapOf<Int, Int>()
    private var lastItemCount = 0

    /**
     * Cada pista dispone de tres reintentos transitorios antes de probar otra.
     * Un error permanente la descarta inmediatamente durante esta ronda.
     * El servicio debe llamar a [reset] al cambiar la selección o confirmar reproducción estable.
     */
    fun onFailure(index: Int, itemCount: Int, permanent: Boolean): Plan {
        require(itemCount > 0) { "La recuperación requiere al menos una pista" }
        val currentIndex = index.coerceIn(0, itemCount - 1)

        // Sólo una reducción puede invalidar índices. Repetir la poda en cada fallo
        // convierte una ronda de archivos inaccesibles en trabajo cuadrático.
        if (itemCount < lastItemCount) {
            failedIndexes.removeAll { it >= itemCount }
            transientAttempts.keys.removeAll { it >= itemCount }
        }
        lastItemCount = itemCount

        if (!permanent && currentIndex !in failedIndexes) {
            val attempts = transientAttempts[currentIndex] ?: 0
            if (attempts < RETRY_DELAYS_MS.size) {
                transientAttempts[currentIndex] = attempts + 1
                return Plan(currentIndex, RETRY_DELAYS_MS[attempts], false, false)
            }
        }

        failedIndexes.add(currentIndex)
        transientAttempts.remove(currentIndex)

        var nextIndex = currentIndex
        repeat(itemCount - 1) {
            nextIndex = if (nextIndex == itemCount - 1) 0 else nextIndex + 1
            if (nextIndex !in failedIndexes) {
                return Plan(nextIndex, NEXT_TRACK_DELAY_MS, true, false)
            }
        }

        // Una nueva ronda tras la espera vuelve a comprobar archivos que pudieron recuperarse.
        reset()
        return Plan(currentIndex, FILES_RECHECK_DELAY_MS, false, true)
    }

    fun reset() {
        failedIndexes.clear()
        transientAttempts.clear()
        lastItemCount = 0
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L)
        const val NEXT_TRACK_DELAY_MS = 250L
        const val FILES_RECHECK_DELAY_MS = 30_000L
    }
}
