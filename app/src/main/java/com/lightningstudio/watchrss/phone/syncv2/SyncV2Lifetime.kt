package com.lightningstudio.watchrss.phone.syncv2

/** Registered before any async work. Close can cancel HTTP and wipe keys during a network wait. */
internal class SyncV2Lifetime(private val current:()->Boolean):AutoCloseable {
    private var closed=false
    private val owned=mutableListOf<AutoCloseable>()
    @Synchronized fun check(){if(closed || !current())SyncV2Codec.fail("authentication_required")}
    @Synchronized fun <T> guard(work:()->T):T {check();return work()}
    @Synchronized fun <T:AutoCloseable> own(value:T):T {
        try{check();owned.add(value);return value}catch(e:Throwable){value.close();throw e}
    }
    @Synchronized override fun close() {
        if(closed)return
        closed=true
        owned.asReversed().forEach{runCatching{it.close()}}
        owned.clear()
    }
}
