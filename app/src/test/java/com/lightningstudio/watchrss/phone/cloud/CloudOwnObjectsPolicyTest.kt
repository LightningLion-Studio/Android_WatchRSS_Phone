package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudOwnObjectsPolicyTest {
    private val owner=CloudSnapshotReuse.Owner("phone","user","epoch","backend/user","generation")
    private val codec=CloudSnapshotCodec()
    private val key=ByteArray(32)
    private fun head(device:String="phone")=codec.create(key,1,device,1,
        listOf(CloudLogicalObject("private-library.wrss","body".toByteArray(),false)),
        snapshotSettings=owner.settings().put(CloudSnapshotReuse.OWN_OBJECTS_SETTING,1)
            .put("snapshotContentLayout",3).put("privateBodyFingerprintV2","fp")
            .put("privateBodyArticleIds",JSONArray(listOf("article"))).put("privateBodyBookIds",JSONArray(listOf("book")))).manifest

    @Test fun copiedForeignSettingsCannotSupplyBodiesCompletionClaimsOrCarriedObjects() {
        val foreign=listOf(head("mini"),head("watch"),head("other-phone"))
        for(m in foreign)assertFalse(CloudSnapshotReuse.canCarry(m,owner,1,"fp"))
        assertTrue(CloudSnapshotReuse.completedArticleIds(foreign,owner,1,"fp").isEmpty())
        assertTrue(CloudSnapshotReuse.completedBookIds(foreign,owner,1,"fp").isEmpty())
        assertTrue(CloudSnapshotReuse.preserveObjects(foreign,emptySet(),emptyList(),owner).isEmpty())
        val own=head()
        assertTrue(CloudSnapshotReuse.canCarry(own,owner,1,"fp"))
        assertEquals(setOf("article"),CloudSnapshotReuse.completedArticleIds(foreign+own,owner,1,"fp"))
        assertEquals(setOf("book"),CloudSnapshotReuse.completedBookIds(foreign+own,owner,1,"fp"))
        assertEquals(own.objects,CloudSnapshotReuse.preserveObjects(foreign+own,emptySet(),emptyList(),owner))
    }

    @Test fun everyScopeFieldAndOwnershipVersionAreRequiredForTrustedClaims() {
        val own=head()
        for(field in listOf("ownerUserId","libraryEpoch","backendScope","contentGeneration",CloudSnapshotReuse.OWN_OBJECTS_SETTING)) {
            for(missing in listOf(false,true)) {
                val settings=JSONObject(own.settings.toString())
                if(missing)settings.remove(field) else settings.put(field,"other")
                val candidate=own.copy(settings=settings)
                assertFalse("$field/$missing",CloudSnapshotReuse.canCarry(candidate,owner,1,"fp"))
                assertTrue(CloudSnapshotReuse.completedArticleIds(listOf(candidate),owner,1,"fp").isEmpty())
                assertTrue(CloudSnapshotReuse.completedBookIds(listOf(candidate),owner,1,"fp").isEmpty())
                assertTrue(CloudSnapshotReuse.preserveObjects(listOf(candidate),emptySet(),emptyList(),owner).isEmpty())
            }
        }
        assertFalse(CloudSnapshotReuse.canCarry(own,owner,2,"fp"))
        assertFalse(CloudSnapshotReuse.canCarry(own,owner,1,"changed"))
    }

    @Test fun foreignUnknownDescriptorsDoNotConflictButDifferentOwnDescriptorsStillDo() {
        fun unknown(device:String,text:String)=head(device).copy(objects=listOf(codec.create(key,1,device,1,
            listOf(CloudLogicalObject("future.bin",text.toByteArray(),false))).manifest.objects.single()))
        assertTrue(CloudSnapshotReuse.preserveObjects(listOf(unknown("mini","m"),unknown("watch","w")),emptySet(),emptyList(),owner).isEmpty())
        val error=runCatching { CloudSnapshotReuse.preserveObjects(listOf(unknown("phone","a"),unknown("phone","b")),emptySet(),emptyList(),owner) }.exceptionOrNull()
        assertEquals("unknown_object_conflicting_heads",(error as CloudFormatUpgradeRequired).reason)
        assertEquals("unknown_own_object",(runCatching{CloudSnapshotReuse.requireOwnPublicationWritable(unknown("phone","a"))}.exceptionOrNull() as CloudFormatUpgradeRequired).reason)
    }

    @Test fun futureOwnVersionAndObjectFeaturesStopPublication() {
        val own=head()
        assertThrows(CloudFormatUpgradeRequired::class.java) {
            CloudSnapshotReuse.requireOwnPublicationWritable(own.copy(settings=JSONObject(own.settings.toString()).put(CloudSnapshotReuse.OWN_OBJECTS_SETTING,2)))
        }
        val descriptor=own.objects.single()
        val future=descriptor.copy(rawDocument=CloudRawDocument.parse(descriptor.toRawString())
            .patch(listOf("features"),"{\"incompat\":[\"future.body.v1\"]}"))
        assertThrows(CloudFormatUpgradeRequired::class.java) { CloudSnapshotReuse.requireOwnPublicationWritable(own.copy(objects=listOf(future))) }
    }
}
