package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** Explicit opt-in counters. Disabled in product; never records payloads, IDs, keys or paths.
 * Separate collectors belong to the sender and receiver threads. Exclusive spans subtract
 * instrumented children; cross-thread ACK wait still overlaps receiver execution. */
internal object SyncV2LocalProfile {
    @Volatile var enabled=false
    private val local=ThreadLocal<Collector?>()
    class Collector {
        private data class Event(var calls:Long=0,var nanos:Long=0,var bytes:Long=0)
        private data class Span(val name:String,val start:Long,var children:Long=0)
        private val events=linkedMapOf<String,Event>()
        private val stack=java.util.ArrayDeque<Span>()
        fun <T> time(name:String,bytes:Long,work:()->T):T {
            val span=Span(name,System.nanoTime());stack.addLast(span)
            try{return work()}finally{
                val elapsed=System.nanoTime()-span.start;stack.removeLast();stack.peekLast()?.let{it.children+=elapsed}
                events.getOrPut(name){Event()}.also{it.calls++;it.nanos+=elapsed-span.children;it.bytes+=bytes}
            }
        }
        fun count(name:String,bytes:Long=0){events.getOrPut(name){Event()}.also{it.calls++;it.bytes+=bytes}}
        fun json()=JSONObject().also{out->events.forEach{(name,e)->out.put(name,JSONObject().put("calls",e.calls).put("exclusiveNanos",e.nanos).put("bytes",e.bytes))}}
    }
    fun attach(value:Collector?) {if(value==null)local.remove()else local.set(value)}
    fun <T> time(name:String,bytes:Long=0,work:()->T):T {
        if(!enabled)return work()
        return local.get()?.time(name,bytes,work)?:work()
    }
    fun count(name:String,bytes:Long=0){if(enabled)local.get()?.count(name,bytes)}
    fun copy(bytes:ByteArray,start:Int=0,end:Int=bytes.size):ByteArray {count("payload.copy",(end-start).toLong());return bytes.copyOfRange(start,end)}
}
