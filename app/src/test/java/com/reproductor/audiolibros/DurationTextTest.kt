package com.reproductor.audiolibros

import java.util.Locale
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class DurationTextTest {
    @Test fun integerFormattingMatchesThePreviousClockFormatAcrossBoundariesAndLongValues() {
        val boundaries = listOf(Long.MIN_VALUE, -1L, 0L, 999L, 1_000L, 59_999L, 60_000L,
            3_599_999L, 3_600_000L, 24L * 60 * 60 * 1_000, Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE + 1L, Long.MAX_VALUE - 999L, Long.MAX_VALUE)
        val random = Random(20)
        val values = boundaries + List(1_000) { random.nextLong() }
        for (milliseconds in values) {
            val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
            val expected = if (seconds >= 3_600L) {
                String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600L,
                    seconds / 60L % 60L, seconds % 60L)
            } else String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
            assertEquals("milliseconds=$milliseconds", expected, DurationText.format(milliseconds))
        }
    }

    @Test fun pausedLabelNeedsOnlyOneTextWriteAcrossTwentyFourHoursOfTicks() {
        val label = DurationLabel()
        var writes = 0
        repeat(24 * 60 * 60) {
            if (label.duration(25L * 60 * 60 * 1_000 + 12_345) != null) writes++
        }
        assertEquals(1, writes)
    }

    @Test fun seekingAndReturningToPlaybackUseTheSameLastRenderedSecond() {
        val label = DurationLabel()
        assertEquals("1:00", label.duration(60_000))
        assertNull(label.duration(60_999))
        assertEquals("10:00", label.duration(600_000)) // Preview while dragging.
        assertNull(label.duration(600_300))
        assertEquals("1:00", label.duration(60_500)) // Cancelled gesture returns to the player.
        assertEquals("0:00", label.duration(0)) // A new track or repeat can move backwards.
        assertNull(label.duration(-1))
    }

    @Test fun knownUnknownAndEmptyTracksNeverReuseAStaleDurationLabel() {
        val label = DurationLabel()
        assertEquals("1:00:00", label.duration(3_600_000))
        assertEquals("--:--", label.unknown("--:--"))
        assertNull(label.unknown("--:--"))
        assertEquals("1:00:00", label.duration(3_600_000))
        assertEquals("--:--", label.unknown("--:--"))
        assertEquals("0:00", label.duration(0))
        assertNull(label.duration(999))
    }

    @Test fun labelsAndRecreatedScreensKeepIndependentBoundedState() {
        val current = DurationLabel()
        val total = DurationLabel()
        assertEquals("0:05", current.duration(5_000))
        assertEquals("0:05", total.duration(5_000))
        assertNull(current.duration(5_999))
        assertNull(total.duration(5_001))
        assertEquals("0:05", DurationLabel().duration(5_000))
    }
}
