package com.reproductor.audiolibros

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Identifies exactly the ordered MediaItems sent to the player, excluding UI-only fields. */
internal class QueueRevision {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val number = ByteBuffer.allocate(java.lang.Long.BYTES)

    fun add(mediaId: String, uri: String, title: String, artist: String, album: String, durationMs: Long) {
        addString(mediaId)
        addString(uri)
        addString(title)
        addString(artist)
        addString(album)
        addLong(durationMs.takeIf { it > 0L } ?: 0L)
    }

    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun addString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        addLong(bytes.size.toLong())
        digest.update(bytes)
    }

    private fun addLong(value: Long) {
        number.clear()
        number.putLong(value)
        digest.update(number.array())
    }
}
