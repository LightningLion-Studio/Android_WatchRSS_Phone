package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P3PolicyTest {
    @Test fun fixedPriorityVectorsUseAuthenticatedLocalClassificationAndDoNotReplaceCompletedRecentSlots() {
        val v=SyncV2Fixtures.vector("priority");val cases=v.getJSONArray("cases")
        for(n in 0 until cases.length()) {
            val c=cases.getJSONObject(n);val values=c.getJSONArray("items")
            val items=(0 until values.length()).map { i -> val r=values.getJSONObject(i);val d=r.optJSONObject("demand")
                SyncV2ContentPriority.Item(r.getString("entityKey"),r.getString("kind"),r.getLong("addedAtMillis"),r.optBoolean("importedFile"),r.optBoolean("complete"),parentKind=if(r.has("parentKind"))r.getString("parentKind")else null,demandAtMillis=d?.getLong("demandAtMillis"),demandExpiresAtMillis=d?.getLong("expiresAtMillis"))
            }
            val actual=JSONArray(SyncV2ContentPriority.order(items,c.getLong("now")).map{JSONObject().put("entityKey",it.first.entityKey).put("rank",it.second)})
            assertEquals(SyncV2Codec.canonical(c.getJSONArray("expected")),SyncV2Codec.canonical(actual))
        }
    }
    @Test fun fixedTransportGateVectorsRequireConfirmedEvidenceAndDefaultCellularSetting() {
        val cases=SyncV2Fixtures.vector("transport-gate").getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val r=c.getJSONObject("input")
            fun flag(k:String):Boolean?=if(r.isNull(k))null else r.getBoolean(k)
            val signals=SyncV2TransportSignals(flag("charging"),flag("wifi"),r.getBoolean("bluetooth"),flag("internet"),flag("cellular"),r.getBoolean("localRoute"))
            assertEquals(SyncV2Codec.canonical(c.getJSONObject("expected")),SyncV2Codec.canonical(signals.eligible(r.optBoolean("allowChargingCellular",true))))
        }
        assertFalse(SyncV2TransportSignals(charging=true,internet=true).eligible(false).getBoolean("cloudAllowed"))
    }
    @Test fun fixedEightToOneFairnessSurvivesReopenAndRotatesOrdinaryRanks() {
        val expected=SyncV2Fixtures.vector("priority").getJSONObject("fairness").getJSONArray("expectedRanks")
        var state=SyncV2ContentPriority.Fairness()
        for(i in 0 until expected.length()) {
            val actual=state.choose(setOf(0,1,2,3,4));assertEquals(expected.getInt(i),actual)
            state.completed(actual!!);state=SyncV2ContentPriority.Fairness(state.demanded,state.nextOrdinary)
        }
        assertEquals(1,SyncV2ContentPriority.Fairness().choose(setOf(1,2,3,4)))
        assertEquals(4,SyncV2ContentPriority.Fairness(8,3).choose(setOf(0,1,4)))
    }
    private fun recipe(bytes:Int=1)=JSONObject().put("version",2).put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("opaqueContentId","33".repeat(32))
        .put("totalPlainBytes",bytes).put("plaintextSha256","44".repeat(32)).put("blocks",JSONArray().put(JSONObject().put("index",0).put("oid","blob:sha256:"+"55".repeat(32)).put("plainBytes",bytes).put("opaqueContentId","66".repeat(32)))).put("ranges",JSONArray())
    private fun error(code:String,fn:()->Unit) {try{fn();fail("accepted invalid input")}catch(e:IllegalStateException){assertEquals(code,e.message)}}
    @Test fun recipeRejectsBlockRangeAndActualJsonLimitsWithoutChangingInput() {
        val original=recipe();SyncV2ContentCodec.recipe(original)
        val blocks=JSONArray();repeat(2049){blocks.put(JSONObject(original.getJSONArray("blocks").getJSONObject(0).toString()).put("index",it).put("plainBytes",262144))}
        error("object_too_large"){SyncV2ContentCodec.recipe(recipe().put("blocks",blocks))}
        val ranges=JSONArray();repeat(100001){ranges.put(JSONObject().put("logicalPart",it.toString()).put("offset",0).put("length",0))}
        error("object_too_large"){SyncV2ContentCodec.recipe(recipe().put("ranges",ranges))}
        val oversized=JSONArray();repeat(300){oversized.put(JSONObject().put("logicalPart",it.toString().padStart(4,'0')+"x".repeat(1000)).put("offset",0).put("length",0))}
        error("object_too_large"){SyncV2ContentCodec.recipe(recipe().put("ranges",oversized))}
        val duplicate=JSONArray().put(JSONObject().put("logicalPart","x").put("offset",0).put("length",1)).put(JSONObject().put("logicalPart","x").put("offset",0).put("length",0))
        error("invalid_schema"){SyncV2ContentCodec.recipe(recipe().put("ranges",duplicate))}
        error("invalid_schema"){SyncV2ContentCodec.recipe(recipe().put("totalPlainBytes",2))}
        assertEquals(1,original.getLong("totalPlainBytes"))
    }
    @Test fun c7LongNotesRemainUnsupportedWithoutMutatingOriginalEntity() {
        val resource=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
        val note=(0 until resource.length()).map{resource.getJSONObject(it)}.first{it.getString("kind")=="note"}.getJSONObject("value")
        note.put("markdown","x".repeat(140000)).put("plainText","x".repeat(140000))
        val original=SyncV2Codec.canonical(note)
        error("object_too_large"){SyncV2Business.split("note",note.getString("noteId"),"aa".repeat(32),note,0)}
        assertEquals(original,SyncV2Codec.canonical(note))
        note.put("markdown","x".repeat(262145))
        error("object_too_large"){SyncV2Business.split("note",note.getString("noteId"),"aa".repeat(32),note,0)}
        assertEquals(262145,note.getString("markdown").length)
    }
}
