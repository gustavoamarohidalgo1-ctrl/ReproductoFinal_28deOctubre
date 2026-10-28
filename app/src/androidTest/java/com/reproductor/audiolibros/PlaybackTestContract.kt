package com.reproductor.audiolibros

import android.Manifest
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem

/** Black-box IPC contract; these tests also target the fully optimized APK. */
internal object PlaybackTestContract {
    const val STATE_RESTORED = "com.reproductor.audiolibros.STATE_RESTORED"
    const val EXTRA_QUEUE_REVISION = "com.reproductor.audiolibros.QUEUE_REVISION"
    const val AUDIO_UNDERRUNS = "com.reproductor.audiolibros.AUDIO_UNDERRUNS"
    const val AUDIO_SINK_ERRORS = "com.reproductor.audiolibros.AUDIO_SINK_ERRORS"
    const val AUDIO_BUFFER_BYTES = "com.reproductor.audiolibros.AUDIO_BUFFER_BYTES"
    const val AUDIO_SAMPLE_RATE = "com.reproductor.audiolibros.AUDIO_SAMPLE_RATE"
    const val AUDIO_ENCODING = "com.reproductor.audiolibros.AUDIO_ENCODING"
    const val AUDIO_CHANNEL_MASK = "com.reproductor.audiolibros.AUDIO_CHANNEL_MASK"
    fun activity(context: Context) = ComponentName(context.packageName, "${context.packageName}.MainActivity")
    fun service(context: Context) = ComponentName(context.packageName, "${context.packageName}.PlaybackService")
    fun isMainActivity(activity: Any): Boolean = activity.javaClass.name == "com.reproductor.audiolibros.MainActivity"

    /** Prevent a first-install permission dialog from covering the lifecycle under test. */
    fun grantRuntimePermissions(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        for (permission in permissions) {
            var output = ""
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} $permission"
                )
                // Reading to EOF waits for pm to complete; the stream also owns
                // and closes the returned descriptor on success or failure.
                output = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader().use { it.readText() }
            }
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                "Test setup could not grant $permission to ${context.packageName}: ${output.trim()}"
            }
        }
    }

    fun attachQueueRevision(items: List<MediaItem>, revision: String): List<MediaItem> =
        items.mapIndexed { index, item ->
            if (index != 0) item else item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
                    putString(EXTRA_QUEUE_REVISION, revision)
                }).build()
            ).build()
        }
}
