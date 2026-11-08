package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Local projection codec. No network, account creation, key rotation or business apply entry point. */
internal object SyncV2Codec {
    val maxD64 = BigInteger("18446744073709551615")
    private val decimal = Regex("0|[1-9][0-9]{0,19}")
    val kinds = setOf("article", "book", "note", "source", "rss-state", "book-reading", "asset", "folder")
    fun fail(code: String): Nothing = throw IllegalStateException(code)
    fun d64(value: String): BigInteger {
        if (!decimal.matches(value)) fail("invalid_schema")
        return BigInteger(value).also { if (it > maxD64) fail("invalid_schema") }
    }
    fun next(value: String): String = d64(value).let {
        if (it == maxD64) fail("counter_exhausted")
        (it + BigInteger.ONE).toString()
    }
    fun utf8(text: String): ByteArray = try {
        val b = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        ByteArray(b.remaining()).also { b.get(it) }
    } catch (_: Exception) { fail("invalid_encoding") }
    fun text(value: String, limit: Int): String = value.also { if (utf8(it).size > limit) fail("invalid_schema") }
    private const val hexDigits="0123456789abcdef"
    fun hex(bytes: ByteArray) = SyncV2LocalProfile.time("hex",bytes.size.toLong()){
        val chars=CharArray(bytes.size*2)
        for(i in bytes.indices){val b=bytes[i].toInt() and 255;chars[2*i]=hexDigits[b ushr 4];chars[2*i+1]=hexDigits[b and 15]}
        String(chars)
    }
    fun unhex(value: String, size: Int): ByteArray {
        if (!Regex("[0-9a-f]{${size * 2}}").matches(value)) fail("invalid_encoding")
        return ByteArray(size) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun sha(bytes: ByteArray,purpose:String="sha256") = SyncV2LocalProfile.time(purpose,bytes.size.toLong()){MessageDigest.getInstance("SHA-256").digest(bytes)}
    fun hash(value: Any?) = hex(sha(utf8(canonical(value))))
    fun lp(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { data -> parts.forEach { data.writeInt(it.size); data.write(it) } }
    }.toByteArray()
    fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    }
    fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray) = hmac(hmac(salt, key), info + byteArrayOf(1))
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode64(value: String, size: Int? = null): ByteArray = try {
        Base64.getUrlDecoder().decode(value).also {
            if (b64(it) != value || (size != null && it.size != size)) fail("invalid_encoding")
        }
    } catch (_: IllegalArgumentException) { fail("invalid_encoding") }
    fun exact(obj: JSONObject, keys: Set<String>) {
        if (obj.keys().asSequence().toSet() != keys) fail("invalid_schema")
    }
    fun canonical(value: Any?): String = SyncV2LocalProfile.time("json.canonical"){canonicalValue(value)}
    private fun canonicalValue(value:Any?):String=when(value) {
        null, JSONObject.NULL -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().also {
            if (it !in -9_007_199_254_740_991L..9_007_199_254_740_991L) fail("invalid_encoding")
        }.toString()
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            quote(it) + ":" + canonicalValue(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalValue(value.get(it)) }
        else -> fail("invalid_encoding")
    }
    internal fun quotePublic(value: String): String = quote(value)
    private fun quote(value: String): String {
        utf8(value)
        return buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
                '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\u000c' -> append("\\f")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
    }
    fun scope(value: JSONObject): String {
        exact(value, setOf("repoId", "libraryEpoch", "contentGeneration"))
        unhex(SyncV2Wire.string(value,"repoId"), 16)
        for (field in listOf("libraryEpoch", "contentGeneration")) if (d64(SyncV2Wire.string(value,field)) < BigInteger.ONE) fail("invalid_schema")
        return canonical(value)
    }
    fun entity(key: ByteArray, repo: String, kind: String, logicalId: String): String {
        if (key.size != 32 || kind !in kinds || utf8(logicalId).isEmpty()) fail("invalid_schema")
        unhex(repo, 16); text(logicalId, 1024)
        return hex(hmac(key, lp(utf8("wrss.sync.v2/entity"), utf8(repo), utf8(kind), utf8(logicalId))))
    }
    fun opaque(key: ByteArray, scope: JSONObject, type: String, bytes: ByteArray): String =
        hex(hmac(key, lp(utf8("wrss.sync.v2/content"), utf8(scope(scope)), utf8(type), sha(bytes))))
    fun oid(type: String, value: Any?) = "$type:sha256:${hash(value)}"
    fun commitOid(value: String) { if (!Regex("commit:sha256:[a-f0-9]{64}").matches(value)) fail("invalid_schema") }
    fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, bytes: ByteArray): ByteArray =
        SyncV2LocalProfile.time("aead",bytes.size.toLong()){Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(bytes)
        }}
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, bytes: ByteArray): ByteArray = try {
        SyncV2LocalProfile.time("aead",bytes.size.toLong()){Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(bytes)
        }}
    } catch (_: Exception) { fail("aead_invalid") }

    /** Independent device-local key, purpose and scope, never the content/identity key. */
    fun sealLocal(key: ByteArray, scope: String, purpose: String, value: Any?): ByteArray {
        require(key.size == 32)
        val nonce = random(12)
        return nonce + encrypt(key, nonce, utf8("wrss.sync.v2/local/$purpose/$scope"), utf8(canonical(value)))
    }
    fun openLocal(key: ByteArray, scope: String, purpose: String, bytes: ByteArray): String {
        if (bytes.size < 28) fail("aead_invalid")
        return decrypt(key, bytes.copyOfRange(0, 12), utf8("wrss.sync.v2/local/$purpose/$scope"), bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}

/** Kotlin final value class: no subclasses, mutable fields, getters or extra own properties. */
internal data class ChoiceCapture(
    val stateVersion: String, val accountOid: String, val accountRevision: String,
    val generation: String, val localChangeRevision: String
) {
    fun json() = JSONObject().put("stateVersion", stateVersion).put("accountOid", accountOid)
        .put("accountRevision", accountRevision).put("generation", generation).put("localChangeRevision", localChangeRevision)
    companion object {
        // Restore the five original values; never fill from current account or pendingChoice.
        fun restore(value: JSONObject): ChoiceCapture {
            try {
                SyncV2Codec.exact(value, setOf("stateVersion", "accountOid", "accountRevision", "generation", "localChangeRevision"))
                val fields = listOf("stateVersion", "accountOid", "accountRevision", "generation", "localChangeRevision")
                if (fields.any { value.get(it) !is String }) SyncV2Codec.fail("choice_stale")
                val result = ChoiceCapture(value.getString("stateVersion"), value.getString("accountOid"), value.getString("accountRevision"), value.getString("generation"), value.getString("localChangeRevision"))
                listOf(result.stateVersion, result.accountRevision, result.generation, result.localChangeRevision).forEach(SyncV2Codec::d64)
                SyncV2Codec.commitOid(result.accountOid)
                return result
            } catch (_: Exception) { SyncV2Codec.fail("choice_stale") }
        }
    }
}
