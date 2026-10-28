package com.reproductor.audiolibros

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/** Motor independiente de la interfaz, con persistencia y recuperación de fallos. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var positionStore: PositionStore
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var bootSettings: BootResumeSettingsStore
    private lateinit var notifications: PlaybackNotificationProvider
    private lateinit var recoveryWakeLock: PowerManager.WakeLock
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recovery = PlaybackRecoveryPolicy()
    private val progressWatchdog = PlaybackProgressWatchdog()

    private var desiredPlaying = false
    private var foreground = false
    private var started = false
    private var destroyed = false
    private var restoring = false
    private var snapshotLoaded = false
    private var userInteracted = false
    private var systemRestartRequested = false
    private var initialized = false
    private var waitingForUnlock = false
    private var bootResumeEnabled = false
    private var bootResumeRequested = false
    private var bootResumeHandled = false
    private val pendingSettingsResults = mutableSetOf<SettableFuture<SessionResult>>()
    private data class RestoredPlaybackIntent(
        val desiredPlaying: Boolean, val bootCount: Int, val musicVolume: Int?
    )
    private var restoredPlaybackIntent: RestoredPlaybackIntent? = null
    private var generation = System.currentTimeMillis()
    private var queueDirty = true
    private var capturedQueue: List<MediaItem>? = null
    private var queueRevision: String? = null
    private var stateRestoredPublished = false
    private var lastSubmittedEmptyGeneration: Long? = null
    private var urgentPersistenceRequest = 0L
    private var lastEmptyWriteRequest = 0L
    private var lastCheckpointWriteRequest = 0L
    private var lastCheckpointSubmittedAtMs: Long? = null
    private var lastAutomaticRepeatTarget: Pair<Long, Int>? = null
    private val persistenceRetry = PlaybackPersistenceRetryPolicy()
    private val stateWriter = LatestTaskExecutor(disk) { error ->
        Log.e(TAG, "No se pudo guardar el estado de reproducción", error)
        handler.post {
            if (!destroyed) {
                lastSubmittedCheckpoint = null
                lastSubmittedEmptyGeneration = null
            }
        }
    }
    private var bootCount = -1
    private var pendingRecovery: Runnable? = null
    private var recoveryOriginIndex = C.INDEX_UNSET
    private var recoveryOriginPosition = C.TIME_UNSET
    private var waitingForFiles = false
    private var recoveryEpoch = 0L
    private var consecutiveStalls = 0
    private var lastSubmittedCheckpoint: PlaybackStateStore.Checkpoint? = null
    private var diagnosticsPublishPending = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED &&
                getSystemService(UserManager::class.java).isUserUnlocked
            ) initializePlayback()
        }
    }
    private val unlockTimeout = Runnable {
        if (!initialized) {
            Log.w(TAG, "Se canceló la recuperación: el almacenamiento sigue bloqueado")
            bootResumeRequested = false
            stopSelf()
        }
    }
    private val publishDiagnostics = Runnable {
        diagnosticsPublishPending = false
        if (!destroyed) publishSessionState()
    }
    private val audioOutputDiagnostics = AudioOutputDiagnostics {
        // Publish only after output events, coalescing bursts instead of adding
        // polling or disk work to steady playback. Diagnostics never restart audio.
        if (!destroyed && !diagnosticsPublishPending) {
            diagnosticsPublishPending = true
            handler.postDelayed(publishDiagnostics, 1_000L)
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (controller.uid != Process.myUid() || controller.packageName != packageName) {
                return MediaSession.ConnectionResult.reject()
            }
            val commands = if (session.isMediaNotificationController(controller)) {
                Player.Commands.Builder().addAll(
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA
                ).build()
            } else {
                // This app deliberately repeats the selected track at its original
                // speed, pitch and digital gain. These are not user-selectable modes.
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SET_REPEAT_MODE)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                    .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
                    .remove(Player.COMMAND_SET_VOLUME)
                    .build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(if (session.isMediaNotificationController(controller)) {
                    SessionCommands.EMPTY
                } else SessionCommands.Builder().add(
                    SessionCommand(BootResumeCommands.ACTION_SET_ENABLED, Bundle.EMPTY)
                ).build())
                .setAvailablePlayerCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != BootResumeCommands.ACTION_SET_ENABLED) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            if (!snapshotLoaded || destroyed) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
            }
            @Suppress("DEPRECATION")
            val enabled = args.get(BootResumeCommands.ARG_ENABLED) as? Boolean
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            if (!enabled) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val result = SettableFuture.create<SessionResult>()
            pendingSettingsResults.add(result)
            disk.execute {
                val saved = bootSettings.enable()
                handler.post {
                    if (!destroyed && saved) {
                        bootResumeEnabled = true
                        saveCheckpoint(force = true, urgent = true)
                        publishSessionState()
                    }
                    result.set(SessionResult(when {
                        destroyed -> SessionError.ERROR_SESSION_DISCONNECTED
                        saved -> SessionResult.RESULT_SUCCESS
                        else -> SessionError.ERROR_IO
                    }))
                    pendingSettingsResults.remove(result)
                }
            }
            return result
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean = true

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(emptyList())

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            userInteracted = true
            cancelRecovery()
            recovery.reset()
            consecutiveStalls = 0
            saveCheckpoint(force = true, urgent = true)
            val index = if (mediaItems.isEmpty()) C.INDEX_UNSET
                else startIndex.coerceIn(0, mediaItems.lastIndex)
            val position = if (startPositionMs == C.TIME_UNSET && index >= 0) {
                positionStore.getPosition(mediaItems[index].mediaId)
            } else startPositionMs
            // The selected audiobook is the entire playback session. Normalize
            // older controllers' library queues before Media3 can play them.
            val selectedItems = if (index >= 0) listOf(mediaItems[index]) else emptyList()
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    selectedItems, if (selectedItems.isEmpty()) C.INDEX_UNSET else 0, position
                )
            )
        }

        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommands: Player.Commands
        ) {
            val navigation = playerCommands.containsAny(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD
            )
            if (navigation) {
                userInteracted = true
                // Every explicit navigation supersedes the target captured by
                // a delayed retry, including commands that change the track index.
                cancelRecovery()
                recovery.reset()
                consecutiveStalls = 0
                resetProgressWatch()
                if (desiredPlaying && player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                    player.play()
                }
            }
            if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) ||
                playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                navigation
            ) {
                userInteracted = true
                saveCheckpoint(force = true, urgent = true)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onRepeatModeChanged(repeatMode: Int) {
            if (!destroyed && repeatMode != Player.REPEAT_MODE_ONE) {
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!destroyed && shuffleModeEnabled) player.shuffleModeEnabled = false
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!destroyed && playbackParameters != PlaybackParameters.DEFAULT) {
                player.playbackParameters = PlaybackParameters.DEFAULT
            }
        }

        override fun onVolumeChanged(volume: Float) {
            // Hardware/system volume still works; this only fixes the app's PCM gain.
            if (!destroyed && volume != 1f) player.volume = 1f
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (restoring || destroyed) return
            lastAutomaticRepeatTarget = null
            desiredPlaying = playWhenReady
            if (!playWhenReady) {
                cancelRecovery()
                recovery.reset()
                consecutiveStalls = 0
            }
            resetProgressWatch()
            saveCheckpoint(force = true, urgent = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            updateForeground()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (restoring || destroyed) return
            // BUFFERING/READY oscillation is not evidence that audio is advancing.
            if (playbackState == Player.STATE_ENDED && desiredPlaying &&
                player.mediaItemCount > 0 && pendingRecovery == null
            ) {
                // Normal REPEAT_ONE loops do not enter ENDED. Repeated unexpected
                // endings must use the bounded retry policy, not a tight prepare loop.
                scheduleRecovery(permanent = false, restartFromBeginning = true)
            }
            updateForeground()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (restoring || destroyed) return
            if (isPlaying) {
                // READY/isPlaying alone is not proof that an output recovered. Require
                // progress before cancelling a watchdog retry, otherwise state flapping
                // could keep cancelling every attempt without ever producing audio.
                if (hasAdvancedSinceRecovery()) {
                    cancelRecovery()
                } else if (pendingRecovery == null) {
                    releaseRecoveryWakeLock()
                }
                updateForeground()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (restoring || destroyed) return
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                generation = nextGeneration(generation)
                lastAutomaticRepeatTarget = null
                queueDirty = true
                queueRevision = if (player.mediaItemCount > 0) {
                    player.getMediaItemAt(0).mediaMetadata.extras
                        ?.getString(PlaybackCommands.EXTRA_QUEUE_REVISION)
                } else null
                publishSessionState()
                recovery.reset()
                cancelRecovery()
                resetProgressWatch()
            }
            saveCheckpoint(force = true)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (restoring || destroyed) return
            val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            val oldItem = oldPosition.mediaItem
            val newItem = newPosition.mediaItem
            // A repeat of this same item needs one current bookmark, not an
            // intermediate zero followed by a second forced preferences write.
            val sameAutomaticRepeat = finished && snapshotLoaded && oldItem != null && newItem != null &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex && oldItem.mediaId == newItem.mediaId &&
                player.currentMediaItemIndex == newPosition.mediaItemIndex && player.currentMediaItem?.mediaId == newItem.mediaId
            if (!sameAutomaticRepeat) lastAutomaticRepeatTarget = null
            oldItem?.let {
                if (!sameAutomaticRepeat) {
                    positionStore.savePosition(
                        it.mediaId, if (finished) 0L else oldPosition.positionMs, force = true
                    )
                }
            }
            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    if (player.isPlaying) {
                        cancelRecovery()
                        progressWatchdog.onAutoTransition(
                            SystemClock.elapsedRealtime(), newPosition.mediaItemIndex, newPosition.positionMs
                        )
                    } else resetProgressWatch()
                }
                Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_REMOVE -> resetProgressWatch()
            }
            val target = generation to newPosition.mediaItemIndex
            val previous = lastSubmittedCheckpoint
            val now = SystemClock.elapsedRealtime()
            val submittedAt = lastCheckpointSubmittedAtMs
            // Very short clips can wrap many times between heartbeats. Keep the
            // first wrap immediate, then share the normal two-second checkpoint
            // window. Manual actions and other tracks never use this gate. A
            // failed request clears lastSubmittedCheckpoint so it can be retried.
            val coalesce = sameAutomaticRepeat && lastAutomaticRepeatTarget == target && !queueDirty &&
                previous != null && previous.generation == generation && previous.index == target.second &&
                previous.desiredPlaying == desiredPlaying && submittedAt != null &&
                now >= submittedAt && now - submittedAt < CHECKPOINT_INTERVAL_MS
            if (sameAutomaticRepeat) lastAutomaticRepeatTarget = target
            if (!coalesce) saveCheckpoint(force = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (restoring || destroyed || !desiredPlaying) return
            val permanent = error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            )
            Log.w(TAG, "Fallo de audio: ${error.errorCodeName}")
            scheduleRecovery(permanent, rebuildPlayer = requiresNewAudioOutput(error))
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            saveCheckpoint()
            checkProgress()
            handler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bootSettings = BootResumeSettingsStore(this)
        notifications = PlaybackNotificationProvider(this)
        bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        recoveryWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Reproductor:Recovery")
            .apply { setReferenceCounted(false) }

        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            initializePlayback()
        } else {
            // On a phone without a PIN, credential storage may unlock just after
            // LOCKED_BOOT_COMPLETED. Prepare recovery without reading CE storage.
            waitingForUnlock = true
            ContextCompat.registerReceiver(this, unlockReceiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            handler.postDelayed(unlockTimeout, UNLOCK_WAIT_MS)
            // Close the race between the first check and receiver registration.
            if (getSystemService(UserManager::class.java).isUserUnlocked) initializePlayback()
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun initializePlayback() {
        if (destroyed || initialized) return
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        val completingBootPreparation = waitingForUnlock && bootResumeRequested && started
        if (waitingForUnlock) {
            unregisterReceiver(unlockReceiver)
            waitingForUnlock = false
        }
        handler.removeCallbacks(unlockTimeout)
        positionStore = PositionStore(this)
        stateStore = PlaybackStateStore(this)

        player = createPlayer()
        player.addListener(playerListener)

        setMediaNotificationProvider(notifications)
        mediaSession = MediaSession.Builder(this, sessionPlayer(player))
            .setCallback(sessionCallback).setSessionActivity(openAppIntent()).build()
        // El reinicio sticky puede suceder sin ningún controlador de interfaz.
        addSession(mediaSession!!)
        initialized = true
        if (completingBootPreparation) {
            // The short, pre-unlock preparation is non-sticky. Once storage is
            // available, record the usual sticky start so later process death
            // retains the app's existing service recovery behavior.
            try {
                startService(Intent(this, PlaybackService::class.java).setAction(ACTION_RESUME_AFTER_BOOT))
            } catch (error: RuntimeException) {
                Log.w(TAG, "No se pudo actualizar el inicio del servicio tras desbloquear", error)
            }
        }
        disk.execute {
            val enabled = bootSettings.readEnabled()
            val snapshot = stateStore.read()
            val stoppedByUser = snapshot != null && wasStoppedByUser(snapshot.savedAtMs)
            handler.post {
                if (destroyed) return@post
                bootResumeEnabled = enabled
                snapshotLoaded = true
                if (!userInteracted && snapshot != null) {
                    // The player now owns the restored queue. Retain only the intent
                    // needed by a later sticky start, not thousands of old MediaItems
                    // after the user subsequently replaces or clears that queue.
                    restoredPlaybackIntent = RestoredPlaybackIntent(
                        snapshot.desiredPlaying && !stoppedByUser, snapshot.bootCount, snapshot.musicVolume
                    )
                    generation = nextGeneration(maxOf(generation, snapshot.generation))
                    queueDirty = true
                    restoring = true
                    // Migrate previous versions' library queues to their selected
                    // audiobook without losing its position or playback intent.
                    player.setMediaItems(listOf(snapshot.queue[snapshot.index]), 0, snapshot.positionMs)
                    restoring = false
                    resetProgressWatch()
                    resumeAfterSystemRestart()
                } else {
                    saveCheckpoint(force = true)
                    updateForeground()
                }
                // Publicar después de que Media3 haya despachado los cambios de la cola.
                handler.post {
                    if (!destroyed) {
                        stateRestoredPublished = true
                        publishSessionState()
                    }
                }
            }
        }
        resetProgressWatch()
        handler.post(heartbeat)
    }

    private fun publishSessionState() {
        mediaSession?.setSessionExtras(Bundle().apply {
            putBoolean(EXTRA_STATE_RESTORED, stateRestoredPublished)
            putBoolean(BootResumeCommands.EXTRA_ENABLED, bootResumeEnabled)
            queueRevision?.let { putString(PlaybackCommands.EXTRA_QUEUE_REVISION, it) }
            audioOutputDiagnostics.appendTo(this)
        })
    }

    private fun sessionPlayer(delegate: ExoPlayer): Player = object : ForwardingPlayer(delegate) {
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            userInteracted = true
            cancelRecovery()
            recovery.reset()
            consecutiveStalls = 0
            saveCheckpoint(force = true, urgent = true)
            // Native player commands retain Media3's ordering and immediate UI masking.
            // The app uses TIME_UNSET for selection: resume the target's bookmark,
            // while explicit seeks (including 0) retain their exact meaning.
            val targetPosition = if (positionMs == C.TIME_UNSET &&
                mediaItemIndex in 0 until delegate.mediaItemCount
            ) positionStore.getPosition(delegate.getMediaItemAt(mediaItemIndex).mediaId)
            else positionMs
            super.seekTo(mediaItemIndex, targetPosition)
        }

        override fun stop() {
            userInteracted = true
            desiredPlaying = false
            cancelRecovery()
            recovery.reset()
            consecutiveStalls = 0
            resetProgressWatch()
            // ExoPlayer.stop() preserves playWhenReady. Apply the user's stopped
            // intent here, in command order: a later PLAY must still take effect.
            // onPlayerInteractionFinished receives an unordered set of commands.
            super.pause()
            super.stop()
            saveCheckpoint(force = true, urgent = true)
            updateForeground()
        }
    }

    private fun createPlayer(): ExoPlayer =
        ExoPlayer.Builder(
            this,
            ContinuousAudioRenderersFactory(this)
                .setEnableAudioFloatOutput(true)
                .setEnableDecoderFallback(true)
        )
            // Buffer original encoded samples; no transcoding, bitrate cap or audio effects.
            // 120 seconds of 320 kbps audio is about 4.8 MB. Keep the default byte
            // threshold as well. It is a soft buffering target, not a total RAM cap.
            // Local sources can be read again on a backward seek. Retaining ten
            // seconds behind playback could consume the whole byte target for
            // high-rate PCM, leaving little forward data before Media3 discards
            // the back buffer. Reserve those allocations for upcoming audio.
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, 120_000, 2_500, 5_000)
                .setBackBuffer(0, false)
                .build())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .apply {
                // Media3 already owns an audio-priority playback thread. Keep its
                // scheduling and decoder defaults; reserve more decoded PCM at the sink.
                addAnalyticsListener(audioOutputDiagnostics)
                repeatMode = Player.REPEAT_MODE_ONE
                shuffleModeEnabled = false
                pauseAtEndOfMediaItems = false
                playbackParameters = PlaybackParameters.DEFAULT
                volume = 1f
                skipSilenceEnabled = false
            }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        if (intent?.action == ACTION_RESUME_AFTER_BOOT && !bootResumeHandled) {
            bootResumeRequested = true
        }
        if (!initialized) {
            if (bootResumeRequested) {
                try {
                    ServiceCompat.startForeground(this, PlaybackNotificationProvider.NOTIFICATION_ID,
                        notifications.buildBootRecoveryNotification(openAppIntent()),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    foreground = true
                    recoveryWakeLock.acquire(RECOVERY_WAKE_TIMEOUT_MS)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Android impidió preparar la recuperación al encender", error)
                    bootResumeRequested = false
                    stopSelf()
                }
            } else stopSelf()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            systemRestartRequested = true
            if (snapshotLoaded) {
                resumeAfterSystemRestart()
            } else {
                // A large recovery queue can take time to read. Promote immediately
                // after a system restart instead of waiting for disk I/O to finish.
                recoveryWakeLock.acquire(RECOVERY_WAKE_TIMEOUT_MS)
                updateForeground()
            }
        } else if (bootResumeRequested && snapshotLoaded) {
            resumeAfterSystemRestart()
        } else if (bootResumeRequested) {
            recoveryWakeLock.acquire(RECOVERY_WAKE_TIMEOUT_MS)
            updateForeground()
        } else if (snapshotLoaded) {
            updateForeground()
        }
        return START_STICKY
    }

    private fun resumeAfterSystemRestart() {
        val intent = restoredPlaybackIntent
        val resumeBoot = intent != null && BootResumePolicy.canResume(
            bootResumeEnabled, bootResumeRequested && !bootResumeHandled, userInteracted,
            intent.desiredPlaying, intent.bootCount, bootCount, player.mediaItemCount > 0
        )
        val resumeSticky = systemRestartRequested && !userInteracted && intent != null &&
            intent.desiredPlaying && bootCount >= 0 && intent.bootCount == bootCount
        if (bootResumeRequested) {
            bootResumeHandled = true
            bootResumeRequested = false
        }
        if (resumeBoot || resumeSticky) {
            desiredPlaying = true
            updateForeground()
            // A rejected foreground promotion must not be followed by play().
            if (!desiredPlaying || !foreground) return
            if (resumeBoot) restoreMusicVolume(intent?.musicVolume)
            player.prepare()
            player.play()
            saveCheckpoint(force = true, urgent = true)
            Log.i(TAG, if (resumeBoot) "Reproducción recuperada automáticamente al encender"
                else "Reproducción recuperada tras reinicio del servicio")
        } else {
            updateForeground()
        }
    }

    private fun currentMusicVolume(): Int? = try {
        getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
            .takeIf { it >= 0 }
    } catch (error: RuntimeException) {
        Log.w(TAG, "No se pudo consultar el volumen multimedia", error)
        null
    }

    private fun restoreMusicVolume(savedVolume: Int?) {
        if (savedVolume == null) return
        try {
            val audio = getSystemService(AudioManager::class.java)
            if (audio.isVolumeFixed) return
            // This option is for the phone's speakers. Avoid raising an unexpected
            // wired/Bluetooth/USB output to the level saved for the built-in speaker.
            val outputs = if (Build.VERSION.SDK_INT >= 33) {
                audio.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            } else audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val externalOutput = outputs.isEmpty() || outputs.any {
                it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
            }
            if (externalOutput) {
                Log.i(TAG, "Se conserva el volumen actual: hay una salida de audio externa")
                return
            }
            val min = if (Build.VERSION.SDK_INT >= 28) {
                audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else 0
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume.coerceIn(min, max), 0)
            Log.i(TAG, "Volumen multimedia recuperado: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        } catch (error: RuntimeException) {
            // Playback recovery is still useful if a device refuses volume control.
            Log.w(TAG, "Android no permitió recuperar el volumen multimedia", error)
        }
    }

    private fun wasStoppedByUser(savedAtMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 0)
                .firstOrNull { it.processName == "$packageName:playback" && it.timestamp >= savedAtMs }
                ?.reason == ApplicationExitInfo.REASON_USER_REQUESTED
        } catch (error: RuntimeException) {
            Log.w(TAG, "No se pudo consultar el cierre anterior", error)
            true
        }
    }

    private fun saveCheckpoint(force: Boolean = false, urgent: Boolean = false) {
        if (destroyed || restoring || !snapshotLoaded) return
        if (urgent) urgentPersistenceRequest = nextGeneration(urgentPersistenceRequest)
        val request = urgentPersistenceRequest
        if (player.mediaItemCount == 0) {
            capturedQueue = null
            lastSubmittedCheckpoint = null
            queueDirty = false
            if (lastSubmittedEmptyGeneration == generation && lastEmptyWriteRequest == request) return
            val emptyGeneration = generation
            lastSubmittedEmptyGeneration = emptyGeneration
            lastEmptyWriteRequest = request
            // Use the same serialized writer as checkpoints: an older in-flight write
            // must finish before the durable clear, never resurrect the previous queue.
            submitPersistence(
                PlaybackPersistenceRetryPolicy.State(emptyGeneration, C.INDEX_UNSET, false), request,
                retry = {
                    if (lastSubmittedEmptyGeneration == emptyGeneration && lastEmptyWriteRequest == request) {
                        lastSubmittedEmptyGeneration = null
                    }
                }
            ) { stateStore.clear() }
            return
        }
        lastSubmittedEmptyGeneration = null
        if (!force && !desiredPlaying && !queueDirty && lastSubmittedCheckpoint != null) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0)
        player.currentMediaItem?.let { positionStore.savePosition(it.mediaId, position, force = force) }
        val checkpoint = PlaybackStateStore.Checkpoint(
            generation, index, position, desiredPlaying, System.currentTimeMillis(), bootCount,
            musicVolume = if (bootResumeEnabled) currentMusicVolume() else null
        )
        val previous = lastSubmittedCheckpoint
        if (!queueDirty && previous != null && previous.generation == generation &&
            previous.index == index && previous.positionMs == position &&
            previous.desiredPlaying == desiredPlaying && previous.musicVolume == checkpoint.musicVolume &&
            lastCheckpointWriteRequest == request
        ) return
        // Every submitted checkpoint owns a complete, immutable queue reference.
        // Replacing a pending write therefore cannot lose an earlier queue dependency.
        val queue = if (queueDirty || capturedQueue == null) {
            List(player.mediaItemCount) { player.getMediaItemAt(it) }.also { capturedQueue = it }
        } else capturedQueue!!
        lastSubmittedCheckpoint = checkpoint
        lastCheckpointSubmittedAtMs = SystemClock.elapsedRealtime()
        lastCheckpointWriteRequest = request
        queueDirty = false
        submitPersistence(
            PlaybackPersistenceRetryPolicy.State(generation, index, desiredPlaying), request,
            retry = {
                // A newer request can already have superseded this failed/skipped write.
                if (lastSubmittedCheckpoint === checkpoint) lastSubmittedCheckpoint = null
            }
        ) {
            (!stateStore.needsQueue(checkpoint.generation, checkpoint.index) ||
                stateStore.writeQueue(queue, checkpoint)) && stateStore.writeCheckpoint(checkpoint)
        }
    }

    private fun submitPersistence(
        state: PlaybackPersistenceRetryPolicy.State,
        urgentRequest: Long,
        retry: () -> Unit,
        persist: () -> Boolean
    ) {
        stateWriter.execute {
            fun retryLater() { handler.post { if (!destroyed) retry() } }
            // Failures must not repeatedly serialize a huge queue on a full/unavailable
            // disk. Healthy writes keep their 2 s cadence; explicit user changes and
            // final shutdown can always attempt an immediate commit.
            if (!persistenceRetry.shouldAttempt(state, SystemClock.elapsedRealtime(), urgentRequest)) {
                retryLater()
                return@execute
            }
            // The priority survives replacement of a pending task by a newer periodic
            // snapshot, but is consumed once so a failed urgent write cannot defeat backoff.
            persistenceRetry.attempted(urgentRequest)
            var saved = false
            try {
                saved = persist()
            } finally {
                if (saved) persistenceRetry.succeeded() else {
                    persistenceRetry.failed(state, SystemClock.elapsedRealtime())
                    retryLater()
                }
            }
        }
    }

    private fun scheduleRecovery(
        permanent: Boolean,
        rebuildPlayer: Boolean = false,
        restartFromBeginning: Boolean = false
    ) {
        if (!desiredPlaying || player.mediaItemCount == 0 || destroyed) return
        cancelRecovery()
        val plan = recovery.onFailure(player.currentMediaItemIndex, player.mediaItemCount, permanent)
        val position = if (restartFromBeginning || plan.restartFromBeginning) 0L
            else player.currentPosition.coerceAtLeast(0)
        val epoch = recoveryEpoch
        val queueGeneration = generation
        recoveryOriginIndex = player.currentMediaItemIndex
        recoveryOriginPosition = player.currentPosition.coerceAtLeast(0)
        waitingForFiles = plan.waitingForFiles
        recoveryWakeLock.acquire(RECOVERY_WAKE_TIMEOUT_MS)
        pendingRecovery = Runnable {
            if (epoch != recoveryEpoch || queueGeneration != generation) return@Runnable
            if (hasAdvancedSinceRecovery()) {
                cancelRecovery()
                updateForeground()
                return@Runnable
            }
            pendingRecovery = null
            if (destroyed || !desiredPlaying || player.mediaItemCount == 0) return@Runnable
            waitingForFiles = false
            val index = plan.index.coerceIn(0, player.mediaItemCount - 1)
            try {
                if (rebuildPlayer) {
                    rebuildPlayerForRecovery(index, position)
                } else {
                    // prepare() in READY does not recreate a stalled audio output.
                    if (player.playbackState != Player.STATE_IDLE) player.stop()
                    player.seekTo(index, position)
                }
                player.prepare()
                player.play()
            } catch (error: RuntimeException) {
                Log.e(TAG, "No se pudo recuperar el motor de audio", error)
                scheduleRecovery(permanent = false, rebuildPlayer = true)
            }
            resetProgressWatch()
            updateForeground()
        }.also { handler.postDelayed(it, plan.delayMs) }
        Log.i(TAG, "Recuperación: pista=${plan.index}, espera=${plan.delayMs} ms")
        saveCheckpoint(force = true)
        updateForeground()
    }

    private fun cancelRecovery() {
        recoveryEpoch++
        pendingRecovery?.let(handler::removeCallbacks)
        pendingRecovery = null
        recoveryOriginIndex = C.INDEX_UNSET
        recoveryOriginPosition = C.TIME_UNSET
        waitingForFiles = false
        releaseRecoveryWakeLock()
    }

    private fun releaseRecoveryWakeLock() {
        if (::recoveryWakeLock.isInitialized && recoveryWakeLock.isHeld) recoveryWakeLock.release()
    }

    private fun resetProgressWatch() {
        progressWatchdog.reset(SystemClock.elapsedRealtime(), player.currentMediaItemIndex, player.currentPosition)
    }

    private fun checkProgress() {
        if (hasAdvancedSinceRecovery()) {
            cancelRecovery()
            updateForeground()
        }
        when (progressWatchdog.observe(
            SystemClock.elapsedRealtime(), player.currentMediaItemIndex, player.currentPosition,
            player.isPlaying, desiredPlaying && player.mediaItemCount > 0, pendingRecovery != null
        )) {
            PlaybackProgressWatchdog.Decision.STALLED -> {
                consecutiveStalls++
                Log.w(TAG, "La reproducción no avanza; recuperación $consecutiveStalls")
                // A second stall needs fresh renderers/AudioTrack, not another prepare()
                // on the same engine. The session token and UI connection remain alive.
                scheduleRecovery(permanent = false, rebuildPlayer = consecutiveStalls >= 2)
            }
            PlaybackProgressWatchdog.Decision.STABLE -> {
                recovery.reset()
                consecutiveStalls = 0
            }
            PlaybackProgressWatchdog.Decision.NONE -> Unit
        }
    }

    private fun hasAdvancedSinceRecovery(): Boolean = pendingRecovery != null && player.isPlaying &&
        player.currentMediaItemIndex == recoveryOriginIndex && recoveryOriginPosition >= 0 &&
        player.currentPosition - recoveryOriginPosition >= 100L

    private fun requiresNewAudioOutput(error: PlaybackException): Boolean = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_TIMEOUT
    )

    private fun rebuildPlayerForRecovery(index: Int, position: Long) {
        val previous = player
        val queue = List(previous.mediaItemCount) { previous.getMediaItemAt(it) }
        val replacement = createPlayer()
        restoring = true
        try {
            replacement.setMediaItems(queue, index, position)
            // createPlayer() reapplies repeat and original-sound settings after
            // recovery instead of inheriting an accidentally modified setting.
            previous.removeListener(playerListener)
            previous.removeAnalyticsListener(audioOutputDiagnostics)
            player = replacement
            replacement.addListener(playerListener)
            mediaSession?.setPlayer(sessionPlayer(replacement))
        } catch (error: RuntimeException) {
            replacement.removeListener(playerListener)
            replacement.removeAnalyticsListener(audioOutputDiagnostics)
            replacement.release()
            player = previous
            previous.addListener(playerListener)
            previous.addAnalyticsListener(audioOutputDiagnostics)
            throw error
        } finally {
            restoring = false
        }
        previous.release()
        Log.i(TAG, "Motor de audio reconstruido conservando archivo, posición y calidad")
    }

    // Mantener foreground también en STATE_IDLE mientras se espera un reintento.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        updateForeground()
    }

    private fun updateForeground() {
        if (destroyed || restoring || !::player.isInitialized) return
        val session = mediaSession ?: return
        val restoringRestart = isRestoringSystemRestart()
        if (restoringRestart || (desiredPlaying && player.mediaItemCount > 0)) {
            try {
                if (!started) {
                    ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
                    started = true
                }
                val status = when {
                    restoringRestart -> getString(R.string.playback_recovering)
                    waitingForFiles -> getString(R.string.playback_waiting_for_files)
                    pendingRecovery != null -> getString(R.string.playback_recovering)
                    else -> null
                }
                ServiceCompat.startForeground(
                    this, PlaybackNotificationProvider.NOTIFICATION_ID,
                    notifications.buildNotification(session, status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                foreground = true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Android no permitió mantener el servicio de audio", error)
                desiredPlaying = false
                player.pause()
                saveCheckpoint(force = true)
            }
        } else {
            releaseRecoveryWakeLock()
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            if (started && snapshotLoaded) {
                stopSelf()
                started = false
            }
        }
    }

    private fun isRestoringSystemRestart(): Boolean =
        (systemRestartRequested || bootResumeRequested) && !snapshotLoaded && !userInteracted

    override fun isPlaybackOngoing(): Boolean = foreground && (desiredPlaying || isRestoringSystemRestart())

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCheckpoint(force = true, urgent = true)
        if (isPlaybackOngoing()) updateForeground() else stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        saveCheckpoint(force = true, urgent = true)
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        pendingSettingsResults.forEach {
            it.set(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
        }
        pendingSettingsResults.clear()
        if (waitingForUnlock) {
            unregisterReceiver(unlockReceiver)
            waitingForUnlock = false
        }
        cancelRecovery()
        if (initialized) {
            player.removeListener(playerListener)
            player.removeAnalyticsListener(audioOutputDiagnostics)
        }
        mediaSession?.release()
        if (initialized) player.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_STATE_RESTORED = "com.reproductor.audiolibros.STATE_RESTORED"
        internal const val ACTION_RESUME_AFTER_BOOT = "com.reproductor.audiolibros.RESUME_AFTER_BOOT"
        // Una nueva instancia espera las escrituras de la anterior en el mismo proceso.
        internal val disk = Executors.newSingleThreadExecutor()
        private fun nextGeneration(previous: Long): Long =
            if (previous == Long.MAX_VALUE) 1L else previous + 1L
        private const val TAG = "PlaybackService"
        private const val CHECKPOINT_INTERVAL_MS = 2_000L
        private const val RECOVERY_WAKE_TIMEOUT_MS = 60_000L
        private const val UNLOCK_WAIT_MS = 30_000L
    }
}
