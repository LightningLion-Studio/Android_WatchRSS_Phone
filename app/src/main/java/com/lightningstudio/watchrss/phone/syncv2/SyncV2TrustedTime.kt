package com.lightningstudio.watchrss.phone.syncv2

import android.os.SystemClock

/** Process-local anchor. Persisting a monotonic reading across a reboot would invent trust. */
internal class SyncV2TrustedTime(private val monotonic:()->Long=SystemClock::elapsedRealtime):AutoCloseable {
    private var issued:Long?=null
    private var received=0L
    @Synchronized fun now():Long? {
        val base=issued?:return null
        val age=monotonic()-received
        if(age<0 || age>=86400000L || base>9007199254740991L-age)return null
        return base+age
    }
    /** Only the service-trust verifier may feed this after full checkpoint validation. */
    @Synchronized internal fun acceptVerifiedCheckpoint(value:Long):Boolean {
        if(value !in 0..9007199254740991L)SyncV2Codec.fail("invalid_schema")
        val prior=now()
        if(prior!=null && value<prior-300000L)return false
        issued=value;received=monotonic();return true
    }
    fun requireNow():Long=now()?:SyncV2Codec.fail("trusted_time_unavailable")
    @Synchronized override fun close(){issued=null;received=0}
}
