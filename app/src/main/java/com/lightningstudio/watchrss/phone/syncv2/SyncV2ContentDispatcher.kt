package com.lightningstudio.watchrss.phone.syncv2

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A dedicated, bounded host executor. P2 control operations never enter this queue.
 * The OS/host grants a single budget window; completion does not resubmit another one. */
internal class SyncV2ContentDispatcher:AutoCloseable {
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),
        {job->Thread(job,"watchrss-syncv2-content").apply{isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    fun <T> submit(window:()->T):Future<T> = executor.submit(Callable {window()})
    override fun close(){executor.shutdownNow()}
}
