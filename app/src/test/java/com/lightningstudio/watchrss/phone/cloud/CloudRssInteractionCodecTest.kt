package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudRssInteractionCodecTest {
    private val key=ByteArray(32){it.toByte()}
    private fun state()=CloudContentPolicy.rssState(JSONObject().put("articleId","stable-public-id").put("readingPositionChangedAt",100).put("isRead",true).put("sourceDeviceId","phone"),"resolved-source")
    @Test fun newKindUsesBoundSourceAndArticleHashWithoutLeakingPublicIndex() {
        val value=state();val id=PhoneCloudRecordCodec.id(key,"owner","rss-state",value)
        val env=PhoneCloudRecordCodec.encrypt(key,"owner",id,1,"rss-state",value)
        val decoded=PhoneCloudRecordCodec.decrypt(key,"owner",id,env)
        assertEquals("rss-state",decoded.first);assertEquals(PhoneCloudRecordCodec.canonical(value),PhoneCloudRecordCodec.canonical(decoded.second))
        assertFalse(value.has("title"));assertFalse(value.has("url"));assertFalse(value.has("excerpt"))
        assertNotEquals(id,PhoneCloudRecordCodec.id(key,"owner","rss-state",JSONObject(value.toString()).put("sourceId","another")))
        assertThrows(javax.crypto.AEADBadTagException::class.java) { PhoneCloudRecordCodec.decrypt(key,"owner","0".repeat(64),env) }
    }
    @Test fun hashIgnoresProvenanceAndNormalizesProgressButRetainsUserState() {
        val a=state().put("readingProgress",.12345601)
        val b=JSONObject(a.toString()).put("sourceDeviceId","other").put("readingProgress",.12345602)
        assertEquals(PhoneCloudRecordCodec.hash("rss-state",a),PhoneCloudRecordCodec.hash("rss-state",b))
        b.put("isRead",false);assertNotEquals(PhoneCloudRecordCodec.hash("rss-state",a),PhoneCloudRecordCodec.hash("rss-state",b))
    }
    @Test fun unknownPublicMetadataOrBodyCannotEnterInteractionRecord() {
        for(field in listOf("title","excerpt","contentText","url","updatedAt")) assertThrows(IllegalArgumentException::class.java) {
            PhoneCloudRecordCodec.validate("rss-state",state().put(field,"forbidden"))
        }
    }
    @Test fun readingLwwIncludesProvenanceAndFalseWinsExactTie() {
        val a=state();val b=JSONObject(a.toString()).put("isRead",false)
        assertFalse(CloudRssInteraction.merge(a,b).getBoolean("isRead"))
        assertFalse(CloudRssInteraction.merge(b,a).getBoolean("isRead"))
        val newer=JSONObject(a.toString()).put("sourceDeviceId","watch")
        assertTrue(CloudRssInteraction.merge(b,newer).getBoolean("isRead"))
        val older=JSONObject(b.toString()).put("readingPositionChangedAt",99)
        assertTrue(CloudRssInteraction.merge(a,older).getBoolean("isRead"))
    }
    @Test fun sharedS8VectorMatchesIndependentHkdfHmacAesGcmImplementation() {
        val vector=JSONObject(requireNotNull(javaClass.getResource("/cloud/rss-state-v1.json")).readText())
        val fixtureKey=vector.getString("accountKeyHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val owner=vector.getString("userId");val value=vector.getJSONObject("value");val id=vector.getString("recordId")
        assertEquals(id,PhoneCloudRecordCodec.id(fixtureKey,owner,"rss-state",value))
        val expected=vector.getJSONObject("envelope")
        val actual=PhoneCloudRecordCodec.encrypt(fixtureKey,owner,id,1,"rss-state",value,nonce=java.util.Base64.getDecoder().decode(expected.getString("nonceBase64")))
        assertEquals(PhoneCloudRecordCodec.canonical(expected),PhoneCloudRecordCodec.canonical(actual))
        val decoded=PhoneCloudRecordCodec.decrypt(fixtureKey,owner,id,expected)
        assertEquals(PhoneCloudRecordCodec.canonical(value),PhoneCloudRecordCodec.canonical(decoded.second))
    }

    @Test fun sharedAnchorVectorUsesFirstKeyForIdentityAndCurrentKeyForEncryption() {
        val v=JSONObject(requireNotNull(javaClass.getResource("/cloud/rss-state-anchor-v1.json")).readText())
        fun bytes(field:String)=v.getString(field).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val anchor=bytes("anchorKeyHex");val content=bytes("contentKeyHex");val nonce=bytes("nonceHex")
        val user=v.getString("userId");val id=v.getString("recordId");val value=v.getJSONObject("value");val version=v.getInt("keyVersion")
        assertEquals(id,PhoneCloudRecordCodec.id(anchor,user,"rss-state",value))
        assertNotEquals(id,PhoneCloudRecordCodec.id(content,user,"rss-state",value))
        val env=PhoneCloudRecordCodec.encrypt(content,user,id,version,"rss-state",value,nonce=nonce,idKey=anchor)
        assertEquals(PhoneCloudRecordCodec.canonical(v.getJSONObject("envelope")),PhoneCloudRecordCodec.canonical(env))
        val decoded=PhoneCloudRecordCodec.decrypt(content,user,id,env,listOf(anchor,content))
        assertEquals(PhoneCloudRecordCodec.canonical(value),PhoneCloudRecordCodec.canonical(decoded.second))
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudRecordCodec.decrypt(content,user,id,env,listOf(content)) }
    }

}
