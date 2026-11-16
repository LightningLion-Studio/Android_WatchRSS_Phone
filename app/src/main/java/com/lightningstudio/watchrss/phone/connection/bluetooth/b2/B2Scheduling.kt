package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject

/** Deterministic admission, with existing runnable objects completed before a full
 * direction can admit another object. Hash order never overrides dependencies. */
object B2Scheduling {
    fun runnable(objects: List<JSONObject>, applied: Set<String>, states: Map<String, String>, slots: Int): List<JSONObject> {
        val ready = objects.filter { it.s("objectId") !in applied && it.a("dependsOn").strings().all { id -> id in applied } }.sortedBy { it.s("objectId") }
        if (ready.isEmpty()) fail("PLAN_CONFLICT")
        val active = states.count { it.key !in applied && it.value in listOf("PARTIAL", "STAGED") }
        return (if (active >= slots) ready.filter { states[it.s("objectId")] in listOf("PARTIAL", "STAGED") } else ready)
            .also { if (it.isEmpty()) fail("PLAN_CONFLICT") }
    }
}
