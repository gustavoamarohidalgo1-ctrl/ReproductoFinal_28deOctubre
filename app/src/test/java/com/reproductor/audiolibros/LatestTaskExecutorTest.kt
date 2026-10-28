package com.reproductor.audiolibros

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LatestTaskExecutorTest {
    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    @Test
    fun tenThousandCheckpointsWaitingForDiskRetainOnlyTheLatestPause() {
        val executor = QueuedExecutor()
        val writer = LatestTaskExecutor(executor)
        val writes = mutableListOf<Pair<Int, Boolean>>()
        repeat(10_000) { position ->
            writer.execute { writes.add(position to true) }
        }
        writer.execute { writes.add(10_000 to false) }

        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(listOf(10_000 to false), writes)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun runningSnapshotFinishesAndLatestPendingDrainsBeforeSharedExecutorRead() {
        val executor = QueuedExecutor()
        val writer = LatestTaskExecutor(executor)
        val events = mutableListOf<String>()
        writer.execute {
            events.add("write started")
            repeat(10_000) { checkpoint ->
                writer.execute { events.add("checkpoint $checkpoint") }
            }
            writer.execute { events.add("latest pause") }
            executor.execute { events.add("new service read") }
            events.add("write finished")
        }

        executor.runNext()
        assertEquals(listOf("write started", "write finished", "latest pause"), events)
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals("new service read", events.last())
    }

    @Test
    fun snapshotSubmittedAfterDrainCompletesStartsAnotherDispatch() {
        val executor = QueuedExecutor()
        val writer = LatestTaskExecutor(executor)
        val writes = mutableListOf<Int>()
        writer.execute { writes.add(1) }
        executor.runNext()
        writer.execute { writes.add(2) }

        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(listOf(1, 2), writes)
    }

    @Test
    fun failedTaskDoesNotStrandPendingPauseOrFutureSubmissions() {
        val executor = QueuedExecutor()
        val failures = mutableListOf<Throwable>()
        val writer = LatestTaskExecutor(executor, failures::add)
        val writes = mutableListOf<String>()
        val failure = IllegalStateException("Storage failed")
        writer.execute {
            writer.execute { writes.add("pause") }
            throw failure
        }

        executor.runNext()
        assertEquals(listOf("pause"), writes)
        assertEquals(listOf(failure), failures)
        writer.execute { writes.add("next session") }
        executor.runNext()
        assertEquals(listOf("pause", "next session"), writes)
    }

    @Test
    fun unhandledFailureIsReportedAfterPendingWorkAndStateIsReusable() {
        val executor = QueuedExecutor()
        val writer = LatestTaskExecutor(executor)
        var latestPausePersisted = false
        val failure = IllegalStateException("Storage failed")
        writer.execute {
            writer.execute { latestPausePersisted = true }
            throw failure
        }

        try {
            executor.runNext()
            fail("Expected the task failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertTrue(latestPausePersisted)
        var nextTaskRan = false
        writer.execute { nextTaskRan = true }
        executor.runNext()
        assertTrue(nextTaskRan)
    }

    @Test
    fun rejectedDispatchDoesNotFreezeSubsequentSubmission() {
        val executor = QueuedExecutor()
        var reject = true
        val writer = LatestTaskExecutor(Executor { task ->
            if (reject) throw RejectedExecutionException("Unavailable")
            executor.execute(task)
        })
        try {
            writer.execute { fail("Rejected task must not run") }
            fail("Expected rejection")
        } catch (_: RejectedExecutionException) {
            // The caller can retry after the delegate becomes available.
        }

        reject = false
        var retried = false
        writer.execute { retried = true }
        executor.runNext()
        assertTrue(retried)
    }

    @Test
    fun synchronousDelegateSupportsSubmissionsFromInsideTheRunningTask() {
        val writer = LatestTaskExecutor(Executor { it.run() })
        val writes = mutableListOf<Int>()
        writer.execute {
            writes.add(1)
            writer.execute { writes.add(2) }
            writer.execute { writes.add(3) }
        }
        assertEquals(listOf(1, 3), writes)
    }

    @Test
    fun concurrentProducersCannotBuildBacklogWhileDiskIsBlocked() {
        val executor = Executors.newSingleThreadExecutor()
        val producers = Executors.newFixedThreadPool(4)
        val writer = LatestTaskExecutor(executor)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val writes = AtomicInteger()
        val finalPosition = AtomicInteger(-1)
        try {
            writer.execute {
                writes.incrementAndGet()
                started.countDown()
                check(unblock.await(10, TimeUnit.SECONDS))
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            val submissions = (0 until 4).map { producer ->
                producers.submit {
                    repeat(2_500) { offset ->
                        val position = producer * 2_500 + offset
                        writer.execute {
                            writes.incrementAndGet()
                            finalPosition.set(position)
                        }
                    }
                }
            }
            submissions.forEach { it.get(5, TimeUnit.SECONDS) }
            writer.execute {
                writes.incrementAndGet()
                finalPosition.set(10_000)
            }
            // This read represents a new service instance sharing the serial executor.
            executor.execute { finished.countDown() }
            unblock.countDown()

            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(2, writes.get())
            assertEquals(10_000, finalPosition.get())
        } finally {
            unblock.countDown()
            producers.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSubmissionsAroundIdleTransitionsNeverStrandTheLatestSnapshot() {
        val executor = Executors.newSingleThreadExecutor()
        val producers = Executors.newFixedThreadPool(4)
        val writer = LatestTaskExecutor(executor)
        val finalPosition = AtomicInteger(-1)
        try {
            repeat(30) { wave ->
                val submissions = (0 until 4).map { producer ->
                    producers.submit {
                        repeat(250) { offset ->
                            writer.execute { finalPosition.set(producer * 250 + offset) }
                            if (offset % 10 == 0) Thread.yield()
                        }
                    }
                }
                submissions.forEach { it.get(5, TimeUnit.SECONDS) }
                val latestWritten = CountDownLatch(1)
                val finalSnapshot = 1_000 + wave
                writer.execute {
                    finalPosition.set(finalSnapshot)
                    latestWritten.countDown()
                }
                assertTrue("Latest snapshot stranded in wave $wave", latestWritten.await(5, TimeUnit.SECONDS))
                assertEquals(finalSnapshot, finalPosition.get())
            }
        } finally {
            producers.shutdownNow()
            executor.shutdownNow()
        }
    }
}
