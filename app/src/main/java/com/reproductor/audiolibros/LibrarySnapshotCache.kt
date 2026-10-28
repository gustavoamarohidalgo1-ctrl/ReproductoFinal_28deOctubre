package com.reproductor.audiolibros

import java.util.Collections

/**
 * Process-local, immutable catalogue cache. Expensive comparison and construction run on
 * the caller's worker; tickets prevent a cancelled/older load from publishing over a new one.
 * Identical scans retain the derived objects and revision (including all MediaItems).
 */
internal class LibrarySnapshotCache<T, D> {
    data class Snapshot<T, D>(val items: List<T>, val derived: D, val revision: Long)

    private val lock = Any()
    private var latestTicket = 0L
    private var revision = 0L
    private var sourceStamp: LibrarySourceStamp? = null
    private var sourceInvalidation = 0L
    private var loadInvalidation = 0L
    @Volatile private var current: Snapshot<T, D>? = null

    fun snapshot(): Snapshot<T, D>? = current

    fun beginLoad(): Long = synchronized(lock) {
        loadInvalidation = sourceInvalidation
        ++latestTicket
    }

    fun reuse(ticket: Long, stamp: LibrarySourceStamp?): Snapshot<T, D>? = synchronized(lock) {
        if (ticket == latestTicket && stamp != null && stamp == sourceStamp) current else null
    }

    /** Observer/manual refreshes must win even when a provider's generation is unchanged. */
    fun invalidateSource() = synchronized(lock) {
        sourceInvalidation++
        sourceStamp = null
    }

    fun cancel(ticket: Long) = synchronized(lock) {
        if (ticket == latestTicket) latestTicket++
    }

    fun clear() = synchronized(lock) {
        latestTicket++
        current = null
        sourceInvalidation++
        sourceStamp = null
    }

    fun update(
        ticket: Long,
        items: List<T>,
        stamp: LibrarySourceStamp? = null,
        build: (List<T>) -> D
    ): Snapshot<T, D>? {
        val previous = current
        if (!isCurrent(ticket)) return null
        // A query owns its mutable result. Never expose it to an adapter or a diff worker.
        val unchanged = previous?.items == items
        val immutable = if (unchanged) previous!!.items else
            Collections.unmodifiableList(ArrayList(items))
        if (!isCurrent(ticket)) return null
        val derived = if (unchanged) previous!!.derived else build(immutable)
        return synchronized(lock) {
            if (ticket != latestTicket) return@synchronized null
            sourceStamp = stamp.takeIf { loadInvalidation == sourceInvalidation }
            if (unchanged) previous else Snapshot(immutable, derived, ++revision).also { current = it }
        }
    }

    private fun isCurrent(ticket: Long): Boolean = synchronized(lock) { ticket == latestTicket }
}
