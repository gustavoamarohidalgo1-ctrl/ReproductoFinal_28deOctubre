package com.reproductor.audiolibros

import android.os.Process
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Runs non-urgent library work at a lower OS scheduling priority. */
internal class BackgroundThreadFactory(private val name: String) : ThreadFactory {
    private val nextId = AtomicInteger()

    override fun newThread(task: Runnable): Thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        task.run()
    }, "$name-${nextId.incrementAndGet()}")
}
