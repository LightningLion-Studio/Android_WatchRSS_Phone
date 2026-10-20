package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudRecordCodecTest {
    private val key = ByteArray(32) { it.toByte() }
    private val user = "record-fixture-user"
    private val value get() = JSONObject().put("articleId", "article-fixture").put("title", "你好 RSS")
        .put("url", "https://example.com/a").put("favoriteSaved", true).put("favoriteChangedAt", 1700000000000L)
    @Test fun decryptsMiniProgramFullDefaultsFixture() {
        val fixture = JSONObject(javaClass.getResource("/cloud/rss-record-js-v1.json")!!.readText())
        val result = PhoneCloudRecordCodec.decrypt(key, fixture.getString("userId"), fixture.getString("recordId"), fixture.getJSONObject("envelope"))
        assertEquals("article", result.first)
        assertEquals("你好 RSS", result.second.getString("title"))
        assertEquals(PhoneCloudRecordCodec.hash("article", value), PhoneCloudRecordCodec.hash("article", result.second))
    }
    @Test fun malformedDecryptedJsonNeverLeaksPlaintextInErrorOrCause() {
        val id=PhoneCloudRecordCodec.id(key,user,"article",value)
        val secret="known-secret-do-not-report"
        val nonce=ByteArray(12) { 3 }
        val derived=CloudSnapshotCodec.hkdfSha256(key,user.toByteArray(),"watchrss/cloud/record/v1".toByteArray(),32)
        val bytes=try {
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
                init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(derived,"AES"),javax.crypto.spec.GCMParameterSpec(128,nonce))
                updateAAD("watchrss-cloud-record-v1:$user:$id:1".toByteArray())
                doFinal(("{invalid:"+secret).toByteArray())
            }
        } finally { derived.fill(0) }
        val envelope=JSONObject().put("algorithm","A256GCM").put("keyVersion",1)
            .put("nonceBase64",java.util.Base64.getEncoder().encodeToString(nonce))
            .put("ciphertextBase64",java.util.Base64.getEncoder().encodeToString(bytes))
        val error=assertThrows(IllegalArgumentException::class.java) { PhoneCloudRecordCodec.decrypt(key,user,id,envelope) }
        assertEquals("cloud_record_invalid",error.message);assertNull(error.cause)
        assertFalse(error.toString().contains(secret))
    }
    @Test fun matchesIndependentNodeCryptoFixture() {
        val id = PhoneCloudRecordCodec.id(key, user, "article", value)
        assertEquals("218ef24506c44d0941adbdb19e548fda1a65ded3456d63efd53b4ca82e48ea80", id)
        val envelope = PhoneCloudRecordCodec.encrypt(key, user, id, 3, "article", value, ByteArray(12) { 7 })
        assertEquals("kKSwUYej9q5BaPBOmG4YxeifETBt6SzhJWGjwJttQcyYQmxRQLlf+phmxCfbMdJnD56KtkKPG8W6578JSLJLNdGtZtubGwKe+MfzYMVo/vbrKLQpFruarEPie/rS7xh4DuwUNs9vIFL1MUzPODp6QcW1ZR9cS7RVvS2HrYTwq3IT5xE5xd709hWU0le+6hD5EkHdrULqHDe/vO+/kjvrnlw5hXskp7EQB5C7uhn5n5uwv3m7eekKCAn/dt8QtklNJ7haJhuqWut6HwS5VJZSR+ent8citIO+R5FquzXPCQ==", envelope.getString("ciphertextBase64"))
    }
    @Test fun encryptedRoundTripPreservesUnicodeAndState() {
        val original = value
        val id = PhoneCloudRecordCodec.id(key, user, "article", original)
        val envelope = PhoneCloudRecordCodec.encrypt(key, user, id, 3, "article", original)
        val decoded = PhoneCloudRecordCodec.decrypt(key, user, id, envelope)
        assertEquals("article", decoded.first)
        assertEquals(PhoneCloudRecordCodec.canonical(original), PhoneCloudRecordCodec.canonical(decoded.second))
        assertFalse(envelope.toString().contains("你好"))
    }
    @Test fun canonicalHashIgnoresPropertyInsertionOrder() {
        val a = JSONObject().put("articleId", "a").put("favoriteSaved", true)
        val b = JSONObject().put("favoriteSaved", true).put("articleId", "a")
        assertEquals(PhoneCloudRecordCodec.hash("article", a), PhoneCloudRecordCodec.hash("article", b))
        b.put("favoriteSaved", false)
        assertNotEquals(PhoneCloudRecordCodec.hash("article", a), PhoneCloudRecordCodec.hash("article", b))
    }
    @Test fun ownerAndVersionAreAuthenticated() {
        val id = PhoneCloudRecordCodec.id(key, user, "article", value)
        val envelope = PhoneCloudRecordCodec.encrypt(key, user, id, 1, "article", value)
        assertThrows(Exception::class.java) { PhoneCloudRecordCodec.decrypt(key, "another-user", id, envelope) }
        envelope.put("keyVersion", 2)
        assertThrows(Exception::class.java) { PhoneCloudRecordCodec.decrypt(key, user, id, envelope) }
    }
    @Test fun excludesBodiesAndOversizeRecords() {
        val body = value.put("contentText", "secret book body")
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudRecordCodec.validate("article", body) }
        val huge = value.put("title", "x".repeat(65536))
        val id = PhoneCloudRecordCodec.id(key, user, "article", huge)
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudRecordCodec.encrypt(key, user, id, 1, "article", huge) }
    }
    @Test fun crossClientMetadataDefaultsAndProvenanceDoNotBounce() {
        val a = JSONObject().put("url", "https://example.com/feed").put("sourceDeviceId", "phone").put("createdAt", 12)
        val b = JSONObject(a.toString()).put("sourceDeviceId", "watch").put("createdAt", 99)
            .put("siteUrl", JSONObject.NULL).put("imageUrl", JSONObject.NULL).put("sortOrder", 0.0).put("isPinned", false)
        assertEquals(PhoneCloudRecordCodec.hash("source", a), PhoneCloudRecordCodec.hash("source", b))
        val back = JSONObject(b.toString()).put("sourceDeviceId", "phone").put("createdAt", 12)
        assertEquals(PhoneCloudRecordCodec.hash("source", a), PhoneCloudRecordCodec.hash("source", back))
    }
    @Test fun progressFloatRoundTripDoesNotEchoButBytePositionDoes() {
        val original = value.put("readingProgress", 0.2)
        val phoneFloat = JSONObject(original.toString()).put("readingProgress", 0.20000000298)
        assertEquals(PhoneCloudRecordCodec.hash("article", original), PhoneCloudRecordCodec.hash("article", phoneFloat))
        phoneFloat.put("readingPositionBytes", 500)
        assertNotEquals(PhoneCloudRecordCodec.hash("article", original), PhoneCloudRecordCodec.hash("article", phoneFloat))
    }
    @Test fun recordIdIsOwnerKindAndKeyScoped() {
        val id = PhoneCloudRecordCodec.id(key, user, "article", value)
        assertEquals(64, id.length)
        assertNotEquals(id, PhoneCloudRecordCodec.id(key, "different-owner", "article", value))
        assertNotEquals(id, PhoneCloudRecordCodec.id(ByteArray(32) { 1 }, user, "article", value))
        assertNotEquals(id, PhoneCloudRecordCodec.id(key, user, "source", JSONObject().put("url", "article-fixture")))
    }
}
