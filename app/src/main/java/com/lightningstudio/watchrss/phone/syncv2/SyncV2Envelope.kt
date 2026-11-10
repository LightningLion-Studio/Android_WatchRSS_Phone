package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Dedicated v2 seal. Salt and nonce are final before AAD is encoded. Legacy crypto is untouched. */
internal object SyncV2Envelope {
    data class Sealed(val oid: String, val bytes: ByteArray, val header: JSONObject)
    fun seal(identity: SyncV2Identity, key: ByteArray, version: Long, type: String, plain: ByteArray): Sealed =
        sealWithParameters(identity.scope, { t, p -> identity.opaque(t, p) }, key, version, type, plain,
            SyncV2Codec.random(32), SyncV2Codec.random(12))

    /** Explicit entropy port also used by fixed public KATs; product callers use seal(). */
    internal fun sealWithParameters(scope: JSONObject, opaque: (String, ByteArray) -> String, key: ByteArray,
        version: Long, type: String, plain: ByteArray, salt: ByteArray, nonce: ByteArray,
        encoding: String = "identity", encoded: ByteArray = plain): Sealed {
        SyncV2Codec.scope(scope)
        if(plain.size>262144 || encoded.size>262144)SyncV2Codec.fail("object_too_large")
        if (key.size != 32 || salt.size != 32 || nonce.size != 12 || version !in 1..4294967295L ||
            type !in setOf("meta", "state", "blob", "recipe")) SyncV2Codec.fail("invalid_schema")
        val h = JSONObject().put("domain", "wrss.sync.v2/sealed").put("version", 2).put("scope", scope)
            .put("keyVersion", version).put("type", type).put("opaqueContentId", opaque(type, plain))
            .put("encoding", encoding).put("plainBytes", plain.size).put("encodedBytes", encoded.size)
            .put("salt", SyncV2Codec.b64(salt)).put("nonce", SyncV2Codec.b64(nonce))
        validate(h)
        if (encoding == "identity" && !plain.contentEquals(encoded) || encoding == "gzip" && !gunzip(encoded, plain.size).contentEquals(plain)) SyncV2Codec.fail("invalid_encoding")
        val aad = SyncV2Codec.utf8(SyncV2Codec.canonical(h))
        val derived = SyncV2Codec.hkdf(key, salt, SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/aead"), SyncV2Codec.sha(aad)))
        val bytes = try { "WRS2".toByteArray() + ByteBuffer.allocate(4).putInt(aad.size).array() + aad +
            SyncV2Codec.encrypt(derived, nonce, aad, encoded) } finally { derived.fill(0) }
        return Sealed("$type:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}", bytes, h)
    }
    fun open(identity: SyncV2Identity, key: ByteArray, oid: String, bytes: ByteArray, expectedVersion: Long? = null, stage:((String,Long)->Unit)?=null): ByteArray =
        open(identity.scope, { t, p -> identity.opaque(t, p) }, key, oid, bytes, expectedVersion,stage)
    internal fun open(scope: JSONObject, opaque: (String, ByteArray) -> String, key: ByteArray, oid: String,
        bytes: ByteArray, expectedVersion: Long? = null,stage:((String,Long)->Unit)?=null, lengthError:String="invalid_encoding"): ByteArray {
        val authAt=System.nanoTime()
        if(key.size!=32)SyncV2Codec.fail("authentication_required")
        if (bytes.size !in 24..266264 || !bytes.copyOfRange(0, 4).contentEquals("WRS2".toByteArray())) SyncV2Codec.fail("invalid_encoding")
        val length = ByteBuffer.wrap(bytes, 4, 4).int
        if (length !in 1..4096 || bytes.size < 8 + length + 16) SyncV2Codec.fail("invalid_encoding")
        val aad = bytes.copyOfRange(8, 8 + length)
        val h = SyncV2Wire.parse(aad, 4096, true); validate(h)
        val type = h.getString("type")
        SyncV2Wire.oid(oid, type)
        if (oid != "$type:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}") SyncV2Codec.fail("hash_mismatch")
        if (SyncV2Codec.scope(h.getJSONObject("scope")) != SyncV2Codec.scope(scope)) SyncV2Codec.fail("scope_mismatch")
        if (bytes.size != 8 + length + h.getInt("encodedBytes") + 16 || expectedVersion != null && h.getLong("keyVersion") != expectedVersion) SyncV2Codec.fail("invalid_schema")
        val derived = SyncV2Codec.hkdf(key, SyncV2Codec.decode64(h.getString("salt"), 32), SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/aead"), SyncV2Codec.sha(aad)))
        val encoded = try { SyncV2Codec.decrypt(derived, SyncV2Codec.decode64(h.getString("nonce"), 12), aad, bytes.copyOfRange(8 + length, bytes.size)) } finally { derived.fill(0) }
        stage?.invoke("authentication",System.nanoTime()-authAt)
        val decompressAt=System.nanoTime()
        val plain = if (h.getString("encoding") == "identity") encoded else gunzip(encoded, h.getInt("plainBytes"),lengthError)
        if(h.getString("encoding")=="gzip")stage?.invoke("decompression",System.nanoTime()-decompressAt)
        if (plain.size != h.getInt("plainBytes")) SyncV2Codec.fail(lengthError)
        if (opaque(type, plain) != h.getString("opaqueContentId")) SyncV2Codec.fail("fingerprint_mismatch")
        if (type != "blob") SyncV2Wire.parse(plain, 262144, true)
        return plain
    }
    private fun validate(h: JSONObject) {
        SyncV2Codec.exact(h, setOf("domain", "version", "scope", "keyVersion", "type", "opaqueContentId", "encoding", "plainBytes", "encodedBytes", "salt", "nonce"))
        if (h.get("domain") != "wrss.sync.v2/sealed" || h.get("version") != 2 || h.get("type") !in setOf("meta", "state", "blob", "recipe")) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.scope(h.getJSONObject("scope")); SyncV2Codec.unhex(SyncV2Wire.string(h, "opaqueContentId"), 32)
        if (SyncV2Wire.uint(h, "keyVersion", 4294967295) == 0L) SyncV2Codec.fail("invalid_schema")
        val p = SyncV2Wire.uint(h, "plainBytes", 262144); val e = SyncV2Wire.uint(h, "encodedBytes", 262144)
        when (h.get("encoding")) {
            "identity" -> if (p != e) SyncV2Codec.fail("invalid_schema")
            "gzip" -> if (p == 0L || e * 100 > p * 95) SyncV2Codec.fail("invalid_schema")
            else -> SyncV2Codec.fail("invalid_schema")
        }
        SyncV2Codec.decode64(SyncV2Wire.string(h, "salt"), 32); SyncV2Codec.decode64(SyncV2Wire.string(h, "nonce"), 12)
    }
    private fun gunzip(bytes: ByteArray, size: Int, lengthError:String="invalid_encoding"): ByteArray {
        fun bad(): Nothing = SyncV2Codec.fail("invalid_encoding")
        if (bytes.size < 18 || bytes[0] != 31.toByte() || bytes[1] != 139.toByte() || bytes[2] != 8.toByte()) bad()
        val flags = bytes[3].toInt() and 255; if (flags and 224 != 0) bad()
        var pos = 10
        fun u8(): Int { if (pos >= bytes.size - 8) bad(); return bytes[pos++].toInt() and 255 }
        if (flags and 4 != 0) { val len = u8() or (u8() shl 8); repeat(len) { u8() } }
        if (flags and 8 != 0) while (u8() != 0) { /* bounded by input */ }
        if (flags and 16 != 0) while (u8() != 0) { /* bounded by input */ }
        if (flags and 2 != 0) {
            val crc = CRC32().also { it.update(bytes, 0, pos) }.value.toInt() and 65535
            if ((u8() or (u8() shl 8)) != crc) bad()
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, pos, bytes.size - pos)
            val out = ByteArrayOutputStream(); val block = ByteArray(4096)
            while (!inflater.finished()) {
                val n = try { inflater.inflate(block) } catch (_: Exception) { bad() }
                if (n == 0 && !inflater.finished()) bad()
                if (out.size() + n > size) SyncV2Codec.fail(lengthError)
                out.write(block, 0, n)
            }
            val trailer = bytes.size - inflater.remaining
            if (trailer + 8 != bytes.size) bad() // one member; no concatenation or trailing bytes
            fun little(at: Int): Long = (0..3).fold(0L) { x, i -> x or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
            val plain = out.toByteArray()
            if (plain.size != size) SyncV2Codec.fail(lengthError)
            if (little(trailer + 4) != size.toLong() || little(trailer) != CRC32().also { it.update(plain) }.value) bad()
            return plain
        } finally { inflater.end() }
    }
}
