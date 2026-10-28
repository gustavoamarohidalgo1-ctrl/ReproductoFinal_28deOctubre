package com.reproductor.audiolibros

/** Maps arbitrarily long audio to SeekBar's Int range without truncating the playable tail. */
internal class SeekBarScale(durationMs: Long) {
    val durationMs = durationMs.coerceAtLeast(0L)
    // Keep exact milliseconds for ordinary files; increase the unit only when required.
    val millisecondsPerStep: Long = if (this.durationMs <= Int.MAX_VALUE) 1L else
        this.durationMs / Int.MAX_VALUE + if (this.durationMs % Int.MAX_VALUE == 0L) 0L else 1L
    val maximum: Int = if (this.durationMs == 0L) 1 else
        (this.durationMs / millisecondsPerStep +
            if (this.durationMs % millisecondsPerStep == 0L) 0L else 1L).toInt()
    val keyboardIncrement: Int = (10_000L / millisecondsPerStep).coerceAtLeast(1L).toInt()

    fun positionForProgress(progress: Int): Long {
        val bounded = progress.coerceIn(0, maximum)
        // The rounded last step may overflow Long when multiplied, so handle it directly.
        return if (bounded == maximum) durationMs else bounded.toLong() * millisecondsPerStep
    }

    fun progressForPosition(positionMs: Long): Int = when {
        durationMs == 0L -> 0
        positionMs >= durationMs -> maximum
        else -> (positionMs.coerceAtLeast(0L) / millisecondsPerStep).toInt()
    }
}
