package com.reproductor.audiolibros

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two-phase upgrade probe; use only an emulator with disposable app data. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class QueueUpgradeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val expected get() = File(context.filesDir, "queue-upgrade-expected.json")

    @Test fun migratePausedSecondItem() {
        val phase = InstrumentationRegistry.getArguments().getString("queueUpgradePhase")
        assumeTrue("Set queueUpgradePhase to prepare-legacy or verify-upgrade", phase != null)
        require(phase == "prepare-legacy" || phase == "verify-upgrade")
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        context.startActivity(Intent().setComponent(PlaybackTestContract.activity(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val future = onMain { MediaController.Builder(context,
            SessionToken(context, PlaybackTestContract.service(context))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            await { onMain { controller.sessionExtras.getBoolean(PlaybackTestContract.STATE_RESTORED) } }
            if (phase == "prepare-legacy") prepare(controller) else verify(controller)
        } finally {
            onMain {
                if (phase == "verify-upgrade") { controller.pause(); controller.clearMediaItems() }
                MediaController.releaseFuture(future)
            }
            if (phase == "verify-upgrade") expected.delete()
        }
    }

    private fun prepare(controller: MediaController) {
        val file = File(context.filesDir, "queue-upgrade-fixture.mp3")
        instrumentation.context.assets.open("silence-320kbps.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val prefix = "queue-upgrade-${UUID.randomUUID()}"
        val first = "$prefix-a"
        val second = "$prefix-b"
        val items = listOf(first, second).map { MediaItem.Builder().setMediaId(it).setUri(Uri.fromFile(file)).build() }
        onMain {
            controller.pause()
            controller.setMediaItems(items, 1, 8_000L)
            controller.prepare()
        }
        await { onMain { controller.mediaItemCount == 2 && controller.currentMediaItemIndex == 1 &&
            controller.currentMediaItem?.mediaId == second && !controller.playWhenReady && controller.currentPosition in 7_900L..8_100L } }
        await { matchingDiskState(first, second, 2, 1, 8_000L) }
        expected.writeText(JSONObject().put("first", first).put("second", second).put("positionMs", 8_000L).toString())
    }

    private fun verify(controller: MediaController) {
        val saved = JSONObject(expected.readText())
        val first = saved.getString("first")
        val second = saved.getString("second")
        val position = saved.getLong("positionMs")
        await { onMain { controller.mediaItemCount == 1 && controller.currentMediaItemIndex == 0 &&
            controller.currentMediaItem?.mediaId == second && !controller.playWhenReady &&
            controller.currentPosition in (position - 100)..(position + 100) } }
        await { matchingDiskState(first, second, 1, 0, position) }
    }

    private fun matchingDiskState(first: String, second: String, count: Int, index: Int, position: Long): Boolean = runCatching {
        val queue = JSONObject(File(context.noBackupFilesDir, "playback_queue.json").readText())
        val checkpoint = JSONObject(File(context.noBackupFilesDir, "playback_checkpoint.json").readText())
        val items = queue.getJSONArray("items")
        val token = queue.getString("queueToken")
        items.length() == count && (count != 2 || items.getJSONObject(0).getString("mediaId") == first) &&
            items.getJSONObject(index).getString("mediaId") == second && token.isNotEmpty() &&
            checkpoint.getString("queueToken") == token && checkpoint.getLong("generation") == queue.getLong("generation") &&
            checkpoint.getInt("index") == index && !checkpoint.getBoolean("desiredPlaying") &&
            checkpoint.getLong("positionMs") in (position - 100)..(position + 100)
    }.getOrDefault(false)

    private fun await(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Migration condition not reached within $timeoutMs ms")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
