package com.reproductor.audiolibros

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LibraryDiffTest {
    @Test
    fun reversedTwentyThousandItemsUseFullRefreshWithinTheComparisonBudget() {
        val old = (0 until 20_000).toList()
        val callback = Rows(old, old.reversed())

        val result = LibraryDiff.calculate(callback) {}

        assertSame(LibraryDiff.Result.FullRefresh, result)
        assertEquals(LibraryDiff.MAX_COMPARISONS, callback.comparisons)
        // The complete replacement remains available, never a partially reordered list.
        assertEquals(19_999, callback.new.first())
        assertEquals(0, callback.new.last())
        assertEquals(20_000, callback.new.toSet().size)
    }

    @Test
    fun smallInsertionRetainsIncrementalUpdatesAndMapsUnchangedRows() {
        val callback = Rows(listOf(10, 20, 30), listOf(10, 15, 20, 30))
        val result = LibraryDiff.calculate(callback) {}

        assertTrue(result is LibraryDiff.Result.Incremental)
        val diff = (result as LibraryDiff.Result.Incremental).updates
        val changes = mutableListOf<String>()
        diff.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { changes += "insert:$position:$count" }
            override fun onRemoved(position: Int, count: Int) { changes += "remove:$position:$count" }
            override fun onMoved(fromPosition: Int, toPosition: Int) { changes += "move:$fromPosition:$toPosition" }
            override fun onChanged(position: Int, count: Int, payload: Any?) { changes += "change:$position:$count" }
        })
        assertEquals(listOf("insert:1:1"), changes)
        assertEquals(0, diff.convertOldPositionToNew(0))
        assertEquals(2, diff.convertOldPositionToNew(1))
        assertEquals(3, diff.convertOldPositionToNew(2))
        assertEquals(-1, diff.convertNewPositionToOld(1))
        assertTrue(callback.comparisons < LibraryDiff.MAX_COMPARISONS)
    }

    @Test
    fun cancelledReorderPropagatesCancellationInsteadOfPublishingAReplacement() {
        val old = (0 until 20_000).toList()
        val callback = Rows(old, old.reversed())
        val cancellation = CancellationException("A newer library load superseded this one")

        try {
            LibraryDiff.calculate(callback) {
                if (callback.comparisons >= 100) throw cancellation
            }
            fail("A cancelled load must not return either a diff or a full replacement")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(100, callback.comparisons)
    }

    @Test
    fun cancellationAfterTheBudgetIsExhaustedStillPreventsPublication() {
        val old = (0 until 20_000).toList()
        val callback = Rows(old, old.reversed())
        val cancellation = CancellationException("The screen closed before publication")
        var checksAfterBudget = 0

        try {
            LibraryDiff.calculate(callback) {
                if (callback.comparisons == LibraryDiff.MAX_COMPARISONS &&
                    ++checksAfterBudget == 2
                ) throw cancellation
            }
            fail("Cancellation must take precedence over the full-refresh fallback")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(2, checksAfterBudget)
    }

    private class Rows(private val old: List<Int>, val new: List<Int>) : DiffUtil.Callback() {
        var comparisons = 0
            private set

        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            comparisons++
            return old[oldItemPosition] == new[newItemPosition]
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            comparisons++
            return old[oldItemPosition] == new[newItemPosition]
        }
    }
}
