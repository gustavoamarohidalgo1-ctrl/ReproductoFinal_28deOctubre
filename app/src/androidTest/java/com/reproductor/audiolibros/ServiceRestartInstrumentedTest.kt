package com.reproductor.audiolibros

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real process death, not stopService(), a new Activity, or a mocked persistence round-trip. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceRestartInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private lateinit var sample: File
    private lateinit var mediaId: String

    @Before
    fun startWithAnIndependentPlaybackProcess() {
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        sample = File(context.filesDir, "continuity-process-restart-320kbps.mp3")
        instrumentation.context.assets.open("silence-320kbps.mp3").use { input ->
            sample.outputStream().use(input::copyTo)
        }
        mediaId = "continuity-process-restart-${System.nanoTime()}"
        // Each test needs a fresh resumed Activity. Reusing the stopped task left
        // behind by HOME can race the preceding test's lifecycle cleanup.
        context.startActivity(Intent().setComponent(PlaybackTestContract.activity(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { PlaybackTestContract.isMainActivity(it) }
        }
        connectFreshController()
        onMain { requireNotNull(controller).pause() }
        assertSafePlaybackPid(requireNotNull(playbackProcess()).pid)
    }

    @After
    fun releaseWithoutLeavingTestPlaybackRunning() {
        // A failed restart assertion can leave a disconnected controller. Bind a
        // fresh one for cleanup; never leave a successful sticky retry playing.
        if (controller == null || !onMain { requireNotNull(controller).isConnected }) {
            connectFreshController()
        }
        onMain {
            controller?.let { it.pause(); it.clearMediaItems() }
            future?.let { MediaController.releaseFuture(it) }
            future = null
            controller = null
        }
        awaitWorker { isDurablyEmpty() }
        // Retain the small MP3 fixture; an interrupted cleanup must not leave a
        // durable snapshot pointing at an already deleted source.
    }

    @Test
    fun systemStickyRestartRecoversPlayingTrackWithoutAPlayCommand() {
        startSample()
        sendActivityHomeAndWaitForStop()
        val checkpoint = awaitCheckpoint(playing = true, minimumPositionMs = 1_500)
        val previousPid = killPlaybackProcess()

        // Do not bind/relaunch the UI to cause this restart. The foreground sticky
        // service must acquire a new process before the test reconnects a controller.
        awaitWorker {
            playbackProcess()?.let { it.pid != previousPid && it.uid == Process.myUid() } == true
        }
        connectFreshController()
        val active = requireNotNull(controller)
        assertNotEquals(previousPid, requireNotNull(playbackProcess()).pid)
        awaitMain { active.currentMediaItem?.mediaId == mediaId && active.isPlaying }
        val restoredPosition = onMain {
            assertTrue(active.playWhenReady)
            assertEquals(Player.REPEAT_MODE_ONE, active.repeatMode)
            assertFalse(active.shuffleModeEnabled)
            assertNull(active.playerError)
            active.currentPosition
        }
        assertTrue("Expected a restored bookmark near ${checkpoint.positionMs} ms; got $restoredPosition ms",
            restoredPosition >= checkpoint.positionMs - 250 && restoredPosition <= checkpoint.positionMs + 5_000)
        awaitMain {
            active.isPlaying && (active.currentPosition > restoredPosition + 250 ||
                active.currentPosition < restoredPosition)
        }
        Log.i(TAG, "Sticky restart $previousPid -> ${requireNotNull(playbackProcess()).pid}; " +
            "checkpoint=${checkpoint.positionMs}, restored=$restoredPosition")
    }

    @Test
    fun pausedBookmarkRemainsPausedAfterRealProcessDeath() {
        startSample()
        onMain {
            requireNotNull(controller).pause()
            requireNotNull(controller).seekTo(5_000)
        }
        val checkpoint = awaitCheckpoint(playing = false, minimumPositionMs = 4_900)
        assertTrue(abs(checkpoint.positionMs - 5_000) <= 100)
        sendActivityHomeAndWaitForStop()
        val previousPid = killPlaybackProcess()
        connectFreshController()
        val active = requireNotNull(controller)
        assertNotEquals(previousPid, requireNotNull(playbackProcess()).pid)
        awaitMain { active.currentMediaItem?.mediaId == mediaId }
        Thread.sleep(2_500)
        onMain {
            assertFalse(active.playWhenReady)
            assertFalse(active.isPlaying)
            assertTrue(abs(active.currentPosition - checkpoint.positionMs) <= 100)
            assertEquals(Player.REPEAT_MODE_ONE, active.repeatMode)
        }
    }

    @Test
    fun clearedQueueDoesNotResurrectAudioAfterRealProcessDeath() {
        startSample()
        awaitCheckpoint(playing = true, minimumPositionMs = 1_500)
        onMain {
            requireNotNull(controller).pause()
            requireNotNull(controller).clearMediaItems()
        }
        awaitWorker { isDurablyEmpty() }
        sendActivityHomeAndWaitForStop()
        val previousPid = killPlaybackProcess()
        connectFreshController()
        val active = requireNotNull(controller)
        assertNotEquals(previousPid, requireNotNull(playbackProcess()).pid)
        Thread.sleep(2_500)
        onMain {
            assertEquals(0, active.mediaItemCount)
            assertNull(active.currentMediaItem)
            assertFalse(active.playWhenReady)
            assertFalse(active.isPlaying)
        }
        assertTrue(isDurablyEmpty())
    }

    private fun startSample() {
        val active = requireNotNull(controller)
        onMain {
            active.setMediaItem(MediaItem.Builder().setMediaId(mediaId)
                .setUri(Uri.fromFile(sample)).build(), 0)
            active.prepare()
            active.play()
        }
        awaitMain { active.isPlaying && active.currentPosition >= 1_500 }
    }

    private fun connectFreshController() {
        val next = onMain {
            future?.let { MediaController.releaseFuture(it) }
            MediaController.Builder(context,
                SessionToken(context, PlaybackTestContract.service(context))).buildAsync()
        }
        future = next
        val active = next.get(30, TimeUnit.SECONDS)
        controller = active
        awaitMain { active.isConnected && active.sessionExtras.getBoolean(PlaybackTestContract.STATE_RESTORED) }
    }

    private fun sendActivityHomeAndWaitForStop() {
        val activity = onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .first { PlaybackTestContract.isMainActivity(it) }
        }
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitMain {
            ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)
                .let { it == Stage.STOPPED || it == Stage.DESTROYED }
        }
    }

    private fun playbackProcess(): ActivityManager.RunningAppProcessInfo? =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == "${context.packageName}:playback" && it.uid == Process.myUid() }

    private fun assertSafePlaybackPid(pid: Int) {
        assertTrue("Only terminate a live app-owned playback process", pid > 0)
        assertNotEquals("Never terminate the instrumentation/UI process", Process.myPid(), pid)
        val process = requireNotNull(playbackProcess())
        assertEquals(pid, process.pid)
        assertEquals(Process.myUid(), process.uid)
        assertEquals("${context.packageName}:playback", process.processName)
    }

    private fun killPlaybackProcess(): Int {
        val pid = requireNotNull(playbackProcess()).pid
        assertSafePlaybackPid(pid)
        Process.killProcess(pid)
        onMain {
            future?.let { MediaController.releaseFuture(it) }
            future = null
            controller = null
        }
        awaitWorker { playbackProcess()?.pid != pid }
        return pid
    }

    private data class DurableCheckpoint(val positionMs: Long)

    private fun awaitCheckpoint(playing: Boolean, minimumPositionMs: Long): DurableCheckpoint {
        var result: DurableCheckpoint? = null
        awaitWorker {
            result = runCatching {
                val queue = JSONObject(File(context.noBackupFilesDir, "playback_queue.json").readText())
                val checkpoint = JSONObject(File(context.noBackupFilesDir, "playback_checkpoint.json").readText())
                val entry = queue.getJSONArray("items").getJSONObject(0)
                if (entry.getString("mediaId") != mediaId ||
                    queue.getString("queueToken") != checkpoint.getString("queueToken") ||
                    queue.getLong("generation") != checkpoint.getLong("generation") ||
                    checkpoint.getBoolean("desiredPlaying") != playing ||
                    checkpoint.getLong("positionMs") < minimumPositionMs
                ) null else DurableCheckpoint(checkpoint.getLong("positionMs"))
            }.getOrNull()
            result != null
        }
        return requireNotNull(result)
    }

    private fun isDurablyEmpty(): Boolean = runCatching {
        JSONObject(File(context.noBackupFilesDir, "playback_queue.json").readText()).getBoolean("cleared")
    }.getOrDefault(false)

    private fun awaitMain(condition: () -> Boolean) = awaitWorker { onMain(condition) }

    private fun awaitWorker(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Process restart condition not reached within 30 s")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private companion object {
        const val TAG = "ServiceRestartTest"
    }
}
