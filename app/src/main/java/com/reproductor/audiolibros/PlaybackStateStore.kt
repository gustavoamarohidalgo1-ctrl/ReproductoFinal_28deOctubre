package com.reproductor.audiolibros

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID

/**
 * Durable playback state owned exclusively by the playback service process.
 *
 * read/write methods perform blocking disk I/O. Call them on the same background
 * executor, never from the main thread or from a second process. Queue changes get a new
 * generation; frequent position/pause updates only rewrite the small checkpoint.
 * AtomicFile preserves each previous complete file if a write is interrupted.
 * Matching generations prevent a checkpoint from restoring a different queue.
 * A queue also contains its initial checkpoint, so interruption between the two
 * commits can restore a consistent position conservatively in the paused state.
 *
 * desiredPlaying records the user's intent, including an explicit pause. A caller
 * must additionally compare bootCount with the current boot before resuming audio;
 * reading a snapshot does not authorize playback after a reboot or an unknown boot.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackStateStore(context: Context) {

    data class Checkpoint(
        val generation: Long,
        val index: Int,
        val positionMs: Long,
        val desiredPlaying: Boolean,
        val savedAtMs: Long,
        val bootCount: Int,
        // STREAM_MUSIC index, not the player's digital gain. Null means unknown.
        val musicVolume: Int? = null
    )

    data class Snapshot(
        val queue: List<MediaItem>,
        val generation: Long,
        val index: Int,
        val positionMs: Long,
        val desiredPlaying: Boolean,
        val savedAtMs: Long,
        val bootCount: Int,
        val musicVolume: Int? = null
    )

    // A device restore must not resurrect playback intent from another installation.
    private val queueFile = AtomicFile(File(context.noBackupFilesDir, "playback_queue.json"))
    private val checkpointFile = AtomicFile(File(context.noBackupFilesDir, "playback_checkpoint.json"))

    private data class PersistedQueue(
        val generation: Long,
        val token: String?,
        val indices: Set<Int>
    )

    // The service may inspect this immutable value on its player thread. Disk writes
    // and reads remain serialized on the service executor, exclusively in :playback.
    @Volatile private var persistedQueue: PersistedQueue? = null

    /** True when saving this target requires a new queue/initial-checkpoint commit. */
    fun needsQueue(generation: Long, index: Int): Boolean {
        val persisted = persistedQueue ?: return true
        return persisted.generation != generation || index !in persisted.indices
    }

    /** Returns null for missing, corrupt, incompatible, or mismatched state. */
    fun read(): Snapshot? {
        // A missing/corrupt state must not leave an earlier in-memory queue eligible
        // for new checkpoints. Calls are serialized on the service's I/O executor.
        persistedQueue = null
        return try {
            val document = readJson(queueFile, MAX_QUEUE_BYTES) { readQueueDocument() } ?: return null
            val queueJson = document.header
            if (queueJson.requiredLong("version") != VERSION) return null
            if (queueJson.opt("cleared") == true) return null

            val generation = queueJson.requiredLong("generation") ?: return null
            if (generation <= 0L) return null
            val token = queueJson.opt("queueToken").let {
                if (it == null || it == JSONObject.NULL) null
                else (it as? String)?.takeIf { value -> value.length in 1..128 } ?: return null
            }
            val recentCheckpoint = try {
                readJson(checkpointFile, MAX_CHECKPOINT_BYTES) { readCheckpointObject() }
                    ?.parseCheckpoint(generation, token)
            } catch (exception: Exception) {
                Log.w(TAG, "Latest checkpoint could not be read; trying queue checkpoint", exception)
                null
            }
            val initialCheckpoint = queueJson.optJSONObject("initialCheckpoint")
                ?.parseCheckpoint(generation, token)?.copy(desiredPlaying = false)

            val items = document.items ?: return null
            val restoredIndices = items.indices
            // A bounded window can change without changing the player's generation.
            // Its token rejects older checkpoints, even when their index still exists.
            // A malformed/unavailable recent target also falls back conservatively.
            val checkpoint = recentCheckpoint?.takeIf { it.index in restoredIndices }
                ?: initialCheckpoint?.takeIf { it.index in restoredIndices }
                ?: return null
            val restoredIndex = restoredIndices.getValue(checkpoint.index)
            persistedQueue = PersistedQueue(generation, token, restoredIndices.keys.toSet())
            Snapshot(
                queue = items.queue,
                generation = generation,
                index = restoredIndex,
                positionMs = checkpoint.positionMs,
                desiredPlaying = checkpoint.desiredPlaying,
                savedAtMs = checkpoint.savedAtMs,
                bootCount = checkpoint.bootCount,
                musicVolume = checkpoint.musicVolume
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Playback state could not be read", exception)
            null
        }
    }

    /**
     * Commits a bounded recovery queue with its first checkpoint. The active local
     * entry is always included; subsequent entries have priority when a large queue
     * exceeds the disk limits. Original indices survive omission and truncation.
     * needsQueue() lets the service refresh this window when the active item changes.
     */
    fun writeQueue(items: List<MediaItem>, checkpoint: Checkpoint): Boolean {
        if (!checkpoint.isValid() || checkpoint.index !in items.indices) return false
        return try {
            var encodedQueue: PersistedQueue? = null
            // Open the atomic transaction before traversing or encoding the queue.
            // An unavailable staging file must not cost up to 20,000 encodings on
            // every retry. Validation failures below use the same rollback path.
            if (!writeAtomically(queueFile) { stream ->
                    val token = UUID.randomUUID().toString()
                    val header = JSONObject()
                        .put("version", VERSION)
                        .put("generation", checkpoint.generation)
                        .put("queueToken", token)
                        .put("initialCheckpoint", checkpoint.toJson(token))
                    val prefix = (header.toString().dropLast(1) + ",\"items\":[")
                        .toByteArray(Charsets.UTF_8)
                    // Encode each selected entry once. Account for the actual
                    // separators and closing ]} in the exact UTF-8 byte limit.
                    var encodedBytes = prefix.size + 2
                    val selected = ArrayList<EncodedQueueEntry>(minOf(items.size, MAX_QUEUE_ENTRIES))
                    for (offset in items.indices) {
                        if (selected.size >= MAX_QUEUE_ENTRIES) break
                        val index = ((checkpoint.index.toLong() + offset) % items.size).toInt()
                        val entry = items[index].toQueueEntry(index)
                        if (entry == null) {
                            if (index == checkpoint.index) {
                                throw IOException("The active playback item cannot be persisted")
                            }
                            continue
                        }
                        val bytes = entry.toString().toByteArray(Charsets.UTF_8)
                        val addition = bytes.size + if (selected.isEmpty()) 0 else 1
                        if (encodedBytes + addition > MAX_QUEUE_BYTES) break
                        selected.add(EncodedQueueEntry(index, bytes))
                        encodedBytes += addition
                    }
                    // Traversal starts at the active item; never commit without it.
                    if (selected.isEmpty()) throw IOException("The active playback item exceeds the queue limit")
                    // Preserve player order when priority traversal wraps at its end.
                    selected.sortBy { it.sourceIndex }
                    val output = stream.buffered()
                    output.write(prefix)
                    selected.forEachIndexed { index, entry ->
                        if (index > 0) output.write(','.code)
                        output.write(entry.bytes)
                    }
                    output.write(']'.code)
                    output.write('}'.code)
                    // AtomicFile owns closing/fsync; flush our small buffer first.
                    output.flush()
                    encodedQueue = PersistedQueue(
                        checkpoint.generation,
                        token,
                        selected.mapTo(HashSet(selected.size)) { it.sourceIndex }
                    )
                }
            ) return false
            // Publish eligibility/token only after AtomicFile's commit was verified.
            val committedQueue = checkNotNull(encodedQueue)
            persistedQueue = committedQueue
            if (committedQueue.indices.size < items.size) {
                Log.i(TAG, "Recovery queue saved ${committedQueue.indices.size}/${items.size} local entries, including active item")
            }
            true
        } catch (exception: Exception) {
            Log.w(TAG, "Playback queue could not be saved", exception)
            false
        }
    }

    private data class EncodedQueueEntry(val sourceIndex: Int, val bytes: ByteArray)

    /**
     * Durably removes the recovery target in one atomic commit. Keeping a tiny
     * tombstone, rather than deleting two files, prevents an older checkpoint from
     * restoring audio if the process dies during clearing. The next queue commit
     * gets a new token and therefore also rejects that older checkpoint.
     */
    fun clear(): Boolean {
        val cleared = writeJson(
            queueFile,
            JSONObject().put("version", VERSION).put("cleared", true),
            MAX_QUEUE_BYTES
        )
        if (cleared) persistedQueue = null
        return cleared
    }

    private fun MediaItem.toQueueEntry(index: Int): JSONObject? {
        val uri = localConfiguration?.uri?.toString() ?: return null
        if (localUri(uri) == null || mediaId.length > MAX_TEXT_LENGTH) return null
        val metadata = mediaMetadata
        return JSONObject()
            .put("sourceIndex", index)
            .put("uri", uri)
            .put("mediaId", mediaId)
            .put("title", metadata.title?.toString()?.take(MAX_TEXT_LENGTH))
            .put("artist", metadata.artist?.toString()?.take(MAX_TEXT_LENGTH))
            .put("album", metadata.albumTitle?.toString()?.take(MAX_TEXT_LENGTH))
            .put("durationMs", metadata.durationMs?.takeIf { it > 0L })
    }

    /** Persists position and user intent without rewriting the queue or metadata. */
    fun writeCheckpoint(checkpoint: Checkpoint): Boolean {
        if (!checkpoint.isValid()) return false
        val persisted = persistedQueue ?: return false
        if (persisted.generation != checkpoint.generation || checkpoint.index !in persisted.indices) {
            return false
        }
        return try {
            writeJson(
                checkpointFile,
                checkpoint.toJson(persisted.token),
                MAX_CHECKPOINT_BYTES
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Playback checkpoint could not be saved", exception)
            false
        }
    }

    private fun Checkpoint.isValid(): Boolean =
        generation > 0L && index >= 0 && positionMs >= 0L &&
            savedAtMs > 0L && bootCount >= -1 && (musicVolume == null || musicVolume >= 0)

    private fun Checkpoint.toJson(token: String?): JSONObject = JSONObject()
        .put("queueToken", token)
        .put("version", VERSION)
        .put("generation", generation)
        .put("index", index)
        .put("positionMs", positionMs)
        .put("desiredPlaying", desiredPlaying)
        .put("savedAtMs", savedAtMs)
        .put("bootCount", bootCount)
        .put("musicVolume", musicVolume)

    private fun JSONObject.parseCheckpoint(expectedGeneration: Long, expectedToken: String?): Checkpoint? {
        if (expectedToken != null && opt("queueToken") != expectedToken) return null
        if (requiredLong("version") != VERSION || requiredLong("generation") != expectedGeneration) {
            return null
        }
        val index = requiredLong("index") ?: return null
        val position = requiredLong("positionMs") ?: return null
        val savedAt = requiredLong("savedAtMs") ?: return null
        val boot = requiredLong("bootCount") ?: return null
        val playing = opt("desiredPlaying") as? Boolean ?: return null
        if (index !in 0L..Int.MAX_VALUE.toLong() || boot !in -1L..Int.MAX_VALUE.toLong()) {
            return null
        }
        val volume = requiredLong("musicVolume")?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
        return Checkpoint(expectedGeneration, index.toInt(), position, playing, savedAt, boot.toInt(), volume)
            .takeIf { it.isValid() }
    }

    private fun writeJson(file: AtomicFile, json: JSONObject, maxBytes: Int): Boolean {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return false
        return writeAtomically(file) { stream -> stream.write(bytes) }
    }

    private fun writeAtomically(file: AtomicFile, write: (FileOutputStream) -> Unit): Boolean {
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            write(stream)
            val written = Os.fstat(stream.fd)
            file.finishWrite(stream)
            // finishWrite reports some commit failures only through Log. Verify
            // that the next AtomicFile read actually opens the file we wrote.
            // This also honors legacy .bak recovery on Android 8/9/10. Only file
            // metadata is read; there is no second fsync or full-content read.
            stream = null // finishWrite owns closing; do not roll back after it.
            val committed = file.openRead().use { Os.fstat(it.fd) }
            check(written.st_dev == committed.st_dev && written.st_ino == committed.st_ino &&
                written.st_size == committed.st_size
            ) { "AtomicFile did not commit the playback state" }
            true
        } catch (exception: Exception) {
            Log.w(TAG, "Playback state write failed", exception)
            false
        } finally {
            // Roll back and close every unfinished transaction, including when
            // encoding throws an Error. Errors still propagate to the caller.
            // finishWrite clears ownership above, so verification cannot undo a commit.
            stream?.let { file.failWrite(it) }
        }
    }

    private data class QueueDocument(val header: JSONObject, val items: QueueItems?)

    private data class QueueItems(val queue: List<MediaItem>, val indices: Map<Int, Int>)

    private data class QueueEntry(val sourceIndex: Int, val mediaItem: MediaItem?)

    private object InvalidField

    /**
     * Stream the large queue directly into its final media items. Only the small
     * header/checkpoint uses JSONObject; no complete byte array, String or JSON
     * tree is retained alongside the restored queue. All app-written versions
     * use standard JSON. Lenient JSON extensions and trailing values are rejected.
     */
    private fun JsonReader.readQueueDocument(): QueueDocument {
        val header = JSONObject()
        var items: QueueItems? = null
        beginObject()
        while (hasNext()) {
            when (val name = nextName()) {
                "version", "generation", "queueToken", "cleared" ->
                    header.put(name, readScalar() ?: JSONObject.NULL)
                "initialCheckpoint" -> header.put(name, readCheckpointObject() ?: JSONObject.NULL)
                "items" -> items = readQueueItems()
                else -> skipBoundedValue()
            }
        }
        endObject()
        return QueueDocument(header, items)
    }

    private fun JsonReader.readQueueItems(): QueueItems? {
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipBoundedValue()
            return null
        }
        val queue = ArrayList<MediaItem>()
        val sourceIndices = HashSet<Int>()
        val restoredIndices = HashMap<Int, Int>()
        var count = 0
        var valid = true
        beginArray()
        while (hasNext()) {
            if (!valid || ++count > MAX_QUEUE_ENTRIES) {
                valid = false
                skipBoundedValue()
                continue
            }
            val entry = readQueueEntry()
            if (entry == null || !sourceIndices.add(entry.sourceIndex)) {
                valid = false
                continue
            }
            entry.mediaItem?.let {
                restoredIndices[entry.sourceIndex] = queue.size
                queue.add(it)
            }
        }
        endArray()
        // Defer semantic failure until the whole document is read: like JSONObject,
        // a repeated "items" property replaces its previous value (last one wins).
        return if (valid) QueueItems(queue, restoredIndices) else null
    }

    private fun JsonReader.readQueueEntry(): QueueEntry? {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipBoundedValue()
            return null
        }
        var sourceIndex: Any? = null
        var uri: Any? = null
        var mediaId: Any? = null
        var title: Any? = null
        var artist: Any? = null
        var album: Any? = null
        var duration: Any? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "sourceIndex" -> sourceIndex = readScalar()
                "uri" -> uri = readScalar()
                "mediaId" -> mediaId = readScalar()
                "title" -> title = readScalar()
                "artist" -> artist = readScalar()
                "album" -> album = readScalar()
                "durationMs" -> duration = readScalar()
                else -> skipBoundedValue()
            }
        }
        endObject()
        val index = sourceIndex as? Long ?: return null
        if (index !in 0L..Int.MAX_VALUE.toLong()) return null
        // Field order must not matter. Invalid metadata on a non-local entry is
        // ignored, just as before; its source index still participates in uniqueness.
        val local = (uri as? String)?.let(::localUri) ?: return QueueEntry(index.toInt(), null)
        val id = mediaId as? String ?: return null
        if (id.length > MAX_TEXT_LENGTH || !title.isOptionalText() ||
            !artist.isOptionalText() || !album.isOptionalText()
        ) return null
        val metadata = MediaMetadata.Builder()
            .setTitle(title as? String)
            .setArtist(artist as? String)
            .setAlbumTitle(album as? String)
            // Absent/malformed optional duration must not discard listening progress.
            .setDurationMs((duration as? Long)?.takeIf { it > 0L })
            .build()
        return QueueEntry(index.toInt(), MediaItem.Builder()
            .setMediaId(id).setUri(local).setMediaMetadata(metadata).build())
    }

    private fun Any?.isOptionalText(): Boolean =
        this == null || (this is String && length <= MAX_TEXT_LENGTH)

    private fun JsonReader.readCheckpointObject(): JSONObject? {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipBoundedValue()
            return null
        }
        val checkpoint = JSONObject()
        beginObject()
        while (hasNext()) {
            when (val name = nextName()) {
                "version", "generation", "queueToken", "index", "positionMs",
                "desiredPlaying", "savedAtMs", "bootCount", "musicVolume" ->
                    checkpoint.put(name, readScalar() ?: JSONObject.NULL)
                else -> skipBoundedValue()
            }
        }
        endObject()
        return checkpoint
    }

    private fun JsonReader.readScalar(): Any? = when (peek()) {
        JsonToken.STRING -> nextString()
        // nextLong() accepts quoted numbers and even coerces fractional/exponent
        // forms through Double. Preserve the old strict integral-value contract.
        JsonToken.NUMBER -> nextString().toLongOrNull() ?: InvalidField
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipBoundedValue(); InvalidField }
    }

    private fun JsonReader.skipBoundedValue(depth: Int = 0) {
        require(depth <= MAX_UNKNOWN_DEPTH) { "Playback state nesting is too deep" }
        when (peek()) {
            JsonToken.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipBoundedValue(depth + 1)
                }
                endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipBoundedValue(depth + 1)
                endArray()
            }
            else -> skipValue()
        }
    }

    private fun <T> readJson(file: AtomicFile, maxBytes: Int, read: JsonReader.() -> T): T? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { stream ->
            // Recover a legacy backup before inspecting size. Count raw UTF-8
            // bytes as well, so a file growing during parsing cannot evade the cap.
            if (stream.channel.size() > maxBytes) return null
            JsonReader(InputStreamReader(BoundedInputStream(stream, maxBytes), Charsets.UTF_8)).use { reader ->
                val result = reader.read()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing playback state data" }
                result
            }
        }
    }

    private class BoundedInputStream(private val source: InputStream, limit: Int) : InputStream() {
        private var remaining = limit

        override fun read(): Int {
            val value = source.read()
            if (value != -1 && --remaining < 0) throw IOException("Playback state exceeds byte limit")
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            // One extra byte distinguishes exact-limit EOF from an oversized file.
            val count = source.read(buffer, offset, minOf(length, remaining + 1))
            if (count > remaining) throw IOException("Playback state exceeds byte limit")
            if (count > 0) remaining -= count
            return count
        }

        override fun close() = source.close()
    }

    private fun localUri(value: String): Uri? {
        if (value.length > MAX_URI_LENGTH) return null
        val uri = Uri.parse(value)
        return when (uri.scheme) {
            "content", "android.resource" -> uri.takeIf { !it.authority.isNullOrBlank() }
            "file" -> uri.takeIf { it.path?.startsWith("/") == true }
            else -> null
        }
    }

    private fun JSONObject.requiredLong(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    companion object {
        private const val TAG = "PlaybackStateStore"
        private const val VERSION = 1L
        private const val MAX_QUEUE_ENTRIES = 20_000
        private const val MAX_QUEUE_BYTES = 16 * 1024 * 1024
        private const val MAX_CHECKPOINT_BYTES = 4_096
        private const val MAX_URI_LENGTH = 16_384
        private const val MAX_TEXT_LENGTH = 8_192
        private const val MAX_UNKNOWN_DEPTH = 64
    }
}
