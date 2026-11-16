package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.*

object B2Switch {
    /** Exactly one final legacy frame in either direction. No buffered read-ahead and no
     * legacy action:ack. The caller has installed its B2 dispatcher before this call. */
    fun perform(input: InputStream, output: OutputStream, capability: JSONObject, enabled: Boolean) {
        if (!enabled) fail("AUTH_REVOKED")
        B2Wire.capability(capability)
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val p = obj("codec" to "lb2", "watchCapabilityDigest" to B2Digests.capability(capability))
        val request = obj("action" to B2Wire.ACTION, "version" to 2, "phase" to "switch", "type" to "request", "requestId" to id, "payload" to p)
        val raw = B2Json.utf8(request.toString())
        DataOutputStream(output).apply { writeInt(raw.size); write(raw); flush() }
        val reader = DataInputStream(input); val n = reader.readInt()
        if (n !in 1..131072) fail()
        val answer = B2Json.parse(ByteArray(n).also { reader.readFully(it) })
        val success = B2Json.bool(answer.get("success"))
        B2Json.exact(answer, *(listOf("action", "version", "phase", "type", "requestId", "success", "result") + if (success) emptyList() else listOf("error")).toTypedArray())
        if (answer.s("action") != B2Wire.ACTION || answer.n("version") != 2L || answer.s("phase") != "switch" || answer.s("type") != "response" || answer.s("requestId") != id) fail()
        if (!success) { if (!answer.nil("result")) fail(); B2Results.error(answer.o("error")); fail(answer.o("error").s("code")) }
        B2Json.exact(answer.o("result"), "codec", "watchCapabilityDigest")
        if (answer.o("result").s("codec") != "lb2" || answer.o("result").s("watchCapabilityDigest") != p.s("watchCapabilityDigest")) fail("CAPABILITY_MISMATCH")
    }
}
