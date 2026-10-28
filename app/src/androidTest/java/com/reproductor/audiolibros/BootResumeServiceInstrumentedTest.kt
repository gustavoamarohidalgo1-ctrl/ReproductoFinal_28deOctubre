package com.reproductor.audiolibros

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BootResumeServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val command = SessionCommand("com.reproductor.audiolibros.SET_BOOT_RESUME_ENABLED", Bundle.EMPTY)
    private val extra = "com.reproductor.audiolibros.BOOT_RESUME_ENABLED"

    @Test fun settingIsDurableAndBootRequestDoesNotResumeCurrentBoot() {
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        val sample = File(context.filesDir, "boot-resume-test.mp3")
        instrumentation.context.assets.open("silence-320kbps.mp3").use { input ->
            sample.outputStream().use(input::copyTo)
        }
        context.startActivity(Intent().setComponent(PlaybackTestContract.activity(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val future = onMain { MediaController.Builder(context,
            SessionToken(context, PlaybackTestContract.service(context))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            await { controller.sessionExtras.getBoolean(PlaybackTestContract.STATE_RESTORED) }
            fun setEnabled(value: Boolean): SessionResult {
                val result = onMain { controller.sendCustomCommand(command, Bundle().apply {
                    putBoolean("enabled", value)
                }) }.get(15, TimeUnit.SECONDS)
                return result
            }
            assertEquals(SessionResult.RESULT_SUCCESS, setEnabled(true).resultCode)
            await { controller.sessionExtras.getBoolean(extra) }
            await {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { PlaybackTestContract.isMainActivity(it) }
            }
            val activity = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .first { PlaybackTestContract.isMainActivity(it) }
            }
            val switchId = context.resources.getIdentifier("bootResumeSwitch", "id", context.packageName)
            val helpId = context.resources.getIdentifier("bootResumeHelp", "id", context.packageName)
            val titleId = context.resources.getIdentifier("nowPlayingTitle", "id", context.packageName)
            val restoringId = context.resources.getIdentifier("restoring_playback", "string", context.packageName)
            assertTrue(switchId != 0 && helpId != 0 && titleId != 0 && restoringId != 0)
            await { activity.findViewById<View>(switchId)?.visibility == View.GONE &&
                activity.findViewById<View>(helpId)?.visibility == View.GONE }
            onMain { activity.recreate() }
            await {
                val recreated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it !== activity && PlaybackTestContract.isMainActivity(it) }
                recreated != null && recreated.findViewById<View>(switchId)?.visibility == View.GONE &&
                    recreated.findViewById<View>(helpId)?.visibility == View.GONE &&
                    recreated.findViewById<TextView>(titleId)?.text?.toString() != context.getString(restoringId)
            }
            val settings = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "boot_resume.json")
            assertTrue(JSONObject(settings.readText()).getBoolean("enabled"))
            assertEquals(SessionError.ERROR_NOT_SUPPORTED, setEnabled(false).resultCode)
            assertTrue(JSONObject(settings.readText()).getBoolean("enabled"))
            assertTrue(onMain { controller.sessionExtras.getBoolean(extra) })
            onMain {
                controller.setMediaItem(MediaItem.Builder().setMediaId("boot-resume-fixture")
                    .setUri(Uri.fromFile(sample)).build())
                controller.prepare()
                controller.play()
            }
            await { controller.isPlaying }
            val checkpoint = File(context.noBackupFilesDir, "playback_checkpoint.json")
            await(20_000) {
                runCatching { JSONObject(checkpoint.readText()).getInt("musicVolume") == volume }.getOrDefault(false)
            }
            onMain { controller.pause() }
            await { !controller.playWhenReady }
            repeat(2) {
                context.startService(Intent().setComponent(PlaybackTestContract.service(context))
                    .setAction("com.reproductor.audiolibros.RESUME_AFTER_BOOT"))
            }
            Thread.sleep(1_000)
            assertFalse(onMain { controller.playWhenReady })
        } finally {
            onMain { controller.pause(); controller.clearMediaItems(); MediaController.releaseFuture(future) }
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != volume)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }

    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        fail("Condition not reached after $timeoutMs ms")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
