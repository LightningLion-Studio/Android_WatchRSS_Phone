package com.lightningstudio.watchrss.phone.connection.ip

import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

/** Bounds a blocked pipe read, not peer JSON parsing or local database work. Positive bytes renew it. */
internal class IpReadDeadline(
    private val source:InputStream,
    private val defaultTimeoutMs:Long=30_000,
    private val onProgress:(Int)->Unit={},
    private val disconnect:(Long)->Unit
):InputStream() {
    private data class Budget(val initial:Long,val progress:Long,var received:Boolean=false)
    private var budget:Budget?=null // The protocol has exactly one input consumer.
    fun <T> withBudget(initialMs:Long,progressMs:Long=20_000,read:()->T):T {
        require(initialMs>0 && progressMs>0)
        val previous=budget;budget=Budget(initialMs,progressMs)
        return try{read()}finally{budget=previous}
    }
    private fun receive(read:()->Int):Int {
        val current=budget;val limit=current?.let{if(it.received)it.progress else it.initial}?:defaultTimeoutMs
        // One atomic outcome prevents a timeout racing a completed read from closing a healthy session.
        val outcome=AtomicInteger(0) // 0 pending, 1 completed, 2 expired
        val timer=clock.schedule({if(outcome.compareAndSet(0,2)) {
            disconnectors.execute{disconnect(limit)}
        }},limit,TimeUnit.MILLISECONDS)
        try {
            val count=read()
            outcome.compareAndSet(0,1)
            if(outcome.get()==2)throw SocketTimeoutException("IP 同步读取无进展（${limit}ms），正在回退蓝牙")
            if(count>0){current?.received=true;onProgress(count)}
            return count
        }catch(error:java.io.IOException){
            if(outcome.get()==2 && error !is SocketTimeoutException)throw SocketTimeoutException("IP 同步读取无进展（${limit}ms），正在回退蓝牙").also{it.initCause(error)}
            throw error
        }finally{outcome.compareAndSet(0,1);timer.cancel(false)}
    }
    override fun read():Int {
        var value=-1;receive {value=source.read();if(value<0)-1 else 1};return value
    }
    override fun read(bytes:ByteArray,offset:Int,length:Int):Int=if(length==0)0 else receive{source.read(bytes,offset,length)}
    override fun available()=source.available()
    override fun close()=source.close()
    companion object {
        private val clock=ScheduledThreadPoolExecutor(1,{r->Thread(r,"watchrss-ip-read-deadline").apply{isDaemon=true}}).apply{removeOnCancelPolicy=true}
        private val disconnectors=Executors.newCachedThreadPool{r->Thread(r,"watchrss-ip-disconnect").apply{isDaemon=true}}
    }
}
