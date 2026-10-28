package com.reproductor.audiolibros

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in elapsed-time test, skipped by the normal suite. For a full day pass
 * -e continuitySoakSeconds 86400 to am instrument. An optional
 * -e continuitySoakSourcePath /readable/local/audio.mp3 uses a device fixture;
 * otherwise the packaged 320 kbps MP3 exercises decoding and unchanged repeat-one.
 *
 * Reports service/controller continuity and durable checkpoints. It does not
 * measure physical sound, Bluetooth reliability, battery life or process death.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LongPlaybackSoakInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun localAudioKeepsAdvancingForTheRequestedRealDuration() {
        val arguments = InstrumentationRegistry.getArguments()
        val durationArgument = arguments.getString("continuitySoakSeconds")
        assumeTrue("Set continuitySoakSeconds to opt into real-time endurance testing", durationArgument != null)
        val durationSeconds = requireNotNull(durationArgument).toLong()
        require(durationSeconds in 10..604_800) { "continuitySoakSeconds must be between 10 s and 7 days" }
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        val suppliedSource = arguments.getString("continuitySoakSourcePath")
        val source = suppliedSource?.let(::File) ?: File(context.filesDir, "continuity-soak-320kbps.mp3")
        if (suppliedSource == null) {
            instrumentation.context.assets.open("silence-320kbps.mp3").use { input ->
                source.outputStream().use(input::copyTo)
            }
        }
        require(source.isFile && source.canRead()) { "The soak source must be a readable local file" }
        val evidenceDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "validation")
        check(evidenceDirectory.exists() || evidenceDirectory.mkdirs())
        val evidence = AtomicFile(File(evidenceDirectory, "continuity-soak.json"))
        val samples = JSONArray()
        val errors = CopyOnWriteArrayList<String>()
        val states = CopyOnWriteArrayList<Int>()
        val report = JSONObject()
            .put("requestedDurationSeconds", durationSeconds)
            .put("sourceName", source.name)
            .put("startedAtUnixMs", System.currentTimeMillis())
            .put("elapsedRealtimeMs", 0)
            .put("physicalAudioMeasured", false)
            .put("audioCountersAreServiceLifetime", true)
            .put("audioDiagnosticsDelivery", "Latest session extras; updates are coalesced and may arrive late under load")
            .put("samples", samples)
            .put("status", "starting")
        writeReport(evidence, report)
        Log.i(TAG, "Soak evidence: ${evidence.baseFile.absolutePath}")

        context.startActivity(Intent().setComponent(PlaybackTestContract.activity(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val future = onMain {
            MediaController.Builder(context,
                SessionToken(context, PlaybackTestContract.service(context))).buildAsync()
        }
        var controller: MediaController? = null
        var beganAt = 0L
        val testMediaId = "continuity-soak-${System.nanoTime()}"
        try {
            val active = future.get(15, TimeUnit.SECONDS)
            controller = active
            await { active.sessionExtras.getBoolean(PlaybackTestContract.STATE_RESTORED) }
            val initialUnderruns = onMain { active.sessionExtras.getLong(PlaybackTestContract.AUDIO_UNDERRUNS) }
            val initialSinkErrors = onMain { active.sessionExtras.getLong(PlaybackTestContract.AUDIO_SINK_ERRORS) }
            report.put("audioUnderrunsAtStart", initialUnderruns)
                .put("audioSinkErrorsAtStart", initialSinkErrors)
            onMain {
                active.setMediaItem(MediaItem.Builder()
                    .setMediaId(testMediaId)
                    .setUri(Uri.fromFile(source)).build(), 0)
                active.prepare()
                active.play()
            }
            await { active.isPlaying && active.currentPosition > 0 }
            onMain {
                active.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        states.add(playbackState)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        errors.add("${error.errorCodeName}: ${error.message}")
                    }
                })
            }
            beganAt = SystemClock.elapsedRealtime()
            var previousPosition = onMain { active.currentPosition }
            var lastProgressAt = beganAt
            var lastCheckpointProgressAt = beganAt
            var lastCheckpointTimestamp = 0L
            var lastCheckpointPollAt = 0L
            var expectedGeneration: Long? = null
            var expectedQueueToken: String? = null
            var observedCheckpoints = 0
            var nextReportAt = beganAt
            var maximumProgressGapMs = 0L
            var observedLoops = 0L
            report.put("status", "running")
                .put("sourceDurationMs", onMain { active.duration })
            while (SystemClock.elapsedRealtime() - beganAt < durationSeconds * 1_000) {
                val now = SystemClock.elapsedRealtime()
                val sample = onMain {
                    assertTrue("The media session disconnected", active.isConnected)
                    assertTrue("Playback intent changed during the soak", active.playWhenReady)
                    assertNull(active.playerError)
                    assertEquals(Player.REPEAT_MODE_ONE, active.repeatMode)
                    val position = active.currentPosition
                    val duration = active.duration
                    if (position != previousPosition) {
                        maximumProgressGapMs = maxOf(maximumProgressGapMs, now - lastProgressAt)
                        lastProgressAt = now
                    }
                    if (position < previousPosition && duration > 0 &&
                        previousPosition > duration / 2 && position < duration / 2) observedLoops++
                    previousPosition = position
                    JSONObject().put("elapsedRealtimeMs", now - beganAt)
                        .put("positionMs", position)
                        .put("isPlaying", active.isPlaying)
                        .put("playbackState", active.playbackState)
                        .put("audioUnderrunsServiceLifetime", active.sessionExtras.getLong(PlaybackTestContract.AUDIO_UNDERRUNS))
                        .put("audioSinkErrorsServiceLifetime", active.sessionExtras.getLong(PlaybackTestContract.AUDIO_SINK_ERRORS))
                        .put("outputBufferBytes", active.sessionExtras.getInt(PlaybackTestContract.AUDIO_BUFFER_BYTES))
                }
                assertTrue("Unexpected player errors: $errors", errors.isEmpty())
                assertFalse("Playback entered IDLE", states.contains(Player.STATE_IDLE))
                assertFalse("Playback entered ENDED", states.contains(Player.STATE_ENDED))
                assertTrue("No observed position progress for 30 s", now - lastProgressAt < 30_000)
                val checkpointPollInterval = if (observedCheckpoints < 2) 1_000 else 5_000
                if (now - lastCheckpointPollAt >= checkpointPollInterval) {
                    if (expectedQueueToken == null) {
                        val queue = readPublishedJson("playback_queue.json")
                        if (queue?.optJSONArray("items")?.optJSONObject(0)?.optString("mediaId") == testMediaId) {
                            expectedGeneration = queue.optLong("generation")
                            expectedQueueToken = queue.optString("queueToken").takeIf { it.isNotEmpty() }
                        }
                    }
                    val checkpoint = runCatching {
                        readPublishedJson("playback_checkpoint.json")?.takeIf {
                            expectedQueueToken != null && it.optString("queueToken") == expectedQueueToken &&
                                it.optLong("generation") == expectedGeneration && it.optInt("index", -1) == 0 &&
                                it.optBoolean("desiredPlaying")
                        }
                    }.getOrNull()
                    val timestamp = checkpoint?.optLong("savedAtMs") ?: 0L
                    if (timestamp != 0L && timestamp != lastCheckpointTimestamp) {
                        lastCheckpointTimestamp = timestamp
                        lastCheckpointProgressAt = now
                        observedCheckpoints++
                    }
                    sample.put("checkpointPositionMs", checkpoint?.optLong("positionMs") ?: JSONObject.NULL)
                        .put("checkpointSavedAtMs", timestamp)
                    lastCheckpointPollAt = now
                }
                // Controller position can be extrapolated locally. Require the
                // remote service's durable heartbeat as an independent signal.
                assertTrue("The durable checkpoint stopped advancing for 60 s",
                    now - lastCheckpointProgressAt < 60_000)
                if (now >= nextReportAt) {
                    samples.put(sample)
                    report.put("elapsedRealtimeMs", now - beganAt)
                        .put("observedLoops", observedLoops)
                        .put("maximumObservedProgressGapMs", maximumProgressGapMs)
                        .put("observedCheckpoints", observedCheckpoints)
                    writeReport(evidence, report)
                    nextReportAt = now + 30_000
                }
                Thread.sleep(minOf(1_000, (durationSeconds * 1_000 - (now - beganAt)).coerceAtLeast(1)))
            }
            assertTrue("Fewer than two distinct checkpoints belonging to this playback session were observed",
                observedCheckpoints >= 2)
            // These are the latest published counters, not an IPC barrier. A busy
            // application looper may deliver recent output events after this sample.
            val finalDiagnostics = onMain { active.sessionExtras }
            report.put("status", "passed")
                .put("observedLoops", observedLoops)
                .put("maximumObservedProgressGapMs", maximumProgressGapMs)
                .put("observedCheckpoints", observedCheckpoints)
                .put("audioUnderrunsAtEnd", finalDiagnostics.getLong(PlaybackTestContract.AUDIO_UNDERRUNS))
                .put("audioSinkErrorsAtEnd", finalDiagnostics.getLong(PlaybackTestContract.AUDIO_SINK_ERRORS))
                .put("outputBufferBytes", finalDiagnostics.getInt(PlaybackTestContract.AUDIO_BUFFER_BYTES))
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            if (beganAt > 0) report.put("elapsedRealtimeMs", SystemClock.elapsedRealtime() - beganAt)
            report.put("finishedAtUnixMs", System.currentTimeMillis())
                .put("playerErrors", JSONArray(errors))
                .put("playbackStates", JSONArray(states))
            try {
                writeReport(evidence, report)
            } finally {
                onMain {
                    controller?.let { it.pause(); it.clearMediaItems() }
                    MediaController.releaseFuture(future)
                }
            }
        }
    }

    private fun readPublishedJson(name: String): JSONObject? = runCatching {
        // Read only the published file: AtomicFile.openRead() can recover/delete
        // staging files and must not race the remote service's active writer.
        JSONObject(File(context.noBackupFilesDir, name).readText())
    }.getOrNull()

    private fun writeReport(file: AtomicFile, report: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(report.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        throw AssertionError("Playback condition not reached within 15 s")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private companion object {
        const val TAG = "LongPlaybackSoakTest"
    }
}
