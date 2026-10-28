package com.reproductor.audiolibros

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import java.util.Collections

data class AudioItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val folder: String,
    val sizeBytes: Long
) {
    val mediaId: String = id.toString()

    /** Bitrate medio del archivo en kbps (tamaño × 8 ÷ duración). -1 si no se puede calcular. */
    val bitrateKbps: Int
        get() = if (durationMs > 0 && sizeBytes > 0) {
            (sizeBytes.toDouble() * 8.0 / durationMs).toInt()
        } else {
            -1
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setDurationMs(durationMs.takeIf { it > 0L })
                    .build()
            )
            .build()
}

/**
 * Lee los audios del dispositivo (memoria interna y SD) vía MediaStore.
 * Los archivos se entregan a ExoPlayer sin modificar; OGG es un contenedor,
 * no una garantía de compresión sin pérdida.
 */
object AudioStore {

    /** Cheap cache validation; any unsupported/inconsistent provider falls back to a full scan. */
    internal fun querySourceStamp(context: Context, cancellation: CancellationSignal): LibrarySourceStamp? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            cancellation.throwIfCanceled()
            val names = MediaStore.getExternalVolumeNames(context)
            if (names.isEmpty()) return null
            val volumes = LinkedHashMap<String, LibrarySourceStamp.Volume>(names.size)
            for (name in names.sorted()) {
                cancellation.throwIfCanceled()
                val version: String? = MediaStore.getVersion(context, name)
                if (version.isNullOrEmpty()) return null
                val generation = MediaStore.getGeneration(context, name)
                // A provider database can reset while these separate calls are in flight.
                if (generation < 0L || version != MediaStore.getVersion(context, name)) return null
                volumes[name] = LibrarySourceStamp.Volume(version, generation)
            }
            cancellation.throwIfCanceled()
            if (names != MediaStore.getExternalVolumeNames(context)) return null
            LibrarySourceStamp(Collections.unmodifiableMap(volumes))
        } catch (error: OperationCanceledException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    val collectionUri: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /** Optional quality metadata: only resolve the active file, never open every library row. */
    fun querySize(context: Context, uri: Uri, cancellationSignal: CancellationSignal): Long =
        context.contentResolver.openFileDescriptor(uri, "r", cancellationSignal)
            ?.use { it.statSize.coerceAtLeast(0L) } ?: 0L

    fun queryAudios(
        context: Context,
        cancellationSignal: CancellationSignal? = null
    ): List<AudioItem> {
        val items = mutableListOf<AudioItem>()
        val collection = collectionUri

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val sortOrder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} COLLATE NOCASE ASC, " +
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC, ${MediaStore.Audio.Media._ID} ASC"
        } else {
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC, ${MediaStore.Audio.Media._ID} ASC"
        }

        context.contentResolver.query(
            collection,
            projection,
            null, // MediaStore puede desconocer la duración de un archivo reproducible.
            null,
            sortOrder,
            cancellationSignal
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            }

            while (cursor.moveToNext()) {
                cancellationSignal?.throwIfCanceled()
                val id = cursor.getLong(idCol)
                val rawPath = cursor.getString(pathCol) ?: ""
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rawPath.trimEnd('/').ifBlank { "Dispositivo" }
                } else {
                    rawPath.substringBeforeLast('/').substringAfterLast('/')
                        .ifBlank { "Dispositivo" }
                }
                val uri = ContentUris.withAppendedId(collection, id)
                val sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L)
                items.add(
                    AudioItem(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Sin título",
                        artist = cursor.getString(artistCol) ?: "",
                        album = cursor.getString(albumCol) ?: "",
                        durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                        contentUri = uri,
                        folder = folder,
                        sizeBytes = sizeBytes
                    )
                )
            }
        } ?: error("MediaStore no devolvió una biblioteca")
        return items
    }
}
