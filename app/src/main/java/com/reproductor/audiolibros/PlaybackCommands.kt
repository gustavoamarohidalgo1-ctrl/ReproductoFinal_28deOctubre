package com.reproductor.audiolibros

import android.os.Bundle
import androidx.media3.common.MediaItem

/** Identifies a library queue so subsequent selections use native, ordered seek commands. */
internal object PlaybackCommands {
    const val EXTRA_QUEUE_REVISION = "com.reproductor.audiolibros.QUEUE_REVISION"
    fun attachQueueRevision(items: List<MediaItem>, revision: String): List<MediaItem> {
        if (items.isEmpty()) return items
        val first = items.first()
        val extras = Bundle(first.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(EXTRA_QUEUE_REVISION, revision)
        }
        return items.toMutableList().apply {
            this[0] = first.buildUpon().setMediaMetadata(
                first.mediaMetadata.buildUpon().setExtras(extras).build()
            ).build()
        }
    }

}
