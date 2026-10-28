package com.reproductor.audiolibros

/** Integer-only clock formatting, including durations beyond 24 hours or Int milliseconds. */
internal object DurationText {
    fun wholeSeconds(milliseconds: Long): Long = milliseconds.coerceAtLeast(0L) / 1_000L

    fun format(milliseconds: Long): String = formatSeconds(wholeSeconds(milliseconds))

    fun formatSeconds(seconds: Long): String {
        val hours = seconds / 3_600L
        val minutes = seconds / 60L % 60L
        val remainingSeconds = seconds % 60L
        return buildString(20) {
            if (hours > 0L) {
                append(hours).append(':')
                if (minutes < 10L) append('0')
            }
            append(minutes).append(':')
            if (remainingSeconds < 10L) append('0')
            append(remainingSeconds)
        }
    }
}

/** One entry per visible label; null means neither formatting nor setText is necessary. */
internal class DurationLabel {
    private var lastSeconds = Long.MIN_VALUE
    private var lastText: String? = null

    fun duration(milliseconds: Long): String? {
        val seconds = DurationText.wholeSeconds(milliseconds)
        if (seconds == lastSeconds) return null
        lastSeconds = seconds
        return changedText(DurationText.formatSeconds(seconds))
    }

    fun unknown(text: String): String? {
        lastSeconds = Long.MIN_VALUE
        return changedText(text)
    }

    private fun changedText(text: String): String? {
        if (text == lastText) return null
        lastText = text
        return text
    }
}
