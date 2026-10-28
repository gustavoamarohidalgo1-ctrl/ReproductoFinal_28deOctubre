package com.reproductor.audiolibros

import android.content.ContentValues
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses one owned fixture to verify the real provider's version/generation contract. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class AudioStoreGenerationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun insertionRenameAndDeletionInvalidateTheActualMediaStoreStamp() {
        PlaybackTestContract.grantRuntimePermissions(instrumentation)
        val cancellation = CancellationSignal()
        val before = requireNotNull(AudioStore.querySourceStamp(context, cancellation))
        val resolver = context.contentResolver
        val name = "library-generation-${System.nanoTime()}"
        val uri = requireNotNull(resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp3")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/LibraryGenerationTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ))
        val id = requireNotNull(uri.lastPathSegment).toLong()
        var deleted = false
        var testFailure: Throwable? = null
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                instrumentation.context.assets.open("silence-320kbps.mp3").use { it.copyTo(output) }
            }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val inserted = awaitChangedStamp(before)
            assertTrue(AudioStore.queryAudios(context, cancellation).any { it.id == id })

            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name-renamed.mp3")
            }, null, null))
            val renamed = awaitChangedStamp(inserted)
            val removedRows = resolver.delete(uri, null, null)
            deleted = removedRows > 0
            assertEquals(1, removedRows)
            awaitChangedStamp(renamed)
            assertFalse(AudioStore.queryAudios(context, cancellation).any { it.id == id })
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            if (!deleted) {
                try {
                    resolver.delete(uri, null, null)
                } catch (cleanupFailure: Exception) {
                    // Preserve the assertion/provider failure that caused cleanup;
                    // an inaccessible fixture must not replace its real cause.
                    if (testFailure != null) testFailure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                }
            }
        }
    }

    @Test fun cancelledValidationPropagatesInsteadOfFallingBackToAnExpensiveScan() {
        val cancellation = CancellationSignal().apply { cancel() }
        try {
            AudioStore.querySourceStamp(context, cancellation)
            fail("Cancellation must not be reported as an unsupported provider")
        } catch (_: OperationCanceledException) {
            // The caller cancels the entire load, rather than starting queryAudios.
        }
    }

    private fun awaitChangedStamp(previous: LibrarySourceStamp): LibrarySourceStamp {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = AudioStore.querySourceStamp(context, CancellationSignal())
            if (current != null && current != previous) return current
            Thread.sleep(50)
        }
        error("MediaStore did not advance its stamp after the fixture changed")
    }
}
