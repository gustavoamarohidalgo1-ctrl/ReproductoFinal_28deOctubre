package com.reproductor.audiolibros

/**
 * The durable session checkpoint tracks active playback more frequently. Bookmarks
 * only need periodic writes plus explicit pause/seek/track-change boundaries.
 * This cache belongs to the playback process; it is not a cross-process store.
 */
internal class PositionCheckpointCache(
    private val readPosition: (String) -> Long,
    private val writePosition: (String, Long) -> Unit,
    private val removePosition: (String) -> Unit,
    private val nowMs: () -> Long,
    private val writeIntervalMs: Long = 30_000L,
    private val maxEntries: Int = 256
) {
    private data class Entry(
        var current: Long,
        var persisted: Long,
        var lastWrittenAt: Long? = null
    )

    init { require(maxEntries > 0) }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private fun entry(mediaId: String): Entry {
        entries[mediaId]?.let { return it }
        val position = readPosition(mediaId).coerceAtLeast(0L)
        if (entries.size >= maxEntries) {
            val oldest = entries.entries.iterator().next()
            // Release only the in-memory copy, never the saved bookmark. Flush
            // pending progress first; a failed write leaves the entry available.
            if (oldest.value.current != oldest.value.persisted) {
                writePosition(oldest.key, oldest.value.current)
            }
            entries.remove(oldest.key)
        }
        return Entry(position, position).also { entries[mediaId] = it }
    }

    @Synchronized
    fun save(mediaId: String, positionMs: Long, force: Boolean = false) {
        val entry = entry(mediaId)
        val position = positionMs.coerceAtLeast(0L)
        val movedBackwards = position < entry.current
        entry.current = position
        if (position == entry.persisted) return
        val now = nowMs()
        val lastWritten = entry.lastWrittenAt
        if (force || movedBackwards || lastWritten == null || now - lastWritten >= writeIntervalMs) {
            writePosition(mediaId, position)
            entry.persisted = position
            entry.lastWrittenAt = now
        }
    }

    @Synchronized
    fun get(mediaId: String): Long = entry(mediaId).current

    @Synchronized
    fun clear(mediaId: String) {
        removePosition(mediaId)
        entries.remove(mediaId)
    }
}
