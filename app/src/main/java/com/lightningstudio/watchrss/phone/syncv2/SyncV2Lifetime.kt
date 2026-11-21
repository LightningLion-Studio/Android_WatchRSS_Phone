package com.lightningstudio.watchrss.phone.syncv2

/** Registered before any async work. Close can cancel HTTP and wipe keys during a network wait. */
internal class SyncV2Lifetime(private val generation: Generation? = null, private val current:()->Boolean):AutoCloseable {
    /** Serialize only SQLite commit and invalidation. Resource disposal can wait separately. */
    internal class Generation(private val commitLock: Any = Any()) {
        @Volatile private var value = 0L
        fun current() = value
        fun invalidate() {
            synchronized(commitLock) { value++ }
        }
        fun <T> commit(work: () -> T): T = synchronized(commitLock) { work() }
    }

    private val openedAt = generation?.current()
    private var closed=false
    private val owned=mutableListOf<AutoCloseable>()
    @Synchronized fun check(){if(closed || openedAt != generation?.current() || !current())SyncV2Codec.fail("authentication_required")}
    @Synchronized fun <T> guard(work:()->T):T {check();return work()}
    @Synchronized fun commit(work: () -> Unit) {
        if (generation == null) {
            check()
            work()
        } else generation.commit {
            check()
            work()
        }
    }
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
