package com.reproductor.audiolibros

import java.util.concurrent.Executor

/**
 * Serializes replaceable, self-contained snapshots without an unbounded task queue.
 * A running task finishes normally; subsequent submissions retain only the latest task.
 *
 * The pending task is drained in the same delegate invocation, before other work queued
 * on a shared serial [executor]. Tasks must therefore contain all data needed to persist
 * their snapshot, rather than depend on a previous submission that may be replaced.
 * This class does not own or shut down the delegate executor.
 */
internal class LatestTaskExecutor(
    private val executor: Executor,
    private val onTaskFailure: (Throwable) -> Unit = { throw it }
) : Executor {
    private val lock = Any()
    private var pending: Runnable? = null
    private var runner: Any? = null

    override fun execute(command: Runnable) {
        val dispatch = synchronized(lock) {
            pending = command
            if (runner != null) null else Any().also { runner = it }
        } ?: return

        try {
            executor.execute { drain(dispatch) }
        } catch (failure: Throwable) {
            // A rejected dispatch must not leave the writer permanently marked active.
            // Identity also protects a newer dispatch when a direct executor propagates
            // an exception from an already-completed drain.
            synchronized(lock) {
                if (runner === dispatch) {
                    runner = null
                    pending = null
                }
            }
            throw failure
        }
    }

    private fun drain(dispatch: Any) {
        var unhandledFailure: Throwable? = null
        while (true) {
            val task = synchronized(lock) {
                if (runner !== dispatch) {
                    null
                } else {
                    pending.also {
                        pending = null
                        if (it == null) runner = null
                    }
                }
            } ?: break

            try {
                task.run()
            } catch (failure: Throwable) {
                try {
                    onTaskFailure(failure)
                } catch (handlerFailure: Throwable) {
                    // Keep draining so a failed write cannot strand a newer pause or
                    // let a subsequent shared-executor read overtake the latest state.
                    if (unhandledFailure == null) unhandledFailure = handlerFailure
                }
            }
        }
        unhandledFailure?.let { throw it }
    }
}
