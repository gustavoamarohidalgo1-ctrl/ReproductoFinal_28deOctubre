package com.reproductor.audiolibros

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in exploratory probe, matching the original persistence research workload.
 * Run with AndroidJUnitRunner arguments: -e measurePersistence true -e class
 * com.reproductor.audiolibros.PersistencePerformanceInstrumentedTest
 *
 * Only an isolated cache directory is used for playback state. No service or real
 * playback files are opened. Results are written under externalFiles/validation.
 * Three samples are descriptive, not a production benchmark or tail-latency test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PersistencePerformanceInstrumentedTest {
    private fun nanos(): Long = SystemClock.elapsedRealtimeNanos()

    private fun allocated(): Long? = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()

    private fun allocationDelta(before: Long?): Any {
        val after = allocated()
        return if (before != null && after != null && after >= before) after - before else JSONObject.NULL
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    // Match PerformanceResearchProbe.kt from the 2026-09-22 baseline exactly.
    private fun audio(index: Int) = AudioItem(
        index.toLong(), "Capítulo $index", "Autor de prueba", "Audiolibro de prueba",
        3_600_000L, Uri.parse("content://media/external/audio/media/$index"),
        "Audiobooks/Prueba", 144_000_000L
    )

    @Test
    fun measurePersistenceScaling() {
        assumeTrue(
            "Opt in with -e measurePersistence true",
            InstrumentationRegistry.getArguments().getString("measurePersistence") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "persistence-performance-${nanos()}")
        assertTrue(directory.mkdirs())
        val outputDirectory = File(checkNotNull(context.getExternalFilesDir(null)), "validation")
        assertTrue(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val outputFile = File(outputDirectory, "persistence-performance.json")
        val rows = JSONArray()
        val report = JSONObject()
            .put("environment", "Exploratory instrumented probe; synthetic metadata; three samples per size")
            .put("metrics_scope", "ART allocated bytes are process-wide cumulative allocations, not peak or retained RAM; PSS is this process only")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("device_model", Build.MODEL)
            .put("debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("warmup_entries", 100)
            .put("runs_per_size", 3)
            .put("rows", rows)
        fun record(row: JSONObject) {
            rows.put(row)
            outputFile.writeText(report.toString(2))
        }
        val storeContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val warmStore = PlaybackStateStore(storeContext)
            val warmCheckpoint = PlaybackStateStore.Checkpoint(1L, 99, 123_456L, false, 1_000_000L, 1)
            assertTrue(warmStore.writeQueue(List(100) { audio(it).toMediaItem() }, warmCheckpoint))
            assertTrue(warmStore.writeCheckpoint(warmCheckpoint))
            assertNotNull(warmStore.read())

            for (count in listOf(100, 1_000, 10_000, 20_000)) {
                repeat(3) { run ->
                    val audios = List(count, ::audio)
                    val modelAllocated = allocated()
                    val modelStart = nanos()
                    val models = audios.map { it.toMediaItem() }
                    val modelMs = (nanos() - modelStart) / 1e6
                    val modelBytes = allocationDelta(modelAllocated)
                    val checkpoint = PlaybackStateStore.Checkpoint(
                        count * 10L + run, count - 1, 123_456L, false, 1_000_000L, 1
                    )
                    val store = PlaybackStateStore(storeContext)

                    val writeAllocated = allocated()
                    val writeStart = nanos()
                    assertTrue(store.writeQueue(models, checkpoint))
                    assertTrue(store.writeCheckpoint(checkpoint))
                    val writeMs = (nanos() - writeStart) / 1e6
                    val writeBytes = allocationDelta(writeAllocated)

                    val readAllocated = allocated()
                    val readStart = nanos()
                    val snapshot = checkNotNull(store.read())
                    val readMs = (nanos() - readStart) / 1e6
                    val readBytes = allocationDelta(readAllocated)
                    assertEquals(count, snapshot.queue.size)
                    assertEquals((count - 1).toString(), snapshot.queue[snapshot.index].mediaId)
                    assertEquals(123_456L, snapshot.positionMs)
                    record(
                        JSONObject().put("type", "models_and_persistence").put("count", count).put("run", run)
                            .put("model_ms", modelMs).put("model_allocated_bytes", modelBytes)
                            .put("write_queue_and_checkpoint_ms", writeMs).put("write_allocated_bytes", writeBytes)
                            .put("read_ms", readMs).put("read_allocated_bytes", readBytes)
                            .put("queue_file_bytes", File(directory, "playback_queue.json").length())
                            .put("process_pss_kb_after", pssKb())
                    )
                    val checkpointTimes = JSONArray()
                    repeat(20) { index ->
                        val started = nanos()
                        assertTrue(store.writeCheckpoint(checkpoint.copy(positionMs = checkpoint.positionMs + index + 1)))
                        checkpointTimes.put((nanos() - started) / 1e6)
                    }
                    record(
                        JSONObject().put("type", "checkpoint_only").put("count", count).put("run", run)
                            .put("samples_ms", checkpointTimes)
                    )
                }
            }
        } finally {
            outputFile.writeText(report.toString(2))
            directory.deleteRecursively()
        }
    }
}
