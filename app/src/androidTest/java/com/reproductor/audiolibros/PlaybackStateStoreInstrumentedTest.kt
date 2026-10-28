package com.reproductor.audiolibros

import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses Android's real AtomicFile, Uri and JSON implementations in an isolated directory. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackStateStoreInstrumentedTest {
    private lateinit var directory: File
    private lateinit var store: PlaybackStateStore

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "playback-persistence-test")
        directory.deleteRecursively()
        assertTrue(directory.mkdirs())
        store = newStore()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun item(index: Int): MediaItem = MediaItem.Builder()
        .setMediaId("track-$index")
        .setUri("content://media/external/audio/media/$index")
        .build()

    private fun checkpoint(index: Int, position: Long = 42_000L, playing: Boolean = true) =
        PlaybackStateStore.Checkpoint(123L, index, position, playing, 1_000_000L, 10)

    private fun newStore(): PlaybackStateStore {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return PlaybackStateStore(object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        })
    }

    @Test
    fun testMissingStateCannotWriteACheckpoint() {
        assertNull(store.read())
        assertTrue(store.needsQueue(123L, 0))
        assertFalse(store.writeCheckpoint(checkpoint(0)))
    }

    @Test
    fun testOptionalMusicVolumeRestoresZeroAndNonzeroAndReadsLegacyState() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0)))
        assertNull(newStore().read()!!.musicVolume)
        for (volume in listOf(0, 7)) {
            assertTrue(store.writeCheckpoint(checkpoint(0).copy(musicVolume = volume)))
            val snapshot = newStore().read()!!
            assertEquals(volume, snapshot.musicVolume)
            assertEquals(42_000L, snapshot.positionMs)
            assertTrue(snapshot.desiredPlaying)
        }
        assertFalse(store.writeCheckpoint(checkpoint(0).copy(musicVolume = -1)))
        assertEquals(7, newStore().read()!!.musicVolume)
    }

    @Test
    fun testMalformedOptionalMusicVolumeDoesNotDiscardCheckpoint() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L).copy(musicVolume = 7)))
        val file = File(directory, "playback_checkpoint.json")
        val original = file.readText()
        for (invalid in listOf("-1", "2147483648", "1.5", "\"7\"", "true", "{}", "null")) {
            file.writeText(original.replace("\"musicVolume\":7", "\"musicVolume\":$invalid"))
            val snapshot = newStore().read()!!
            assertEquals(invalid, 99_000L, snapshot.positionMs)
            assertTrue(invalid, snapshot.desiredPlaying)
            assertNull(invalid, snapshot.musicVolume)
        }
    }

    @Test
    fun testClearSurvivesRestartEvenWithThePreviousPlayingCheckpointStillPresent() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val checkpointFile = File(directory, "playback_checkpoint.json")
        val previousCheckpoint = checkpointFile.readText()

        assertTrue(store.clear())
        assertEquals(previousCheckpoint, checkpointFile.readText())
        assertNull(newStore().read())
        assertNull(store.read())
        assertTrue(store.needsQueue(123L, 0))
        assertFalse(store.writeCheckpoint(checkpoint(0)))
        assertTrue(store.clear()) // Clearing an already empty state is also durable.
        assertNull(newStore().read())
    }

    @Test
    fun testNewQueueAfterClearRejectsThePreviousCheckpointInTheSameGeneration() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        assertTrue(store.clear())
        assertTrue(store.writeQueue(listOf(item(99)), checkpoint(0, 7_000L)))

        // Simulate death before the first separate checkpoint for the new queue.
        val restored = newStore().read()!!
        assertEquals("track-99", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
        assertTrue(store.writeCheckpoint(checkpoint(0, 8_000L)))
        assertTrue(newStore().read()!!.desiredPlaying)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testFailedClearPreservesTheLastCommittedQueueAndCanBeRetried() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        // Prevent AtomicFile from opening its staging file without altering the
        // committed queue. This is a real startWrite failure, not a mocked result.
        val blockedStagingFile = File(directory, "playback_queue.json.new")
        assertTrue(blockedStagingFile.mkdir())
        assertFalse(store.clear())
        assertFalse(store.needsQueue(123L, 0))
        assertEquals(99_000L, newStore().read()!!.positionMs)
        blockedStagingFile.deleteRecursively()
        assertTrue(store.clear())
        assertNull(newStore().read())
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testFailedQueueCommitKeepsItsPreviousTokenAndCheckpointUsable() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val blockedStagingFile = File(directory, "playback_queue.json.new")
        assertTrue(blockedStagingFile.mkdir())
        assertFalse(store.writeQueue(listOf(item(99)), checkpoint(0).copy(generation = 124L)))
        assertFalse(store.needsQueue(123L, 0))
        assertTrue(store.needsQueue(124L, 0))
        assertTrue(store.writeCheckpoint(checkpoint(0, 100_000L, playing = false)))
        val restored = newStore().read()!!
        assertEquals("track-0", restored.queue[restored.index].mediaId)
        assertEquals(100_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testOpeningFailureDoesNotTraverseTwentyThousandItemsAndCanBeRetried() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        var accesses = 0
        val items = object : AbstractList<MediaItem>() {
            override val size: Int = 20_000
            override fun get(index: Int): MediaItem {
                accesses++
                return item(index)
            }
        }
        val next = checkpoint(19_999, 7_000L, playing = false).copy(generation = 124L)
        val blocked = File(directory, "playback_queue.json.new")
        assertTrue(blocked.mkdir())
        // Keep the obstacle in place even when AtomicFile.openRead tries to
        // discard a stale staging file while checking the previous snapshot.
        File(blocked, "keep").writeText("blocked")

        assertFalse(store.writeQueue(items, next))
        assertEquals(0, accesses)
        assertFalse(store.needsQueue(123L, 0))
        assertTrue(store.needsQueue(124L, 19_999))
        val previous = newStore().read()!!
        assertEquals("track-0", previous.queue[previous.index].mediaId)
        assertEquals(99_000L, previous.positionMs)
        assertTrue(store.writeCheckpoint(checkpoint(0, 100_000L, playing = false)))
        assertEquals(100_000L, newStore().read()!!.positionMs)

        assertTrue(blocked.deleteRecursively())
        assertTrue(store.writeQueue(items, next))
        assertEquals(20_000, accesses)
        assertTrue(store.writeCheckpoint(next))
        val restored = newStore().read()!!
        assertEquals(20_000, restored.queue.size)
        assertEquals("track-19999", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testInvalidActiveItemAfterOpeningRollsBackWithoutChangingEligibility() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val queueFile = File(directory, "playback_queue.json")
        val previousQueue = queueFile.readText()
        val staging = File(directory, "playback_queue.json.new")
        var observedOpenTransaction = false
        val invalidItems = object : AbstractList<MediaItem>() {
            override val size: Int = 1
            override fun get(index: Int): MediaItem {
                observedOpenTransaction = staging.isFile
                return MediaItem.fromUri("https://example.invalid/audio.mp3")
            }
        }
        val next = checkpoint(0, 7_000L, playing = false).copy(generation = 124L)

        assertFalse(store.writeQueue(invalidItems, next))
        assertTrue(observedOpenTransaction)
        assertFalse(staging.exists())
        assertEquals(previousQueue, queueFile.readText())
        assertFalse(store.needsQueue(123L, 0))
        assertTrue(store.needsQueue(124L, 0))
        assertEquals(99_000L, newStore().read()!!.positionMs)
        assertTrue(store.writeCheckpoint(checkpoint(0, 100_000L, playing = false)))
        assertEquals(100_000L, newStore().read()!!.positionMs)

        assertTrue(store.writeQueue(listOf(item(99)), next))
        assertTrue(store.writeCheckpoint(next))
        assertEquals("track-99", newStore().read()!!.queue[0].mediaId)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testEncodingErrorPropagatesAfterClosingAndRollingBackTheTransaction() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val queueFile = File(directory, "playback_queue.json")
        val previousQueue = queueFile.readText()
        val staging = File(directory, "playback_queue.json.new")
        val injected = AssertionError("Injected queue encoding failure")
        var sawOpenDescriptor = false
        val items = object : AbstractList<MediaItem>() {
            override val size: Int = 1
            override fun get(index: Int): MediaItem {
                sawOpenDescriptor = hasOpenDescriptor(staging)
                throw injected
            }
        }
        val next = checkpoint(0, 7_000L, playing = false).copy(generation = 124L)
        var propagated: AssertionError? = null
        try {
            store.writeQueue(items, next)
        } catch (error: AssertionError) {
            propagated = error
        }

        assertSame(injected, propagated)
        assertTrue(sawOpenDescriptor)
        assertFalse(staging.exists())
        // Unlinking alone is insufficient on Linux: an unclosed descriptor would
        // still target the deleted staging file under /proc/self/fd.
        assertFalse(hasOpenDescriptor(staging))
        assertEquals(previousQueue, queueFile.readText())
        assertFalse(store.needsQueue(123L, 0))
        assertTrue(store.needsQueue(124L, 0))
        assertEquals(99_000L, newStore().read()!!.positionMs)
        assertTrue(store.writeCheckpoint(checkpoint(0, 100_000L, playing = false)))
        assertEquals(100_000L, newStore().read()!!.positionMs)
        assertTrue(store.writeQueue(listOf(item(99)), next))
        assertTrue(store.writeCheckpoint(next))
        val restored = newStore().read()!!
        assertEquals("track-99", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    private fun hasOpenDescriptor(file: File): Boolean {
        val target = file.canonicalPath
        return checkNotNull(File("/proc/self/fd").listFiles()).any { descriptor ->
            try {
                Os.readlink(descriptor.path).removeSuffix(" (deleted)") == target
            } catch (_: ErrnoException) {
                // A descriptor belonging to another thread may close during listing.
                false
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testRejectedQueueRenameDoesNotPublishItsTokenAndCanBeRetried() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val next = checkpoint(0, 7_000L, playing = false).copy(generation = 124L)
        withBlockedCommitTarget("playback_queue.json") {
            // startWrite and the content write succeed; finishWrite only logs
            // that it cannot replace this nonempty directory with the new file.
            assertFalse(store.writeQueue(listOf(item(99)), next))
            assertFalse(store.needsQueue(123L, 0))
            assertTrue(store.needsQueue(124L, 0))
        }

        assertTrue(store.writeCheckpoint(checkpoint(0, 100_000L, playing = false)))
        assertEquals(100_000L, newStore().read()!!.positionMs)
        assertTrue(store.writeQueue(listOf(item(99)), next))
        assertTrue(store.writeCheckpoint(next))
        val restored = newStore().read()!!
        assertEquals("track-99", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testRejectedCheckpointRenameReportsFailureAndCanBeRetried() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val paused = checkpoint(0, 100_000L, playing = false)
        withBlockedCommitTarget("playback_checkpoint.json") {
            assertFalse(store.writeCheckpoint(paused))
        }

        assertEquals(99_000L, newStore().read()!!.positionMs)
        assertTrue(store.writeCheckpoint(paused))
        val restored = newStore().read()!!
        assertEquals(100_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun testRejectedClearRenamePreservesTheQueueAndCanBeRetried() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        withBlockedCommitTarget("playback_queue.json") {
            assertFalse(store.clear())
            assertFalse(store.needsQueue(123L, 0))
        }

        assertTrue(store.writeCheckpoint(checkpoint(0, 100_000L, playing = false)))
        assertEquals(100_000L, newStore().read()!!.positionMs)
        assertTrue(store.clear())
        assertNull(newStore().read())
    }

    private fun withBlockedCommitTarget(name: String, action: () -> Unit) {
        val target = File(directory, name)
        val preserved = File(directory, "$name.preserved")
        assertTrue(target.renameTo(preserved))
        assertTrue(target.mkdir())
        File(target, "prevent-directory-replacement").writeText("keep")
        try {
            action()
        } finally {
            assertTrue(target.deleteRecursively())
            assertTrue(preserved.renameTo(target))
        }
    }

    @Test
    fun testInvalidWritesDoNotReplaceCommittedState() {
        val items = listOf(item(0))
        val valid = checkpoint(0)
        assertTrue(store.writeQueue(items, valid))
        assertTrue(store.writeCheckpoint(valid))
        val invalid = listOf(
            valid.copy(generation = 0L),
            valid.copy(index = -1),
            valid.copy(index = 1),
            valid.copy(positionMs = -1L),
            valid.copy(savedAtMs = 0L),
            valid.copy(bootCount = -2)
        )
        invalid.forEach {
            assertFalse(store.writeQueue(items, it))
            assertFalse(store.writeCheckpoint(it))
        }
        assertFalse(store.writeQueue(emptyList(), valid))
        assertFalse(store.writeQueue(listOf(MediaItem.fromUri("https://example.invalid/audio.mp3")), valid))
        val restored = newStore().read()!!
        assertEquals("track-0", restored.queue[restored.index].mediaId)
        assertEquals(valid.positionMs, restored.positionMs)
        assertTrue(restored.desiredPlaying)
    }

    @Test
    fun testSlowDiskCoalescesUpdatesAndRestoresTheLatestQueuePaused() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val writes = AtomicInteger()
        val writer = LatestTaskExecutor(executor)
        fun submit(items: List<MediaItem>, cp: PlaybackStateStore.Checkpoint, block: Boolean = false) {
            writer.execute {
                if (block) {
                    started.countDown()
                    check(unblock.await(10, TimeUnit.SECONDS))
                }
                check(!store.needsQueue(cp.generation, cp.index) || store.writeQueue(items, cp))
                check(store.writeCheckpoint(cp))
                writes.incrementAndGet()
            }
        }
        try {
            submit(listOf(item(0)), checkpoint(0), block = true)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val nextQueue = listOf(item(99))
            for (position in 1L..10_000L) {
                submit(nextQueue, checkpoint(0, position).copy(generation = 124L))
            }
            submit(nextQueue, checkpoint(0, 10_123L, playing = false).copy(generation = 124L))
            // A new service's read on the shared executor must follow the old
            // instance's last pending pause, even though that write was conflated.
            val restored = executor.submit<PlaybackStateStore.Snapshot?> { store.read() }
            unblock.countDown()
            val snapshot = restored.get(10, TimeUnit.SECONDS)!!
            assertEquals(2, writes.get())
            assertEquals("track-99", snapshot.queue[snapshot.index].mediaId)
            assertEquals(10_123L, snapshot.positionMs)
            assertFalse(snapshot.desiredPlaying)
        } finally {
            unblock.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun testQueueAboveEntryLimitKeepsTheActiveTrackBeyondTwentyThousand() {
        val items = List(20_001, ::item)
        val checkpoint = checkpoint(20_000)
        assertTrue(store.writeQueue(items, checkpoint))
        assertTrue(store.writeCheckpoint(checkpoint))
        val restored = store.read()!!
        assertEquals(20_000, restored.queue.size)
        assertEquals("track-20000", restored.queue[restored.index].mediaId)
        assertEquals(42_000L, restored.positionMs)
        assertTrue(restored.desiredPlaying)
        assertFalse(store.needsQueue(123L, 20_000))
        assertTrue(store.needsQueue(123L, 19_999))
        assertFalse(store.writeCheckpoint(checkpoint(19_999)))
    }

    @Test
    fun testUtf8QueueAboveByteLimitKeepsTheActiveTrackAndStaysBounded() {
        val text = "é".repeat(8_192)
        val metadata = MediaMetadata.Builder().setTitle(text).setArtist(text).setAlbumTitle(text).build()
        val items = List(400) { item(it).buildUpon().setMediaMetadata(metadata).build() }
        val checkpoint = checkpoint(399)
        assertTrue(store.writeQueue(items, checkpoint))
        assertTrue(store.writeCheckpoint(checkpoint))
        val restored = store.read()!!
        assertTrue(restored.queue.size < items.size)
        assertEquals("track-399", restored.queue[restored.index].mediaId)
        assertEquals(42_000L, restored.positionMs)
        assertTrue(File(directory, "playback_queue.json").length() <= 16L * 1024L * 1024L)
    }

    @Test
    fun testInterruptedWindowReplacementRejectsAnOlderCheckpointInTheSameGeneration() {
        val items = List(20_001, ::item)
        assertTrue(store.writeQueue(items, checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(10, 99_000L)))
        // The old index is still present, but this queue commit represents a newer action.
        assertTrue(store.writeQueue(items, checkpoint(1, 7_000L)))
        val restored = store.read()!!
        assertEquals("track-1", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    fun testUnavailableCheckpointTargetFallsBackToTheInitialTrackPaused() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 9_000L)))
        val file = File(directory, "playback_checkpoint.json")
        file.writeText(JSONObject(file.readText()).put("index", 100).toString())
        val restored = store.read()!!
        assertEquals("track-0", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    fun testCorruptCheckpointFallsBackPausedAndExplicitPauseIsPreserved() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 9_000L, playing = false)))
        assertFalse(store.read()!!.desiredPlaying)
        assertEquals(9_000L, store.read()!!.positionMs)
        File(directory, "playback_checkpoint.json").writeText("{truncated")
        val restored = store.read()!!
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    fun testOmittingNonLocalItemsRemapsTheActiveIndex() {
        val remote = MediaItem.fromUri("https://example.invalid/audio.mp3")
        assertTrue(store.writeQueue(listOf(remote, item(1)), checkpoint(1)))
        assertTrue(store.writeCheckpoint(checkpoint(1)))
        val restored = store.read()!!
        assertEquals(0, restored.index)
        assertEquals("track-1", restored.queue[0].mediaId)
        assertTrue(restored.desiredPlaying)
    }

    @Test
    fun testLegacySnapshotWithoutQueueTokenStillRestores() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0)))
        val queueFile = File(directory, "playback_queue.json")
        val queue = JSONObject(queueFile.readText())
        queue.remove("queueToken")
        queue.getJSONObject("initialCheckpoint").remove("queueToken")
        queueFile.writeText(queue.toString())
        val checkpointFile = File(directory, "playback_checkpoint.json")
        val checkpoint = JSONObject(checkpointFile.readText())
        checkpoint.remove("queueToken")
        checkpointFile.writeText(checkpoint.toString())
        val restored = store.read()!!
        assertEquals("track-0", restored.queue[restored.index].mediaId)
        assertTrue(restored.desiredPlaying)
    }

    @Test
    fun testQueueEncodingPreservesEscapedUnicodeMetadataDurationAndSourceOrder() {
        val title = "Voz \"clara\"\\archivo\n日本語 🎧 é"
        val artist = "Narrador\tuno"
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle("Libro\rcompleto")
            .setDurationMs(172_800_000L)
            .build()
        val items = List(4) { item(it).buildUpon().setMediaMetadata(metadata).build() }
        assertTrue(store.writeQueue(items, checkpoint(2)))
        assertTrue(store.writeCheckpoint(checkpoint(2)))
        val restored = newStore().read()!!
        assertEquals(listOf("track-0", "track-1", "track-2", "track-3"), restored.queue.map { it.mediaId })
        assertEquals(2, restored.index)
        assertEquals(title, restored.queue[2].mediaMetadata.title)
        assertEquals(artist, restored.queue[2].mediaMetadata.artist)
        assertEquals("Libro\rcompleto", restored.queue[2].mediaMetadata.albumTitle)
        assertEquals(172_800_000L, restored.queue[2].mediaMetadata.durationMs)
    }

    @Test
    fun testMalformedQueueIsRejectedAndInvalidatesCachedCheckpointEligibility() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0)))
        val file = File(directory, "playback_queue.json")
        val original = file.readText()
        val duplicate = JSONObject(original).let { queue ->
            val entries = queue.getJSONArray("items")
            entries.put(JSONObject(entries.getJSONObject(0).toString()))
            queue.toString()
        }
        val badMetadata = JSONObject(original).let { queue ->
            queue.getJSONArray("items").getJSONObject(0).put("title", true)
            queue.toString()
        }
        val corrupt = listOf(
            "{truncated",
            JSONObject(original).put("version", 2).toString(),
            JSONObject(original).put("queueToken", "").toString(),
            JSONObject(original).put("items", true).toString(),
            JSONObject(original).put("items", JSONArray().put(false)).toString(),
            JSONObject(original).put("items", JSONArray()).toString(),
            duplicate,
            badMetadata
        )
        corrupt.forEach { contents ->
            file.writeText(original)
            assertNotNull(store.read())
            assertFalse(store.needsQueue(123L, 0))
            file.writeText(contents)
            assertNull("Malformed queue: $contents", store.read())
            assertTrue(store.needsQueue(123L, 0))
            assertFalse(store.writeCheckpoint(checkpoint(0)))
        }
    }

    @Test
    fun testInvalidRequiredQueueNumbersNeverCoerceOrOverflow() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0)))
        val file = File(directory, "playback_queue.json")
        val original = file.readText()
        val invalidGenerations = listOf("0", "-1", "1.5", "123.0", "123e0", "\"123\"", "true", "9223372036854775808")
        invalidGenerations.forEach { invalid ->
            file.writeText(original.replace("\"generation\":123", "\"generation\":$invalid"))
            assertNull("generation=$invalid", newStore().read())
        }
        val invalidIndices = listOf("-1", "2147483648", "0.5", "0.0", "0e0", "\"0\"", "false", "9223372036854775808")
        invalidIndices.forEach { invalid ->
            file.writeText(original.replace("\"sourceIndex\":0", "\"sourceIndex\":$invalid"))
            assertNull("sourceIndex=$invalid", newStore().read())
        }
    }

    @Test
    fun testMalformedCheckpointNumbersFallBackToEmbeddedPositionPaused() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val file = File(directory, "playback_checkpoint.json")
        val original = file.readText()
        val replacements = listOf(
            "\"positionMs\":99000" to "\"positionMs\":9223372036854775808",
            "\"positionMs\":99000" to "\"positionMs\":1.5",
            "\"positionMs\":99000" to "\"positionMs\":99000.0",
            "\"positionMs\":99000" to "\"positionMs\":99e3",
            "\"positionMs\":99000" to "\"positionMs\":\"99000\"",
            "\"positionMs\":99000" to "\"positionMs\":-1",
            "\"index\":0" to "\"index\":2147483648",
            "\"bootCount\":10" to "\"bootCount\":2147483648",
            "\"bootCount\":10" to "\"bootCount\":-2",
            "\"savedAtMs\":1000000" to "\"savedAtMs\":0",
            "\"desiredPlaying\":true" to "\"desiredPlaying\":\"true\""
        )
        replacements.forEach { (valid, invalid) ->
            assertTrue(original.contains(valid))
            file.writeText(original.replace(valid, invalid))
            val restored = newStore().read()!!
            assertEquals(invalid, 7_000L, restored.positionMs)
            assertFalse(invalid, restored.desiredPlaying)
        }
    }

    @Test
    fun testOversizedCheckpointFallsBackAndOversizedQueueIsRejected() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val checkpointFile = File(directory, "playback_checkpoint.json")
        checkpointFile.appendText(" ".repeat(4_096))
        val fallback = newStore().read()!!
        assertEquals(7_000L, fallback.positionMs)
        assertFalse(fallback.desiredPlaying)

        // Add valid trailing whitespace in blocks so the parser cannot be the
        // reason for rejection and the test itself does not allocate a giant String.
        val block = ByteArray(8_192) { ' '.code.toByte() }
        FileOutputStream(File(directory, "playback_queue.json"), true).use { output ->
            repeat(2_049) { output.write(block) }
        }
        assertNull(newStore().read())
    }

    @Test
    fun testUnavailableRecentTargetFallsBackAfterFilteringRemoteUri() {
        assertTrue(store.writeQueue(listOf(item(0), item(1)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(1, 99_000L)))
        val file = File(directory, "playback_queue.json")
        val queue = JSONObject(file.readText())
        queue.getJSONArray("items").getJSONObject(1).put("uri", "https://example.invalid/audio.mp3")
        file.writeText(queue.toString())
        val restored = newStore().read()!!
        assertEquals(1, restored.queue.size)
        assertEquals("track-0", restored.queue[restored.index].mediaId)
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
    }

    @Test
    fun testLongAudioAndLargestSupportedCheckpointNumbersRestoreExactly() {
        val checkpoint = checkpoint(0).copy(
            generation = Long.MAX_VALUE,
            positionMs = Long.MAX_VALUE,
            savedAtMs = Long.MAX_VALUE,
            bootCount = Int.MAX_VALUE
        )
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint))
        assertTrue(store.writeCheckpoint(checkpoint))
        val restored = newStore().read()!!
        assertEquals(Long.MAX_VALUE, restored.generation)
        assertEquals(Long.MAX_VALUE, restored.positionMs)
        assertEquals(Long.MAX_VALUE, restored.savedAtMs)
        assertEquals(Int.MAX_VALUE, restored.bootCount)
        assertTrue(restored.desiredPlaying)
    }

    @Test
    fun testLegacySnapshotWithoutDurationOrEmbeddedCheckpointStillRestores() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0)))
        val queueFile = File(directory, "playback_queue.json")
        val queue = JSONObject(queueFile.readText())
        queue.remove("queueToken")
        queue.remove("initialCheckpoint")
        queue.getJSONArray("items").getJSONObject(0).remove("durationMs")
        queueFile.writeText(queue.toString())
        val checkpointFile = File(directory, "playback_checkpoint.json")
        val checkpoint = JSONObject(checkpointFile.readText())
        checkpoint.remove("queueToken")
        checkpointFile.writeText(checkpoint.toString())
        val restored = newStore().read()!!
        assertEquals(42_000L, restored.positionMs)
        assertNull(restored.queue[0].mediaMetadata.durationMs)
        assertTrue(restored.desiredPlaying)
    }

    @Test
    fun testInterruptedAtomicWriteRestoresTheLastCompleteQueueFromLegacyBackup() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val queueFile = File(directory, "playback_queue.json")
        assertTrue(queueFile.renameTo(File(directory, "playback_queue.json.bak")))
        queueFile.writeText("{truncated")
        val restored = newStore().read()!!
        assertEquals("track-0", restored.queue[restored.index].mediaId)
        assertEquals(99_000L, restored.positionMs)
        assertTrue(restored.desiredPlaying)
        assertTrue(store.clear())
        assertNull(newStore().read())
    }

    @Test
    fun testStreamingRestoresReorderedFieldsAndIgnoresUnknownNestedValues() {
        assertTrue(store.writeQueue(listOf(item(0), item(1)), checkpoint(1, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(1, 99_000L)))
        val file = File(directory, "playback_queue.json")
        val original = JSONObject(file.readText())
        val unknown = JSONObject().put("nested", JSONArray().put(JSONObject().put("future", true)))
        original.getJSONArray("items").getJSONObject(0).put("unknown", unknown)
        original.getJSONObject("initialCheckpoint").put("unknown", unknown)
        val reordered = JSONObject()
            .put("items", original.getJSONArray("items"))
            .put("unknown", unknown)
            .put("initialCheckpoint", original.getJSONObject("initialCheckpoint"))
            .put("queueToken", original.get("queueToken"))
            .put("generation", original.get("generation"))
            .put("version", original.get("version"))
        file.writeText(reordered.toString())
        val restored = newStore().read()!!
        assertEquals(listOf("track-0", "track-1"), restored.queue.map { it.mediaId })
        assertEquals(1, restored.index)
        assertEquals(99_000L, restored.positionMs)
        assertTrue(restored.desiredPlaying)
    }

    @Test
    fun testStreamingDuplicateFieldsKeepTheLastValueLikeExistingSnapshots() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val file = File(directory, "playback_queue.json")
        val original = file.readText().replace(
            "\"sourceIndex\":0", "\"sourceIndex\":\"invalid\",\"sourceIndex\":0"
        )
        // Semantically invalid earlier properties are superseded by the final ones.
        file.writeText("{\"version\":0,\"items\":[false],\"generation\":false," + original.drop(1))
        assertEquals(99_000L, newStore().read()!!.positionMs)

        // The reverse order must reject the snapshot, not retain the earlier array.
        file.writeText(original.dropLast(1) + ",\"items\":false}")
        assertNull(newStore().read())
    }

    @Test
    fun testStreamingFiltersRemoteEntriesBeforeValidatingTheirMetadata() {
        assertTrue(store.writeQueue(listOf(item(0), item(1)), checkpoint(1, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(1, 99_000L)))
        val file = File(directory, "playback_queue.json")
        val queue = JSONObject(file.readText())
        val remote = JSONObject()
            .put("title", JSONObject().put("invalid", true))
            .put("artist", false)
            .put("album", JSONArray())
            .put("mediaId", 123)
            .put("sourceIndex", 0)
            .put("uri", "https://example.invalid/audio.mp3")
        queue.getJSONArray("items").put(0, remote)
        file.writeText(queue.toString())
        val restored = newStore().read()!!
        assertEquals(1, restored.queue.size)
        assertEquals("track-1", restored.queue[0].mediaId)
        assertEquals(0, restored.index)
        assertEquals(99_000L, restored.positionMs)

        // Omitted URIs cannot hide a duplicate source index.
        remote.put("sourceIndex", 1)
        file.writeText(queue.toString())
        assertNull(newStore().read())
    }

    @Test
    fun testStreamingOptionalDurationNeverCoercesOrDiscardsValidProgress() {
        val withDuration = item(0).buildUpon().setMediaMetadata(
            MediaMetadata.Builder().setDurationMs(123_000L).build()
        ).build()
        assertTrue(store.writeQueue(listOf(withDuration), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val file = File(directory, "playback_queue.json")
        val original = file.readText()
        listOf("123000.0", "123e3", "\"123000\"", "9223372036854775808", "true", "{}", "[]", "null", "-1", "0")
            .forEach { invalid ->
                file.writeText(original.replace("\"durationMs\":123000", "\"durationMs\":$invalid"))
                val restored = newStore().read()!!
                assertEquals(invalid, 99_000L, restored.positionMs)
                assertNull(invalid, restored.queue[0].mediaMetadata.durationMs)
            }
    }

    @Test
    fun testStreamingRejectsNonstandardJsonAndTrailingValues() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0)))
        assertTrue(store.writeCheckpoint(checkpoint(0)))
        val file = File(directory, "playback_queue.json")
        val original = file.readText()
        val invalid = listOf(
            original.replace("\"generation\":123", "\"generation\":0x7b"),
            original.replace("\"generation\":123", "\"generation\":0173"),
            original.replace("\"version\":1", "version:1"),
            original.replace("\"version\":1", "'version':1"),
            "/* comment */$original",
            "$original {}",
            "$original trailing"
        )
        invalid.forEach { contents ->
            file.writeText(contents)
            assertNull(newStore().read())
        }
    }

    @Test
    fun testStreamingBoundsUnknownNestingAndKeepsCheckpointFallback() {
        assertTrue(store.writeQueue(listOf(item(0)), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val queueFile = File(directory, "playback_queue.json")
        val checkpointFile = File(directory, "playback_checkpoint.json")
        val originalQueue = queueFile.readText()
        val originalCheckpoint = checkpointFile.readText()
        val nested = "[".repeat(66) + "0" + "]".repeat(66)
        checkpointFile.writeText(originalCheckpoint.dropLast(1) + ",\"unknown\":$nested}")
        val restored = newStore().read()!!
        assertEquals(7_000L, restored.positionMs)
        assertFalse(restored.desiredPlaying)
        checkpointFile.writeText(originalCheckpoint)
        queueFile.writeText(originalQueue.dropLast(1) + ",\"unknown\":$nested}")
        assertNull(newStore().read())
    }

    @Test
    fun testStreamingEnforcesExactUtf8ByteLimitsIncludingTrailingWhitespace() {
        val unicode = item(0).buildUpon().setMediaMetadata(
            MediaMetadata.Builder().setTitle("é漢\uD83D\uDE00").build()
        ).build()
        assertTrue(store.writeQueue(listOf(unicode), checkpoint(0, 7_000L)))
        assertTrue(store.writeCheckpoint(checkpoint(0, 99_000L)))
        val queueFile = File(directory, "playback_queue.json")
        val checkpointFile = File(directory, "playback_checkpoint.json")
        padWithWhitespace(queueFile, 16L * 1024L * 1024L)
        padWithWhitespace(checkpointFile, 4_096L)
        assertEquals(99_000L, newStore().read()!!.positionMs)
        checkpointFile.appendText(" ")
        val fallback = newStore().read()!!
        assertEquals(7_000L, fallback.positionMs)
        assertFalse(fallback.desiredPlaying)
        queueFile.appendText(" ")
        assertNull(newStore().read())
    }

    private fun padWithWhitespace(file: File, size: Long) {
        val block = ByteArray(8_192) { ' '.code.toByte() }
        var remaining = size - file.length()
        assertTrue(remaining >= 0L)
        FileOutputStream(file, true).use { output ->
            while (remaining > 0L) {
                val count = minOf(remaining, block.size.toLong()).toInt()
                output.write(block, 0, count)
                remaining -= count
            }
        }
        assertEquals(size, file.length())
    }
}
