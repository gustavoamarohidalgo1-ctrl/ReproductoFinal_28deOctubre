package com.reproductor.audiolibros

import androidx.recyclerview.widget.DiffUtil

/** Bounds pathological catalogue reorders without changing ordinary incremental updates. */
internal object LibraryDiff {
    const val MAX_COMPARISONS = 1_000_000

    sealed interface Result {
        data class Incremental(val updates: DiffUtil.DiffResult) : Result
        data object FullRefresh : Result
    }

    fun calculate(callback: DiffUtil.Callback, checkCanceled: () -> Unit): Result {
        var comparisons = 0
        fun beforeComparison() {
            checkCanceled()
            if (comparisons >= MAX_COMPARISONS) throw ComparisonBudgetExceeded()
            comparisons++
        }
        val bounded = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = callback.oldListSize
            override fun getNewListSize(): Int = callback.newListSize

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                beforeComparison()
                return callback.areItemsTheSame(oldItemPosition, newItemPosition)
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                beforeComparison()
                return callback.areContentsTheSame(oldItemPosition, newItemPosition)
            }

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
                callback.getChangePayload(oldItemPosition, newItemPosition)
        }
        checkCanceled()
        val result = try {
            Result.Incremental(DiffUtil.calculateDiff(bounded, false))
        } catch (_: ComparisonBudgetExceeded) {
            Result.FullRefresh
        }
        // Cancellation is distinct from exhausting the diff budget: neither a
        // partial diff nor a cancelled replacement may reach the adapter.
        checkCanceled()
        return result
    }

    private class ComparisonBudgetExceeded : RuntimeException(null, null, false, false)
}
