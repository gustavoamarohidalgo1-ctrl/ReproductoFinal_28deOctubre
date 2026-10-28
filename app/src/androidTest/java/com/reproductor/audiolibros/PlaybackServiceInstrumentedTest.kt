package com.reproductor.audiolibros

import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real remote service and decoders, rather than a mocked Player. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var controller: MediaController
    private lateinit var future: ListenableFuture<MediaController>
    private lateinit var sample: File

    @Before
    fun connectToForegroundActivityAndService() {
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        sample = File(context.filesDir, "continuity-test-320kbps.mp3")
        instrumentation.context.assets.open("silence-320kbps.mp3").use { input ->
            sample.outputStream().use(input::copyTo)
        }
        context.startActivity(Intent().setComponent(PlaybackTestContract.activity(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        future = onMain {
            MediaController.Builder(context, SessionToken(context, PlaybackTestContract.service(context)))
                .buildAsync()
        }
        controller = future.get(15, TimeUnit.SECONDS)
        await { controller.sessionExtras.getBoolean(PlaybackTestContract.STATE_RESTORED) }
        onMain { controller.pause() }
    }

    @After
    fun release() {
        if (::controller.isInitialized) onMain {
            controller.pause()
            controller.clearMediaItems()
            MediaController.releaseFuture(future)
        }
        // The generated fixture is deliberately retained in app-private storage so a
        // delayed durable checkpoint cannot refer to an already removed test source.
    }

    @Test
    fun playsOriginal320KbpsFormatWithoutModifyingSource() {
        val original = checksum(sample)
        startSample()
        await { controller.currentPosition > 1_000 }
        onMain {
            val group = controller.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            val format = group.getTrackFormat(0)
            assertEquals("audio/mpeg", format.sampleMimeType)
            // MP3 average bitrate can differ by a few bps due to duration rounding.
            assertEquals(320, ((format.bitrate.toLong() + 500) / 1_000).toInt())
            assertEquals(44_100, format.sampleRate)
            assertEquals(2, format.channelCount)
            assertEquals(1f, controller.playbackParameters.speed)
            assertEquals(1f, controller.playbackParameters.pitch)
            assertEquals(1f, controller.volume)
            assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
            assertFalse(controller.shuffleModeEnabled)
            assertNull(controller.playerError)
        }
        assertArrayEquals(original, checksum(sample))
    }

    @Test
    fun decodedOutputUsesTheLargerBufferThroughTheRealService() {
        startSample()
        await { controller.sessionExtras.getInt(PlaybackTestContract.AUDIO_BUFFER_BYTES) > 0 }
        onMain {
            val extras = controller.sessionExtras
            assertEquals(44_100, extras.getInt(PlaybackTestContract.AUDIO_SAMPLE_RATE))
            assertEquals(AudioFormat.CHANNEL_OUT_STEREO,
                extras.getInt(PlaybackTestContract.AUDIO_CHANNEL_MASK))
            val frameSize = Util.getPcmFrameSize(extras.getInt(PlaybackTestContract.AUDIO_ENCODING), 2)
            assertTrue("The real output must retain at least two seconds of PCM capacity",
                extras.getInt(PlaybackTestContract.AUDIO_BUFFER_BYTES) >= 44_100 * frameSize * 2)
            assertTrue(extras.containsKey(PlaybackTestContract.AUDIO_UNDERRUNS))
            assertTrue(extras.containsKey(PlaybackTestContract.AUDIO_SINK_ERRORS))
            assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
            assertNull(controller.playerError)
        }
    }

    @Test
    fun repeatsThreeTimesWithoutIdleEndedOrPlayerError() {
        val states = CopyOnWriteArrayList<Int>()
        startSample()
        onMain {
            controller.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states.add(playbackState)
                }
            })
        }
        // MediaController suppresses transitions to an equal MediaItem.
        // Observe actual position wraps instead of counting the suppressed callbacks.
        var previousPosition = 0L
        var loops = 0
        await(50_000) {
            assertNull(controller.playerError)
            assertTrue(controller.playWhenReady)
            val position = controller.currentPosition
            val duration = controller.duration
            if (duration > 0 && previousPosition > duration * 3 / 4 && position < duration / 4) loops++
            previousPosition = position
            loops >= 3
        }
        onMain {
            assertTrue(controller.isPlaying)
            assertNull(controller.playerError)
        }
        assertFalse(states.contains(Player.STATE_IDLE))
        assertFalse(states.contains(Player.STATE_ENDED))
    }

    @Test
    fun seekWhilePausedKeepsPositionAndDoesNotResume() {
        startSample()
        onMain { controller.pause(); controller.seekTo(8_000) }
        await { !controller.playWhenReady && controller.currentPosition in 7_900..8_100 }
        Thread.sleep(2_500)
        onMain {
            assertFalse(controller.playWhenReady)
            assertTrue(controller.currentPosition in 7_900..8_100)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun checkpointWriteFailuresKeepPlayingAndExplicitSeekBypassesRetryDelay() {
        // Android 11+ AtomicFile stages writes in .new. A nonempty directory
        // produces a real open failure and cannot be removed by failWrite(null).
        val checkpointFile = File(context.noBackupFilesDir, "playback_checkpoint.json")
        val blockedStaging = File(context.noBackupFilesDir, "playback_checkpoint.json.new")
        val blocker = File(blockedStaging, "write-failure-test")
        startSample()
        val pauseRequestedAt = System.currentTimeMillis()
        onMain { controller.pause() }
        var durablePause: JSONObject? = null
        val pauseDeadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < pauseDeadline && durablePause == null) {
            // Plain reads observe only the completed file; a second AtomicFile
            // reader could alter the live service's staging/backup files.
            durablePause = runCatching { JSONObject(checkpointFile.readText()) }
                .getOrNull()?.takeIf {
                    !it.getBoolean("desiredPlaying") &&
                        it.getLong("savedAtMs") >= pauseRequestedAt &&
                        !blockedStaging.exists()
                }
            if (durablePause == null) Thread.sleep(50)
        }
        val paused = checkNotNull(durablePause) { "A completed paused checkpoint is required" }
        val pausedFileContents = checkpointFile.readText()
        val queueToken = paused.getString("queueToken")
        val generation = paused.getLong("generation")
        val index = onMain { controller.currentMediaItemIndex }
        val queueIds = onMain {
            (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
        }

        try {
            assertTrue("Create the AtomicFile staging blocker", blockedStaging.mkdir())
            blocker.writeText("Keep the directory nonempty while writes fail")
            onMain { controller.play() }
            await { controller.isPlaying }
            val failureDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < failureDeadline) {
                onMain {
                    assertTrue("Disk errors must not interrupt audio", controller.isPlaying)
                    assertTrue(controller.playWhenReady)
                    assertNull(controller.playerError)
                    assertEquals(index, controller.currentMediaItemIndex)
                }
                Thread.sleep(100)
            }
            assertTrue("The real write obstruction must remain active", blocker.isFile)
            assertEquals("Failed writes must preserve the last completed checkpoint",
                pausedFileContents, checkpointFile.readText())

            assertTrue("Remove the obstruction's content", blocker.delete())
            assertTrue("Restore AtomicFile staging", blockedStaging.delete())
            val seekRequestedAt = System.currentTimeMillis()
            val seekDeadline = SystemClock.elapsedRealtime() + 4_000
            // Keep queue, index and playing intent unchanged: only the user's
            // explicit seek can make this urgent during the failed-write cooldown.
            onMain { controller.seekTo(5_000) }
            var recoveredCheckpoint: JSONObject? = null
            while (SystemClock.elapsedRealtime() < seekDeadline && recoveredCheckpoint == null) {
                recoveredCheckpoint = runCatching { JSONObject(checkpointFile.readText()) }
                    .getOrNull()?.takeIf {
                        it.getBoolean("desiredPlaying") &&
                            it.getLong("savedAtMs") >= seekRequestedAt &&
                            it.getLong("positionMs") in 4_900L..6_500L &&
                            it.getInt("index") == index &&
                            it.getLong("generation") == generation &&
                            it.getString("queueToken") == queueToken
                    }
                if (recoveredCheckpoint == null) Thread.sleep(50)
            }
            assertNotNull("An explicit seek must save before the retry cooldown expires",
                recoveredCheckpoint)
            onMain {
                assertTrue(controller.isPlaying)
                assertTrue(controller.playWhenReady)
                assertNull(controller.playerError)
                assertEquals(index, controller.currentMediaItemIndex)
                assertEquals(queueIds, (0 until controller.mediaItemCount)
                    .map { controller.getMediaItemAt(it).mediaId })
            }
        } finally {
            // Never delete a normal .new file belonging to a successful retry.
            if (blockedStaging.isDirectory) {
                blocker.delete()
                blockedStaging.delete()
            }
        }
    }

    @Test
    fun controllerCannotDisableLoopOrAlterOriginalPlaybackParameters() {
        startSample()
        val original = checksum(sample)
        val positionBefore = onMain {
            for (command in listOf(
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_VOLUME
            )) {
                assertFalse("Playback policy must not expose command $command", controller.isCommandAvailable(command))
            }
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            // Unsupported commands must be harmless even if an app controller calls
            // them accidentally. Android's device volume remains independent.
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.shuffleModeEnabled = true
            controller.setPlaybackSpeed(1.5f)
            controller.playbackParameters = PlaybackParameters(0.8f, 1.25f)
            controller.volume = 0.5f
            assertOriginalPlaybackPolicyAndFormat()
            controller.currentPosition
        }
        await { controller.currentPosition > positionBefore + 500 }
        onMain { assertOriginalPlaybackPolicyAndFormat(); assertTrue(controller.isPlaying) }
        assertArrayEquals(original, checksum(sample))
    }

    @Test
    fun subsecondLoopKeepsSelectedTrackBeyondWatchdogDeadline() {
        val selectedId = "continuity-test-short-loop-320kbps"
        val states = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<PlaybackException>()
        val selected = sampleItem().buildUpon()
            .setMediaId(selectedId)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(2_000)
                .setEndPositionMs(2_800)
                .build())
            .build()
        onMain {
            controller.setMediaItems(listOf(sampleItem(), selected), 1, 0)
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying && controller.mediaItemCount == 1 && controller.currentMediaItem?.mediaId == selectedId }
        var previousPosition = onMain {
            assertEquals(1, controller.mediaItemCount)
            assertTrue(controller.duration in 750..850)
            controller.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states.add(playbackState)
                }
                override fun onPlayerError(error: PlaybackException) {
                    errors.add(error)
                }
            })
            controller.currentPosition
        }
        var loops = 0
        val deadline = SystemClock.elapsedRealtime() + 26_000
        // Actual 800 ms loops exercise transitions more frequently than the 2 s
        // heartbeat, for longer than its 20 s stall timeout. The service retains
        // only the selected item from the original list.
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain {
                assertEquals(selectedId, controller.currentMediaItem?.mediaId)
                assertEquals(0, controller.currentMediaItemIndex)
                assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
                assertFalse(controller.shuffleModeEnabled)
                assertTrue(controller.playWhenReady)
                assertNull(controller.playerError)
                val position = controller.currentPosition
                val duration = controller.duration
                if (previousPosition > duration * 3 / 4 && position < duration / 4) loops++
                previousPosition = position
            }
            Thread.sleep(50)
        }
        assertTrue("Expected at least 20 natural wraps; observed $loops", loops >= 20)
        assertTrue("Unexpected decoder errors: $errors", errors.isEmpty())
        assertFalse(states.contains(Player.STATE_IDLE))
        assertFalse(states.contains(Player.STATE_ENDED))
        onMain { assertTrue(controller.isPlaying); assertOriginalPlaybackPolicyAndFormat() }
    }

    @Test
    fun addingItemsCannotChangeTheSelectedAudiobook() {
        startSample()
        await { controller.isPlaying && controller.currentPosition > 500 }
        val added = sampleItem().buildUpon().setMediaId("continuity-test-unrequested").build()
        onMain {
            assertTrue("The barrier seek must target a position not yet reached", controller.currentPosition < 5_000)
            controller.addMediaItem(added)
            assertEquals(2, controller.mediaItemCount)
            // A subsequent native seek on the same controller is a command-order
            // barrier; a fresh controller observes the service's actual timeline.
            controller.seekTo(7_000)
        }
        val observerFuture = onMain {
            MediaController.Builder(context, SessionToken(context, PlaybackTestContract.service(context)))
                .buildAsync()
        }
        try {
            val observer = observerFuture.get(15, TimeUnit.SECONDS)
            await {
                observer.currentMediaItem?.mediaId == SAMPLE_ID && observer.isPlaying &&
                    observer.currentPosition in 7_000L..10_000L
            }
            val position = onMain {
                assertEquals(1, observer.mediaItemCount)
                assertTrue(observer.playWhenReady)
                observer.currentPosition
            }
            await {
                assertEquals(1, observer.mediaItemCount)
                assertEquals(SAMPLE_ID, observer.currentMediaItem?.mediaId)
                observer.isPlaying && observer.currentPosition > position + 250
            }
        } finally {
            onMain { MediaController.releaseFuture(observerFuture) }
        }
    }

    @Test
    fun unreadableTrackStaysSelectedUntilUserChoosesAnother() {
        onMain {
            controller.setMediaItems(listOf(missingItem(), sampleItem()), 0, 0)
            controller.prepare()
            controller.play()
        }
        await(15_000) { controller.playerError != null }
        val deadline = SystemClock.elapsedRealtime() + 32_000
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain {
                assertEquals("continuity-test-missing", controller.currentMediaItem?.mediaId)
                assertEquals(0, controller.currentMediaItemIndex)
                assertEquals(1, controller.mediaItemCount)
            }
            Thread.sleep(250)
        }
        onMain { controller.setMediaItem(sampleItem(), 0); controller.prepare(); controller.play() }
        await(20_000) { controller.currentMediaItem?.mediaId == SAMPLE_ID && controller.isPlaying }
        onMain { assertNull(controller.playerError) }
    }

    @Test
    fun explicitPauseCancelsUnavailableFilesRecovery() {
        onMain {
            controller.setMediaItem(missingItem())
            controller.prepare()
            controller.play()
        }
        await(15_000) { controller.playerError != null }
        onMain { controller.pause() }
        // Unavailable-file recovery waits 30 s. A 3 s assertion misses a stale
        // retry that can resume much later, after the listener already saw pause.
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain { assertFalse(controller.playWhenReady); assertFalse(controller.isPlaying) }
            Thread.sleep(250)
        }
    }

    @Test
    fun selectingAnotherTrackDuringRecoveryKeepsTheNewSelection() {
        val selectedId = "continuity-test-user-selected-320kbps"
        onMain {
            controller.setMediaItems(listOf(
                missingItem(), sampleItem(), sampleItem().buildUpon().setMediaId(selectedId).build()
            ), 0, 0)
            controller.prepare()
            controller.play()
        }
        // Replacing the selected file must supersede the retry, even in IDLE.
        await { controller.playerError != null }
        onMain {
            controller.setMediaItem(sampleItem().buildUpon().setMediaId(selectedId).build(), 7_000)
            controller.prepare()
            controller.play()
        }
        await { controller.currentMediaItem?.mediaId == selectedId && controller.isPlaying }
        Thread.sleep(1_000)
        onMain {
            assertEquals(selectedId, controller.currentMediaItem?.mediaId)
            assertEquals(0, controller.currentMediaItemIndex)
            assertEquals(1, controller.mediaItemCount)
            assertTrue(controller.currentPosition >= 7_000)
            assertOriginalPlaybackPolicyAndFormat()
        }
    }

    @Test
    fun explicitStopRemainsStoppedBeyondWatchdogDeadline() {
        startSample()
        onMain { controller.stop() }
        await { controller.playbackState == Player.STATE_IDLE && !controller.playWhenReady }
        Thread.sleep(22_000)
        onMain {
            assertEquals(Player.STATE_IDLE, controller.playbackState)
            assertFalse(controller.playWhenReady)
            assertFalse(controller.isPlaying)
        }
    }

    @Test
    fun stopThenExplicitPlayHonorsLatestCommand() {
        startSample()
        onMain {
            controller.stop()
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying && controller.currentPosition > 500 }
        Thread.sleep(1_000)
        onMain { assertTrue(controller.isPlaying); assertOriginalPlaybackPolicyAndFormat() }
    }

    @Test
    fun prepareAfterStopDoesNotResumeWithoutExplicitPlay() {
        startSample()
        onMain {
            controller.stop()
            controller.prepare()
        }
        await { controller.playbackState == Player.STATE_READY && !controller.playWhenReady }
        Thread.sleep(1_000)
        onMain { assertFalse(controller.isPlaying); assertFalse(controller.playWhenReady) }
    }

    @Test
    fun longLocalAudioSeeksPlaysAndPersistsPositionBeyondTwentyFourHours() {
        val longAudio = File(context.filesDir, "continuity-test-27-hours.wav")
        val longId = "continuity-test-long-${System.nanoTime()}"
        try {
            SparseWaveFixture.create(longAudio, durationSeconds = 27L * 60 * 60)
            val startPositionMs = 25L * 60 * 60 * 1_000 + 12_345
            onMain {
                controller.setMediaItem(MediaItem.Builder()
                    .setMediaId(longId)
                    .setUri(Uri.fromFile(longAudio))
                    .build(), startPositionMs)
                controller.prepare()
                controller.play()
            }
            await { controller.isPlaying && controller.currentPosition > startPositionMs + 500 }
            val pausedPosition = onMain {
                assertEquals(27L * 60 * 60 * 1_000, controller.duration)
                assertNull(controller.playerError)
                assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
                controller.pause()
                controller.currentPosition
            }
            assertTrue(pausedPosition > 24L * 60 * 60 * 1_000)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var durablePause = false
            while (SystemClock.elapsedRealtime() < deadline && !durablePause) {
                // Observe the service's completed file on this worker thread;
                // never run a second AtomicFile reader/writer in the UI process.
                durablePause = runCatching {
                    val checkpoint = JSONObject(File(context.noBackupFilesDir,
                        "playback_checkpoint.json").readText())
                    !checkpoint.getBoolean("desiredPlaying") &&
                        kotlin.math.abs(checkpoint.getLong("positionMs") - pausedPosition) <= 250
                }.getOrDefault(false)
                if (!durablePause) Thread.sleep(50)
            }
            assertTrue("The position beyond 24 h must be durably saved paused", durablePause)

            // Read the real screen through public Android APIs so these assertions
            // also exercise the R8-optimized app from the independent validation APK.
            await {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { PlaybackTestContract.isMainActivity(it) }
            }
            val (timeCurrent, timeTotal) = onMain {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .first { PlaybackTestContract.isMainActivity(it) }
                val currentId = activity.resources.getIdentifier("timeCurrent", "id", context.packageName)
                val totalId = activity.resources.getIdentifier("timeTotal", "id", context.packageName)
                assertNotEquals("Current-time label must exist in the optimized app", 0, currentId)
                assertNotEquals("Duration label must exist in the optimized app", 0, totalId)
                requireNotNull(activity.findViewById<TextView>(currentId)) to
                    requireNotNull(activity.findViewById<TextView>(totalId))
            }
            onMain { controller.seekTo(25L * 60 * 60 * 1_000 + 12_000) }
            await {
                assertFalse("Seeking a long paused file must not resume audio", controller.playWhenReady)
                assertFalse(controller.isPlaying)
                timeCurrent.text.toString() == "25:00:12" && timeTotal.text.toString() == "27:00:00"
            }
            onMain { controller.seekTo(0) }
            await {
                assertFalse(controller.playWhenReady)
                assertFalse(controller.isPlaying)
                timeCurrent.text.toString() == "0:00" && timeTotal.text.toString() == "27:00:00"
            }
            // Boundary test: seek near the real 27 h file's end; this does not
            // represent 24 h of elapsed playback or an endurance test.
            val nearEnd = 27L * 60 * 60 * 1_000 - 2_000
            onMain { controller.seekTo(nearEnd); controller.play() }
            await { controller.isPlaying && controller.currentPosition >= nearEnd }
            await(15_000) {
                assertEquals(longId, controller.currentMediaItem?.mediaId)
                assertEquals(1, controller.mediaItemCount)
                assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
                assertNull(controller.playerError)
                controller.isPlaying && controller.currentPosition in 200L..5_000L
            }
            onMain { controller.pause(); controller.clearMediaItems() }
            await {
                assertFalse(controller.playWhenReady)
                assertFalse(controller.isPlaying)
                controller.mediaItemCount == 0 && timeCurrent.text.toString() == "0:00" &&
                    timeTotal.text.toString() == "0:00"
            }
        } finally {
            onMain { controller.pause(); controller.clearMediaItems() }
            await { controller.mediaItemCount == 0 }
            if (longAudio.exists()) {
                assertTrue("Remove the sparse fixture after playback", longAudio.delete())
            }
        }
    }

    @Test
    fun selectingTracksRestoresEachBookmarkWithOneItemQueue() {
        val revision = "bookmarks-${System.nanoTime()}"
        val items = listOf(
            sampleItem().buildUpon().setMediaId("$revision-a").build(),
            sampleItem().buildUpon().setMediaId("$revision-b").build()
        )
        onMain {
            controller.setMediaItems(listOf(items[1]), 0, 3_000)
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying && controller.currentPosition >= 3_000 }
        onMain { controller.pause() }
        onMain { selectItem(items[0]) }
        await { controller.currentMediaItem?.mediaId == items[0].mediaId && controller.isPlaying }
        onMain { controller.pause(); controller.seekTo(8_000) }
        await { controller.currentPosition in 7_900..8_100 && !controller.playWhenReady }
        onMain { selectItem(items[1]) }
        await { controller.currentMediaItem?.mediaId == items[1].mediaId && controller.isPlaying }
        onMain {
            assertTrue("Track B must resume its bookmark", controller.currentPosition in 2_900..5_000)
            controller.pause()
        }
        onMain { selectItem(items[0]) }
        await { controller.currentMediaItem?.mediaId == items[0].mediaId && controller.isPlaying }
        onMain {
            assertTrue("Track A must resume its bookmark", controller.currentPosition in 7_900..10_000)
            assertEquals(1, controller.mediaItemCount)
        }
    }

    @Test
    fun latestExplicitPauseWinsAfterTrackSelection() {
        val revision = "selection-${System.nanoTime()}"
        val items = listOf(sampleItem(), sampleItem().buildUpon().setMediaId("$revision-b").build())
        onMain {
            controller.setMediaItems(listOf(items[0]), 0, 0)
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying }
        onMain {
            selectItem(items[1])
            controller.pause()
        }
        await { controller.currentMediaItem?.mediaId == items[1].mediaId && !controller.playWhenReady }
        Thread.sleep(2_500)
        onMain {
            assertEquals(items[1].mediaId, controller.currentMediaItem?.mediaId)
            assertFalse(controller.playWhenReady)
            assertFalse(controller.isPlaying)
        }
    }

    @Test
    fun clearingTheQueueCommitsAnEmptyDurableState() {
        startSample()
        onMain { controller.clearMediaItems() }
        await { controller.mediaItemCount == 0 }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var cleared = false
        while (SystemClock.elapsedRealtime() < deadline && !cleared) {
            cleared = runCatching {
                JSONObject(File(context.noBackupFilesDir, "playback_queue.json").readText())
                    .getBoolean("cleared")
            }.getOrDefault(false)
            if (!cleared) Thread.sleep(50)
        }
        assertTrue("An empty queue must not resurrect the previously saved audio", cleared)
    }

    @Test
    fun rapidSelectionOfBThenAUsesTheLatestTrackAndPreservesItsBookmark() {
        val revision = "rapid-${System.nanoTime()}"
        val items = listOf(
            sampleItem().buildUpon().setMediaId("$revision-a").build(),
            sampleItem().buildUpon().setMediaId("$revision-b").build()
        )
        onMain {
            controller.setMediaItems(listOf(items[0]), 0, 6_000)
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying && controller.currentPosition >= 6_000 }
        onMain {
            selectItem(items[1])
            // Native masking must expose B immediately, so tapping A next
            // cannot be mistaken for another tap on the old active item.
            assertEquals(items[1].mediaId, controller.currentMediaItem?.mediaId)
            selectItem(items[0])
            assertEquals(items[0].mediaId, controller.currentMediaItem?.mediaId)
        }
        await { controller.currentMediaItemIndex == 0 && controller.isPlaying && controller.currentPosition >= 6_000 }
        Thread.sleep(500)
        onMain {
            assertEquals(items[0].mediaId, controller.currentMediaItem?.mediaId)
            assertTrue(controller.currentPosition in 6_000..9_000)
            assertEquals(1, controller.mediaItemCount)
        }
    }

    @Test
    fun earlierPauseAndSeekCannotOvertakeTheLatestTrackSelection() {
        val revision = "ordered-${System.nanoTime()}"
        val items = listOf(
            sampleItem().buildUpon().setMediaId("$revision-a").build(),
            sampleItem().buildUpon().setMediaId("$revision-b").build()
        )
        onMain {
            controller.setMediaItems(listOf(items[0]), 0, 0)
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying }
        // Keep all operations in one native command batch. A custom session
        // command would bypass this queue and let this old seek land on B.
        onMain {
            controller.pause()
            controller.seekTo(8_000)
            selectItem(items[1])
        }
        await { controller.currentMediaItem?.mediaId == items[1].mediaId && controller.isPlaying }
        onMain {
            assertTrue("Track B must use its own bookmark", controller.currentPosition < 3_000)
            assertTrue(controller.playWhenReady)
        }
    }

    /** Mirrors selecting a different item in the UI; the service resolves its bookmark. */
    private fun selectItem(item: MediaItem) {
        controller.setMediaItems(listOf(item), 0, C.TIME_UNSET)
        controller.prepare()
        controller.play()
    }

    private fun startSample() {
        onMain {
            controller.setMediaItems(listOf(sampleItem()), 0, 0)
            controller.prepare()
            controller.play()
        }
        await { controller.isPlaying }
    }

    private fun sampleItem() = MediaItem.Builder()
        .setMediaId(SAMPLE_ID)
        .setUri(Uri.fromFile(sample))
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Continuidad 320 kbps").build())
        .build()

    private fun missingItem() = MediaItem.Builder()
        .setMediaId("continuity-test-missing")
        .setUri(Uri.fromFile(File(context.filesDir, "does-not-exist-continuity.mp3")))
        .build()

    private fun assertOriginalPlaybackPolicyAndFormat() {
        assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
        assertFalse(controller.shuffleModeEnabled)
        assertEquals(PlaybackParameters.DEFAULT, controller.playbackParameters)
        assertEquals(1f, controller.volume)
        val group = controller.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        val format = group.getTrackFormat(0)
        assertEquals("audio/mpeg", format.sampleMimeType)
        assertEquals(320, ((format.bitrate.toLong() + 500) / 1_000).toInt())
        assertEquals(44_100, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertNull(controller.playerError)
    }

    private fun checksum(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

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

    private companion object {
        const val SAMPLE_ID = "continuity-test-320kbps"
    }
}
