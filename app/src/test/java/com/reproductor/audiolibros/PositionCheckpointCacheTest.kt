package com.reproductor.audiolibros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PositionCheckpointCacheTest {
    private var now = 0L
    private val disk = mutableMapOf<String, Long>()
    private val writes = mutableListOf<Pair<String, Long>>()
    private val cache = PositionCheckpointCache(
        readPosition = { disk[it] ?: 0L },
        writePosition = { id, position -> disk[id] = position; writes.add(id to position) },
        removePosition = { disk.remove(it) },
        nowMs = { now }
    )

    @Test
    fun frequentPlaybackProgressStaysCurrentInMemoryAndLimitsBookmarkWrites() {
        // A full hour with a two-second session checkpoint needs only 120 bookmark writes.
        for (tick in 1..1_800) {
            now = tick * 2_000L
            cache.save("book", now)
            assertEquals(now, cache.get("book"))
        }
        assertEquals(120, writes.size)
        assertEquals(3_600_000L, cache.get("book"))
    }

    @Test
    fun explicitPauseFlushesLatestPositionEvenInsideTheInterval() {
        cache.save("book", 1_000L)
        now = 2_000L
        cache.save("book", 3_000L)
        assertEquals(1_000L, disk["book"])
        cache.save("book", 3_000L, force = true)
        assertEquals(3_000L, disk["book"])
    }

    @Test
    fun backwardSeekOrRepeatWritesImmediately() {
        cache.save("book", 50_000L)
        now = 2_000L
        cache.save("book", 52_000L)
        cache.save("book", 0L)
        assertEquals(0L, disk["book"])
        assertEquals(0L, cache.get("book"))
    }

    @Test
    fun unchangedPositionsDoNotWriteEvenWhenForced() {
        disk["book"] = 5_000L
        repeat(100) {
            now += 30_000L
            cache.save("book", 5_000L, force = true)
        }
        assertEquals(0, writes.size)
    }

    @Test
    fun trackChangesHaveIndependentPositionsAndWriteClocks() {
        cache.save("first", 1_000L)
        now = 2_000L
        cache.save("first", 3_000L)
        cache.save("first", 3_000L, force = true)
        cache.save("second", 10_000L)
        assertEquals(3_000L, disk["first"])
        assertEquals(10_000L, disk["second"])
        assertEquals(3_000L, cache.get("first"))
    }

    @Test
    fun clearingPositionAlsoClearsTheCachedBookmark() {
        cache.save("book", 50_000L)
        cache.clear("book")
        assertFalse(disk.containsKey("book"))
        assertEquals(0L, cache.get("book"))
        cache.save("book", -10L)
        assertEquals(0L, cache.get("book"))
    }

    @Test
    fun evictingAnOldBookmarkFlushesPendingProgressBeforeReload() {
        val reads = mutableListOf<String>()
        val small = boundedCache(2, reads)
        small.save("first", 1_000L)
        now = 2_000L
        small.save("first", 3_000L)
        assertEquals(1_000L, disk["first"])
        small.get("second")
        small.get("third")
        assertEquals(3_000L, disk["first"])
        assertEquals(3_000L, small.get("first"))
        assertEquals(2, reads.count { it == "first" })
    }

    @Test
    fun recentlyReadBookmarkSurvivesWhileTheLeastUsedOneIsReleased() {
        val reads = mutableListOf<String>()
        val small = boundedCache(2, reads)
        small.get("first")
        small.get("second")
        small.get("first")
        small.get("third")
        small.get("first")
        assertEquals(1, reads.count { it == "first" })
        small.get("second")
        assertEquals(2, reads.count { it == "second" })
        assertTrue(writes.isEmpty()) // Reading/evicting unchanged positions does not rewrite them.
    }

    @Test
    fun scanningManyTracksKeepsRecentBookmarksAndReloadsOlderOnesFromStorage() {
        val reads = mutableListOf<String>()
        val bounded = boundedCache(256, reads)
        repeat(20_000) { index ->
            disk["track-$index"] = index.toLong()
            assertEquals(index.toLong(), bounded.get("track-$index"))
        }
        for (index in 19_744 until 20_000) assertEquals(index.toLong(), bounded.get("track-$index"))
        assertEquals(20_000, reads.size)
        assertEquals(0L, bounded.get("track-0"))
        assertEquals(20_001, reads.size)
        assertEquals(20_000, disk.size) // Eviction never deletes a saved bookmark.
        assertTrue(writes.isEmpty())
    }

    @Test
    fun failedEvictionDoesNotDiscardProgressAndCanBeRetried() {
        var rejectWrites = false
        val small = PositionCheckpointCache(
            { disk[it] ?: 0L },
            { id, position ->
                if (rejectWrites) error("Unavailable storage")
                disk[id] = position
            },
            { disk.remove(it) }, { now }, maxEntries = 1
        )
        small.save("first", 1_000L)
        now = 2_000L
        small.save("first", 3_000L)
        rejectWrites = true
        try {
            small.get("second")
            fail("The dirty bookmark must remain when its write fails")
        } catch (_: IllegalStateException) { }
        assertEquals(3_000L, small.get("first"))
        rejectWrites = false
        small.get("second")
        assertEquals(3_000L, disk["first"])
        assertEquals(3_000L, small.get("first"))
    }

    private fun boundedCache(capacity: Int, reads: MutableList<String>) = PositionCheckpointCache(
        { id -> reads.add(id); disk[id] ?: 0L },
        { id, position -> disk[id] = position; writes.add(id to position) },
        { disk.remove(it) }, { now }, maxEntries = capacity
    )
}
