package com.reproductor.audiolibros

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Notificación de servicio sin acciones ni MediaStyle que genere controles externos. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackNotificationProvider(private val context: Context) : MediaNotification.Provider {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playback_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        return MediaNotification(NOTIFICATION_ID, buildNotification(mediaSession))
    }

    fun buildNotification(mediaSession: MediaSession, status: String? = null): Notification {
        val player = mediaSession.player
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(player.mediaMetadata.title ?: context.getString(R.string.app_name))
            .setContentText(status ?: context.getString(
                if (player.playWhenReady) R.string.playback_notification_playing
                else R.string.playback_notification_paused
            ))
            .setContentIntent(mediaSession.sessionActivity)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .build()
    }

    fun buildBootRecoveryNotification(openApp: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.playback_recovering))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true).setSilent(true).setShowWhen(false).setOngoing(true)
            .build()

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean = false

    companion object {
        private const val CHANNEL_ID = "continuous_playback"
        const val NOTIFICATION_ID = 1001
    }
}
