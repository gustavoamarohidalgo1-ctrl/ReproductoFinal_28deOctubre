package com.reproductor.audiolibros

/**
 * Vigila avance real, independientemente de los cambios BUFFERING/READY del reproductor.
 * Todos los tiempos deben provenir del mismo reloj monotónico (elapsedRealtime en Android).
 */
class PlaybackProgressWatchdog(
    private val stallTimeoutMs: Long = 20_000L,
    private val stablePlaybackMs: Long = 20_000L,
    private val minProgressMs: Long = 100L
) {
    enum class Decision { NONE, STALLED, STABLE }

    init {
        require(stallTimeoutMs > 0)
        require(stablePlaybackMs > 0)
        require(minProgressMs > 0)
    }

    private var initialized = false
    private var monitoring = false
    private var observedIndex = -1
    private var lastObservationAt = 0L
    private var lastProgressAt = 0L
    private var progressPosition = 0L
    private var stableSince: Long? = null
    private var progressedBetweenObservations = false

    /** Llamar al buscar una posición, reiniciar manualmente o iniciar un nuevo intento. */
    fun reset(now: Long, index: Int, position: Long) {
        initialized = true
        monitoring = true
        observedIndex = index
        lastObservationAt = now
        lastProgressAt = now
        progressPosition = position.coerceAtLeast(0L)
        stableSince = null
        progressedBetweenObservations = false
    }

    /**
     * Una repetición automática real demuestra avance incluso en pistas más cortas que el
     * intervalo de observación. El llamador debe comprobar AUTO_TRANSITION e isPlaying.
     * Conserva estabilidad al repetir la misma pista; una selección distinta inicia otra ventana.
     */
    fun onAutoTransition(now: Long, index: Int, position: Long) {
        if (initialized && now < lastObservationAt) return
        if (!initialized || !monitoring || index != observedIndex) {
            reset(now, index, position)
            return
        }
        if (now - lastProgressAt >= stallTimeoutMs) stableSince = null
        lastObservationAt = now
        lastProgressAt = now
        progressPosition = position.coerceAtLeast(0L)
        progressedBetweenObservations = true
        if (stableSince == null) stableSince = now
    }

    /**
     * La pausa voluntaria y la espera de un reintento suspenden la vigilancia. Un cambio de
     * estado sin avance no renueva el plazo: incluso isPlaying=true puede ocultar un atasco.
     * STABLE exige observaciones sucesivas con avance y reproducción durante todo el intervalo.
     * STALLED se puede repetir; el llamador debe indicar recoveryPending al programar un intento.
     */
    fun observe(
        now: Long,
        index: Int,
        position: Long,
        isPlaying: Boolean,
        desiredPlaying: Boolean,
        recoveryPending: Boolean = false
    ): Decision {
        // Una lectura atrasada no debe renovar el plazo ni alterar el máximo de posición.
        if (initialized && now < lastObservationAt) return Decision.NONE

        if (!initialized || index != observedIndex) reset(now, index, position)

        if (!desiredPlaying || recoveryPending) {
            reset(now, index, position)
            monitoring = false
            return Decision.NONE
        }
        if (!monitoring) {
            reset(now, index, position)
            return Decision.NONE
        }

        lastObservationAt = now
        val currentPosition = position.coerceAtLeast(0L)
        val progressed = currentPosition >= progressPosition &&
            currentPosition - progressPosition >= minProgressMs
        if (progressed) {
            progressPosition = currentPosition
            lastProgressAt = now
        }
        val progressObserved = progressed || progressedBetweenObservations
        progressedBetweenObservations = false

        if (now - lastProgressAt >= stallTimeoutMs) {
            stableSince = null
            return Decision.STALLED
        }

        if (!isPlaying || !progressObserved) {
            stableSince = null
        } else {
            val since = stableSince
            if (since == null) {
                stableSince = now
            } else if (now - since >= stablePlaybackMs) {
                stableSince = now
                return Decision.STABLE
            }
        }
        return Decision.NONE
    }
}
