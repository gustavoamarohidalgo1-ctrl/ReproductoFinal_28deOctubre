package com.reproductor.audiolibros

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LibrarySnapshotCacheTest {
    @Test fun twentyReturnsToUnchangedLargeLibraryDoNotRescanOrRebuildRows() {
        val cache = LibrarySnapshotCache<Int, Any>()
        val stamp = stamp()
        var scans = 0
        var rowsCreated = 0
        var builds = 0
        fun load(): LibrarySnapshotCache.Snapshot<Int, Any> {
            val ticket = cache.beginLoad()
            return cache.reuse(ticket, stamp) ?: run {
                scans++
                val items = List(20_000) { rowsCreated++; it }
                cache.update(ticket, items, stamp) { builds++; Any() }!!
            }
        }
        val first = load()
        repeat(20) { assertSame(first, load()) }
        assertEquals(1, scans)
        assertEquals(20_000, rowsCreated)
        assertEquals(1, builds)
    }

    @Test fun providerGenerationVersionAndMountedVolumeChangesRequireScanning() {
        val cache = LibrarySnapshotCache<Int, Any>()
        val primary = LibrarySourceStamp.Volume("primary-v1", 7)
        val sd = LibrarySourceStamp.Volume("sd-v1", 9)
        val original = LibrarySourceStamp(mapOf("external_primary" to primary, "sd" to sd))
        cache.update(cache.beginLoad(), listOf(1), original) { Any() }
        val changed = listOf(
            original.copy(volumes = original.volumes + ("sd" to sd.copy(generation = 10))),
            original.copy(volumes = original.volumes + ("external_primary" to primary.copy(version = "reset"))),
            LibrarySourceStamp(mapOf("external_primary" to primary)),
            original.copy(volumes = original.volumes + ("new-sd" to sd))
        )
        changed.forEach { assertNull(cache.reuse(cache.beginLoad(), it)) }
    }

    @Test fun observerOrManualInvalidationForcesScanningEvenWithIdenticalGeneration() {
        val cache = LibrarySnapshotCache<Int, Any>()
        val stamp = stamp()
        val first = cache.update(cache.beginLoad(), listOf(1), stamp) { Any() }
        cache.invalidateSource()
        val ticket = cache.beginLoad()
        assertNull(cache.reuse(ticket, stamp))
        assertSame(first, cache.snapshot())
        assertSame(first, cache.update(ticket, listOf(1), stamp) { error("Unchanged rows") })
        assertSame(first, cache.reuse(cache.beginLoad(), stamp))
    }

    @Test fun invalidationDuringScanCannotValidateTheResultForFutureReuse() {
        val cache = LibrarySnapshotCache<Int, Any>()
        val stamp = stamp()
        val snapshot = cache.update(cache.beginLoad(), listOf(1), stamp) {
            cache.invalidateSource()
            Any()
        }
        assertNotNull(snapshot)
        assertSame(snapshot, cache.snapshot())
        assertNull(cache.reuse(cache.beginLoad(), stamp))
    }

    @Test fun unsupportedOrUnstableProviderNeverReusesASnapshotWithoutScanning() {
        val cache = LibrarySnapshotCache<Int, Any>()
        val stamp = stamp()
        cache.update(cache.beginLoad(), listOf(1), stamp) { Any() }
        assertNull(cache.reuse(cache.beginLoad(), null))
        cache.update(cache.beginLoad(), listOf(1), null) { error("Unchanged rows") }
        assertNull(cache.reuse(cache.beginLoad(), stamp))
    }

    @Test fun cancelledRefreshCannotAttachItsProviderStampToThePreviousSnapshot() {
        val cache = LibrarySnapshotCache<Int, Any>()
        val oldStamp = stamp()
        val newStamp = stamp(2)
        val first = cache.update(cache.beginLoad(), listOf(1), oldStamp) { Any() }
        val ticket = cache.beginLoad()
        assertNull(cache.update(ticket, listOf(2), newStamp) { cache.cancel(ticket); Any() })
        assertNull(cache.reuse(cache.beginLoad(), newStamp))
        assertSame(first, cache.reuse(cache.beginLoad(), oldStamp))
    }

    private fun stamp(generation: Long = 1L) = LibrarySourceStamp(
        mapOf("external_primary" to LibrarySourceStamp.Volume("provider-v1", generation))
    )

    @Test fun unchangedRefreshReusesDerivedObjectsAndRevision() {
        val cache = LibrarySnapshotCache<String, Any>()
        val first = cache.update(cache.beginLoad(), listOf("first", "second")) { Any() }!!
        val second = cache.update(cache.beginLoad(), arrayListOf("first", "second")) {
            error("Unchanged catalogues must not rebuild MediaItems or indices")
        }
        assertSame(first, second)
        assertSame(first, cache.snapshot())
    }

    @Test fun snapshotCannotChangeWhenQueryListIsReused() {
        val cache = LibrarySnapshotCache<String, List<String>>()
        val query = arrayListOf("original")
        val first = cache.update(cache.beginLoad(), query) { it }!!
        query[0] = "mutated"
        query.add("new")
        assertEquals(listOf("original"), first.items)
        assertSame(first.items, first.derived)
        try {
            (first.items as MutableList<String>).add("mutation")
            fail("Published snapshots must be immutable during asynchronous diffing")
        } catch (_: UnsupportedOperationException) {
            // Expected: read-only Kotlin List alone would not prevent this mutation.
        }
    }

    @Test fun olderBuildFinishingLastCannotOverwriteNewerCatalogue() {
        val cache = LibrarySnapshotCache<String, String>()
        val oldTicket = cache.beginLoad()
        val building = CountDownLatch(1)
        val finishBuild = CountDownLatch(1)
        val oldResult = AtomicReference<LibrarySnapshotCache.Snapshot<String, String>?>(null)
        val worker = thread {
            oldResult.set(cache.update(oldTicket, listOf("old")) {
                building.countDown()
                check(finishBuild.await(5, TimeUnit.SECONDS))
                "old media items"
            })
        }
        assertTrue(building.await(5, TimeUnit.SECONDS))
        val newest = cache.update(cache.beginLoad(), listOf("new")) { "new media items" }!!
        finishBuild.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertNull(oldResult.get())
        assertSame(newest, cache.snapshot())
    }

    @Test fun cancellationDuringBuildRetainsLastCompleteSnapshot() {
        val cache = LibrarySnapshotCache<String, String>()
        val original = cache.update(cache.beginLoad(), listOf("original")) { "original" }
        val ticket = cache.beginLoad()
        val cancelled = cache.update(ticket, listOf("replacement")) {
            cache.cancel(ticket)
            "replacement"
        }
        assertNull(cancelled)
        assertSame(original, cache.snapshot())
    }

    @Test fun cancellingAnOlderActivityDoesNotCancelItsReplacement() {
        val cache = LibrarySnapshotCache<String, String>()
        val oldTicket = cache.beginLoad()
        val newTicket = cache.beginLoad()
        cache.cancel(oldTicket)
        val result = cache.update(newTicket, listOf("new screen")) { "new" }
        assertNotNull(result)
        assertSame(result, cache.snapshot())
    }

    @Test fun revokedPermissionClearsSnapshotAndRejectsInFlightPublication() {
        val cache = LibrarySnapshotCache<String, String>()
        cache.update(cache.beginLoad(), listOf("private media")) { "media" }
        val ticket = cache.beginLoad()
        val result = cache.update(ticket, listOf("in flight")) {
            cache.clear()
            "must not publish"
        }
        assertNull(result)
        assertNull(cache.snapshot())
        assertNotNull(cache.update(cache.beginLoad(), emptyList()) { "permission restored" })
    }

    @Test fun removedAndReorderedFilesInvalidateDerivedQueue() {
        val cache = LibrarySnapshotCache<String, String>()
        val first = cache.update(cache.beginLoad(), listOf("a", "b", "c")) { it.joinToString() }!!
        val second = cache.update(cache.beginLoad(), listOf("c", "a")) { it.joinToString() }!!
        assertNotEquals(first.revision, second.revision)
        assertEquals("c, a", second.derived)
        assertEquals(listOf("a", "b", "c"), first.items)
    }
}
