package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class CloudPrivateFileProofTest {
    private val owner=CloudSnapshotReuse.Owner("phone","user","epoch","backend/user","generation")
    private fun manifest(proof:CloudExportFileWitness.PrivateObservation?)=CloudSnapshotCodec().create(ByteArray(32),1,"phone",1,
        listOf(CloudLogicalObject("private-library.wrss",byteArrayOf(1))),snapshotSettings=owner.settings()
            .put(CloudSnapshotReuse.OWN_OBJECTS_SETTING,1).also{if(proof!=null)it.put(CloudSnapshotReuse.PRIVATE_FILES_OBSERVATION,proof.json())}).manifest

    @Test fun sameTokenAndAnotherScopeConsumptionCannotProveAnOlderOwnHead() {
        val root=Files.createTempDirectory("private-proof-scope-").toFile()
        try {
            val target=File(root,"imported_text/a.txt")
            CloudExportFileWitness.changing(target){}
            val early=CloudExportFileWitness.observePrivate(root);val oldHead=manifest(early)
            CloudExportFileWitness.changing(target){}
            val late=CloudExportFileWitness.observePrivate(root)
            assertEquals(early.token,late.token)
            val current=CloudExportFileWitness.observe(root)
            assertTrue(CloudExportFileWitness.complete(root,current,current.revision){true}) // another scope's receipt
            assertFalse(CloudExportFileWitness.privateFilesPending(root))
            assertFalse(CloudSnapshotReuse.privateFilesProven(oldHead,owner,late))
            assertTrue(CloudSnapshotReuse.privateFilesProven(manifest(late),owner,late))
            assertFalse(CloudSnapshotReuse.privateFilesProven(manifest(late),owner.copy(backendScope="other/user"),late))
            assertFalse(CloudSnapshotReuse.privateFilesProven(manifest(null),owner,late))
        } finally {root.deleteRecursively()}
    }
    @Test fun lostVolatileSequenceAndUnknownProofVersionAlwaysRequireRebuild() {
        val root=Files.createTempDirectory("private-proof-restart-").toFile()
        try {
            val before=CloudExportFileWitness.observePrivate(root);val head=manifest(before)
            CloudExportFileWitness.forgetProcessForTest(root)
            val after=CloudExportFileWitness.observePrivate(root)
            assertEquals(before.token,after.token);assertEquals(before.installs,after.installs)
            assertNotEquals(before.incarnation,after.incarnation)
            assertFalse(CloudSnapshotReuse.privateFilesProven(head,owner,after))
            val unsupported=manifest(after).copy(settings=JSONObject(manifest(after).settings.toString()).put(
                CloudSnapshotReuse.PRIVATE_FILES_OBSERVATION,after.json().put("version",2)))
            assertFalse(CloudSnapshotReuse.privateFilesProven(unsupported,owner,after))
        } finally {root.deleteRecursively()}
    }
}
