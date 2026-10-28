package com.reproductor.audiolibros

import org.junit.Assert.*
import org.junit.Test

class SeekBarScaleTest {
    @Test fun ordinaryAudiobooksKeepMillisecondAccuracy() {
        val scale = SeekBarScale(24L * 60 * 60 * 1000)
        assertEquals(1L, scale.millisecondsPerStep)
        assertEquals(10_000, scale.keyboardIncrement)
        listOf(0L, 1L, 59_999L, 43_210_987L, scale.durationMs).forEach { position ->
            assertEquals(position, scale.positionForProgress(scale.progressForPosition(position)))
        }
    }

    @Test fun justOverIntLimitAndThirtyDayAudioReachTheirEntireDuration() {
        listOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L, 30L * 24 * 60 * 60 * 1000).forEach { duration ->
            val scale = SeekBarScale(duration)
            assertTrue(scale.maximum > 0)
            assertEquals(duration, scale.positionForProgress(scale.maximum))
            assertEquals(scale.maximum, scale.progressForPosition(duration))
            val position = duration - 10_003L
            val recovered = scale.positionForProgress(scale.progressForPosition(position))
            assertTrue(recovered <= position)
            assertTrue(position - recovered < scale.millisecondsPerStep)
        }
    }

    @Test fun extremeDurationsDoNotOverflowAndRemainMonotonic() {
        val scale = SeekBarScale(Long.MAX_VALUE)
        var previous = 0L
        listOf(0, 1, scale.maximum / 2, scale.maximum - 1, scale.maximum).forEach { progress ->
            val position = scale.positionForProgress(progress)
            assertTrue(position >= previous)
            assertTrue(position <= scale.durationMs)
            assertEquals(progress, scale.progressForPosition(position))
            previous = position
        }
        assertEquals(Long.MAX_VALUE, previous)
        assertTrue(scale.keyboardIncrement >= 1)
    }

    @Test fun unknownDurationAndOutOfBoundsEventsStaySafe() {
        val unknown = SeekBarScale(-1L)
        assertEquals(1, unknown.maximum)
        assertEquals(0L, unknown.positionForProgress(Int.MAX_VALUE))
        assertEquals(0, unknown.progressForPosition(Long.MAX_VALUE))
        val scale = SeekBarScale(100_000L)
        assertEquals(0L, scale.positionForProgress(-100))
        assertEquals(100_000L, scale.positionForProgress(Int.MAX_VALUE))
        assertEquals(0, scale.progressForPosition(Long.MIN_VALUE))
        assertEquals(scale.maximum, scale.progressForPosition(Long.MAX_VALUE))
    }
}
