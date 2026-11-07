package com.lightningstudio.watchrss.phone.syncv2

import org.junit.Assume.assumeTrue
import java.io.File

/** Missing opt-in paths skip before any fixture read, evidence write or HTTP call. */
internal object SyncV2JointEnvironment {
    fun directory(name:String):File {
        val value=System.getenv(name)
        assumeTrue("Set $name to run this joint test",!value.isNullOrBlank())
        return File(value!!)
    }
}
