package com.reproductor.audiolibros

import org.junit.Assert.*
import org.junit.Test

class QueueRevisionTest {
    @Test fun orderedPlaybackFieldsHaveAStableRevision() {
        assertEquals(revision(listOf(Row("1"), Row("2"))), revision(listOf(Row("1"), Row("2"))))
        assertNotEquals(revision(listOf(Row("1"), Row("2"))), revision(listOf(Row("2"), Row("1"))))
    }

    @Test fun sizeAndFolderChangesDoNotReplaceAnOtherwiseIdenticalPlayerQueue() {
        val before = Row("1", folder = "Books/First", size = 100)
        val after = before.copy(folder = "Books/Moved", size = 200)
        assertNotEquals(before, after) // The UI snapshot must still see these changes.
        assertEquals(revision(listOf(before)), revision(listOf(after)))
    }

    @Test fun changedIdentityOrPlaybackMetadataInvalidatesTheQueue() {
        val before = Row("1")
        val changes = listOf(
            before.copy(id = "2"), before.copy(uri = "content://media/audio/9"),
            before.copy(title = "new title"), before.copy(artist = "new artist"),
            before.copy(album = "new album"), before.copy(duration = 25L * 60 * 60 * 1_000)
        )
        changes.forEach { assertNotEquals(revision(listOf(before)), revision(listOf(it))) }
    }

    @Test fun lengthPrefixesKeepAdjacentMetadataFieldsUnambiguous() {
        assertNotEquals(revision(listOf(Row("1", title = "ab", artist = "c"))),
            revision(listOf(Row("1", title = "a", artist = "bc"))))
    }

    private fun revision(rows: List<Row>): String = QueueRevision().apply {
        rows.forEach { add(it.id, it.uri, it.title, it.artist, it.album, it.duration) }
    }.finish()

    private data class Row(
        val id: String,
        val uri: String = "content://media/audio/1",
        val title: String = "Book",
        val artist: String = "Reader",
        val album: String = "Collection",
        val duration: Long = 10_000,
        val folder: String = "Books",
        val size: Long = 100
    )
}
