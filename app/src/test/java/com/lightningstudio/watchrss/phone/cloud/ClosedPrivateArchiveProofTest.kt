package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness
import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClosedPrivateArchiveProofTest {
    private val owner=CloudSnapshotReuse.Owner("phone","user","epoch","backend/user","generation")
    private fun manifest(proof:CloudExportFileWitness.PrivateObservation)=CloudSnapshotCodec().create(ByteArray(32),1,"phone",1,
        listOf(CloudLogicalObject("private-library.wrss",byteArrayOf(1))),snapshotSettings=owner.settings()
            .put(CloudSnapshotReuse.OWN_OBJECTS_SETTING,1).put("privateFilesRevision",proof.token)
            .put(CloudSnapshotReuse.PRIVATE_FILES_OBSERVATION,proof.json())).manifest

    @Test fun scopedClosedProofCarriesAfterRestartButRejectsNewWritesOrAnotherHead() {
        val root=Files.createTempDirectory("closed-private-proof-").toFile()
        try {
            val target=File(root,"imported_text/a.txt")
            CloudExportFileWitness.changing(target){}
            val observation=CloudExportFileWitness.observePrivate(root)
            val head=manifest(observation);val sha="a".repeat(64)
            val closed=ClosedPrivateArchiveProof(owner,head.snapshotId,sha,observation.token)
            assertEquals(closed,ClosedPrivateArchiveProof.decode(closed.encode()))
            val full=CloudExportFileWitness.observe(root)
            assertTrue(CloudExportFileWitness.complete(root,full,full.revision){true})
            CloudExportFileWitness.forgetProcessForTest(root)
            val restarted=CloudExportFileWitness.observePrivate(root)
            assertFalse(CloudSnapshotReuse.privateFilesProven(head,owner,restarted))
            assertTrue(CloudSnapshotReuse.privateFilesProven(head,owner,restarted,closed,sha))
            assertFalse(CloudSnapshotReuse.privateFilesProven(head,owner,restarted,closed,"b".repeat(64)))
            assertFalse(CloudSnapshotReuse.privateFilesProven(head,owner,restarted,closed.copy(headId="other-head"),sha))
            assertFalse(CloudSnapshotReuse.privateFilesProven(head,owner,restarted,closed.copy(owner=owner.copy(backendScope="other")),sha))
            CloudExportFileWitness.changing(target){}
            assertFalse(CloudSnapshotReuse.privateFilesProven(head,owner,CloudExportFileWitness.observePrivate(root),closed,sha))
        } finally {root.deleteRecursively()}
    }
    @Test fun closureIsAtomicWithCheckpointAndSurvivesOnlyOrdinaryCycleInvalidation() {
        val prefs=MemoryPreferences();val state=PhoneCloudStateStore(prefs)
        state.bindScope("backend","user")
        val headId="own-head";val sha="a".repeat(64)
        val closed=ClosedPrivateArchiveProof(owner,headId,sha,"0")
        val checkpoint=CloudCycleCheckpoint("context",mapOf(headId to sha),3,"4",10)
        val rejected=state.admitCycle();state.invalidateCycleCheckpoint()
        assertFalse(state.completeCycle(checkpoint,rejected,closed));assertNull(state.closedPrivateArchive())
        val admitted=state.admitCycle();prefs.failCommit=true
        assertTrue(runCatching{state.completeCycle(checkpoint,admitted,closed)}.isFailure)
        prefs.failCommit=false;assertNull(state.closedPrivateArchive())
        assertTrue(state.completeCycle(checkpoint,state.admitCycle(),closed))
        state.invalidateCycleCheckpoint()
        val reopened=PhoneCloudStateStore(prefs).also{it.bindScope("backend","user")}
        assertNull(reopened.cycleCheckpoint());assertEquals(closed,reopened.closedPrivateArchive())
        reopened.bindScope("other-backend","user");assertNull(reopened.closedPrivateArchive())
        reopened.bindScope("backend","other-user");assertNull(reopened.closedPrivateArchive())
        reopened.bindScope("backend","user");reopened.clearLibraryState();assertNull(reopened.closedPrivateArchive())
        assertTrue(reopened.completeCycle(checkpoint,reopened.admitCycle(),closed))
        reopened.clearScope();assertNull(reopened.closedPrivateArchive())
    }
}
