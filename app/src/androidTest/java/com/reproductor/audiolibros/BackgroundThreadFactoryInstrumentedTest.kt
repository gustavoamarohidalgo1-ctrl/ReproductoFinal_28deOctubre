package com.reproductor.audiolibros

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundThreadFactoryInstrumentedTest {
    @Test fun libraryWorkerRunsAtBackgroundPriorityWithoutChangingCaller() {
        val callerTid = Process.myTid()
        val callerPriority = Process.getThreadPriority(callerTid)
        val executor = Executors.newSingleThreadExecutor(BackgroundThreadFactory("audio-library-test"))
        try {
            val workerPriority = executor.submit<Int> {
                Process.getThreadPriority(Process.myTid())
            }.get(10, TimeUnit.SECONDS)
            assertEquals(Process.THREAD_PRIORITY_BACKGROUND, workerPriority)
            assertEquals(callerPriority, Process.getThreadPriority(callerTid))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
