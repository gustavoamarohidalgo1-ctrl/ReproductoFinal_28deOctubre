package com.reproductor.audiolibros

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Xml
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Opt-in observation of durable files; sampling is not an exact write counter. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LoopPersistenceLoadInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun compareShortLoopAndLongAudio() {
        assumeTrue("Set loopPersistenceProbe=true to run the dedicated 24-second probe",
            InstrumentationRegistry.getArguments().getString("loopPersistenceProbe") == "true")
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        val report = JSONObject().put("measurement", "polling lower bound, not exact write counts")
            .put("pollIntervalMs", 50).put("durationPerCaseMs", 12_000)
            .put("serviceApplicationThreadLoadInjected", false)
            .put("physicalAudioMeasured", false).put("status", "starting")
        val cases = JSONArray()
        report.put("cases", cases)
        val outputDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "validation")
        check(outputDir.exists() || outputDir.mkdirs())
        val output = File(outputDir, "loop-persistence-load.json")
        val short = File(context.filesDir, "loop-persistence-200ms.wav")
        val long = File(context.filesDir, "loop-persistence-12s.mp3")
        writeWav(short)
        instrumentation.context.assets.open("silence-320kbps.mp3").use { input -> long.outputStream().use(input::copyTo) }
        context.startActivity(Intent().setComponent(PlaybackTestContract.activity(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val future = onMain { MediaController.Builder(context, SessionToken(context, PlaybackTestContract.service(context))).buildAsync() }
        var controller: MediaController? = null
        try {
            val active = future.get(15, TimeUnit.SECONDS)
            controller = active
            await { active.sessionExtras.getBoolean(PlaybackTestContract.STATE_RESTORED) }
            for ((label, file) in listOf("short-wav-200ms" to short, "long-mp3-12s" to long)) {
                cases.put(measure(active, label, file))
                output.writeText(report.toString(2))
            }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            try { output.writeText(report.toString(2)) } finally {
                onMain { controller?.let { it.pause(); it.clearMediaItems() }; MediaController.releaseFuture(future) }
            }
        }
    }

    private fun measure(controller: MediaController, label: String, file: File): JSONObject {
        val transitions = AtomicInteger()
        val errors = AtomicInteger()
        val mediaId = "loop-probe-$label-${UUID.randomUUID()}"
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) transitions.incrementAndGet()
            }
            override fun onPlayerError(error: PlaybackException) { errors.incrementAndGet() }
        }
        onMain {
            controller.pause()
            controller.addListener(listener)
            controller.setMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(Uri.fromFile(file)).build())
            controller.prepare()
            controller.play()
        }
        try {
            await { controller.isPlaying }
            val queue = File(context.noBackupFilesDir, "playback_queue.json")
            val checkpoint = File(context.noBackupFilesDir, "playback_checkpoint.json")
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var generation: Long? = null
            var token: String? = null
            var baseline: String? = null
            while (SystemClock.elapsedRealtime() < deadline && baseline == null) {
                val document = runCatching { JSONObject(queue.readText()) }.getOrNull()
                if (document?.optJSONArray("items")?.optJSONObject(0)?.optString("mediaId") == mediaId) {
                    val candidateToken = document.optString("queueToken").takeIf { it.isNotEmpty() }
                    val candidateGeneration = document.optLong("generation", -1)
                    val current = runCatching { JSONObject(checkpoint.readText()) }.getOrNull()
                    if (candidateToken != null && candidateGeneration > 0 && current != null &&
                        current.optString("queueToken") == candidateToken &&
                        current.optLong("generation", -1) == candidateGeneration &&
                        current.optInt("index", -1) == 0 && current.optBoolean("desiredPlaying") &&
                        current.optLong("savedAtMs", 0) > 0) {
                        generation = candidateGeneration
                        token = candidateToken
                        baseline = "$candidateToken:${current.optLong("savedAtMs")}"
                    }
                }
                if (baseline == null) Thread.sleep(50)
            }
            check(baseline != null) { "No matching playing checkpoint for $label within 15 seconds" }
            transitions.set(0)
            errors.set(0)
            val start = SystemClock.elapsedRealtime()
            val initial = onMain { controller.sessionExtras.getLong(PlaybackTestContract.AUDIO_UNDERRUNS) to
                controller.sessionExtras.getLong(PlaybackTestContract.AUDIO_SINK_ERRORS) }
            val preferences = File(context.applicationInfo.dataDir, "shared_prefs/reproductor_prefs.xml")
            var lastCheckpoint: String? = baseline
            var lastPreferences: Pair<Long, Long>? = null
            var checkpointChanges = 0
            var preferenceChanges = 0
            var polls = 0
            while (SystemClock.elapsedRealtime() - start < 12_000) {
                // Read only the published base file; AtomicFile.openRead() could interfere with a writer.
                val identity = runCatching { JSONObject(checkpoint.readText()) }.getOrNull()?.let {
                    if (it.optString("queueToken") == token && it.optLong("generation", -1) == generation &&
                        it.optInt("index", -1) == 0 && it.optBoolean("desiredPlaying") &&
                        it.optLong("savedAtMs", 0) > 0) "$token:${it.optLong("savedAtMs")}" else null
                }
                if (identity != null && identity != lastCheckpoint) {
                    if (lastCheckpoint != null) checkpointChanges++
                    lastCheckpoint = identity
                }
                val stamp = if (preferences.isFile) preferences.lastModified() to preferences.length() else null
                if (stamp != null && stamp != lastPreferences) {
                    if (lastPreferences != null) preferenceChanges++
                    lastPreferences = stamp
                }
                polls++
                Thread.sleep(50)
            }
            val final = onMain { controller.sessionExtras.getLong(PlaybackTestContract.AUDIO_UNDERRUNS) to
                controller.sessionExtras.getLong(PlaybackTestContract.AUDIO_SINK_ERRORS) }
            val duration = onMain { controller.duration }
            check(duration > 0) { "Unknown duration for $label" }
            onMain { controller.pause() }
            val pauseDeadline = SystemClock.elapsedRealtime() + 15_000
            var pausedPosition: Long? = null
            var bookmarkPosition: Long? = null
            while (SystemClock.elapsedRealtime() < pauseDeadline) {
                val saved = runCatching { JSONObject(checkpoint.readText()) }.getOrNull()
                val matching = saved != null && saved.optString("queueToken") == token &&
                    saved.optLong("generation", -1) == generation && saved.optInt("index", -1) == 0 &&
                    saved.has("desiredPlaying") && !saved.optBoolean("desiredPlaying", true)
                val position = if (matching) saved!!.optLong("positionMs", -1) else -1
                val bookmark = readBookmark(preferences, mediaId)
                if (position in 0..duration && bookmark == position) {
                    pausedPosition = position
                    bookmarkPosition = bookmark
                    break
                }
                Thread.sleep(50)
            }
            check(pausedPosition != null) { "No matching paused checkpoint and bookmark for $label" }
            return JSONObject().put("case", label).put("elapsedMs", SystemClock.elapsedRealtime() - start)
                .put("autoTransitionsObserved", transitions.get())
                .put("autoTransitionsMeaning", "remote controller events, not exact loop count")
                .put("checkpointChangesObserved", checkpointChanges)
                .put("preferenceFileChangesObserved", preferenceChanges).put("polls", polls)
                .put("pausedCheckpointPositionMs", pausedPosition).put("pausedBookmarkPositionMs", bookmarkPosition)
                .put("sourceDurationMs", duration)
                .put("underrunsObservedDelta", final.first - initial.first)
                .put("sinkErrorsObservedDelta", final.second - initial.second).put("playerErrors", errors.get())
                .put("playbackState", onMain { controller.playbackState })
                .put("isPlaying", onMain { controller.isPlaying })
        } finally { onMain { controller.removeListener(listener); controller.pause() } }
    }

    private fun readBookmark(file: File, mediaId: String): Long? = runCatching {
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, null) }
            // A missing key is the store's documented zero default for this unique ID.
            var value: Long? = 0L
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "long" &&
                    parser.getAttributeValue(null, "name") == "pos_$mediaId") {
                    value = parser.getAttributeValue(null, "value")?.toLongOrNull()
                    break
                }
            }
            value
        }
    }.getOrNull()

    private fun writeWav(file: File) {
        val dataSize = 48_000 * 2 / 5 // 200 ms, mono PCM16
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataSize)
        FileOutputStream(file).use { it.write(header.array()); it.write(ByteArray(dataSize)) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        throw AssertionError("Playback condition not reached")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
