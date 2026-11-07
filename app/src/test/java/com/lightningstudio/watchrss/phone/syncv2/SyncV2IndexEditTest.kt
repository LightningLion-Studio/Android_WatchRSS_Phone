package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class SyncV2IndexEditTest {
    private val scope=SyncV2Fixtures.identity().scope
    private fun row(n:Int)=JSONObject().put("entityKey",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("entity-$n"))))
        .put("metaOid","meta:sha256:"+"1".repeat(64)).put("stateOid","state:sha256:"+"2".repeat(64)).put("addedAtMillis",0).put("rowSequence","1").put("deleted",false)
        .put("bodyRevision",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray())
    @Test fun singleEditReadsOnlyItsPathAndMatchesTheCanonicalWholeTree() {
        val rows=(0 until 12500).map(::row);val original=SyncV2Index.build(scope,rows);val target=JSONObject(rows[17].toString()).put("rowSequence","2")
        var reads=0;val edit=SyncV2Index.edit(scope,original.root,mapOf(target.getString("entityKey") to target)){oid->reads++;original.objects.getValue(oid)}
        val expected=SyncV2Index.build(scope,rows.map{if(it.getString("entityKey")==target.getString("entityKey"))target else it})
        assertEquals(expected.root,edit.tree.root);assertTrue("tree pages $reads",reads<=4)
        val available=original.objects+edit.tree.objects;assertEquals(12500,SyncV2Index.read(scope,edit.tree.root,available::getValue).size)
        assertEquals("2",SyncV2Index.find(scope,edit.tree.root,target.getString("entityKey"),available::getValue)!!.getString("rowSequence"))
        for(oid in edit.retired)assertFalse(oid in expected.objects)
    }
    @Test fun deletionsCollapseCanonicalNodesAndEmptyRootsWithoutDroppingReusedPages() {
        val rows=(0 until 800).map(::row);val original=SyncV2Index.build(scope,rows)
        for(keep in listOf(240,1,0)) {
            val edit=SyncV2Index.edit(scope,original.root,rows.drop(keep).associate{it.getString("entityKey") to null},original.objects::getValue)
            val expected=SyncV2Index.build(scope,rows.take(keep));assertEquals(expected.root,edit.tree.root)
            assertEquals(keep,SyncV2Index.read(scope,edit.tree.root,(original.objects+edit.tree.objects)::getValue).size)
        }
    }
    @Test fun noOpAndTamperedPathCannotReplaceAnAuthenticatedRoot() {
        val rows=(0 until 300).map(::row);val original=SyncV2Index.build(scope,rows);val first=rows.first()
        val same=SyncV2Index.edit(scope,original.root,mapOf(first.getString("entityKey") to first),original.objects::getValue)
        assertEquals(original.root,same.tree.root);assertTrue(same.tree.objects.isEmpty());assertTrue(same.retired.isEmpty())
        assertNotNull(runCatching{SyncV2Index.edit(scope,original.root,mapOf(first.getString("entityKey") to JSONObject(first.toString()).put("rowSequence","2"))){byteArrayOf(1,2,3)}}.exceptionOrNull())
    }
}
