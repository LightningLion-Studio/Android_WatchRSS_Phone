package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import org.junit.Assert.*
import org.junit.Test

class CloudScopeAndSetupV2Test {
    @Test fun logoutRevocationIsBestEffortAndAlwaysDiscardsDeviceAuthorization() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()
        releaseCloudDevice({ events += "revoke"; throw java.io.IOException("offline") }, { events += "clear-private-key" })
        assertEquals(listOf("revoke","clear-private-key"),events)
        events.clear()
        releaseCloudDevice({ events += "revoke" }, { events += "clear-private-key" })
        assertEquals(listOf("revoke","clear-private-key"),events)
    }
    @Test fun r2RotationReusesOneEntropyAndRestoresTheWholeCommitmentChain() {
        val entropy = ByteArray(32) { 7 }; val words = RecoveryWords.encode(entropy)
        val keys = (1..3).associateWith { v -> ByteArray(32) { v.toByte() } }
        val envelopes = keys.map { (v,k) -> CloudKeyEnvelopeCodec.createRecoveryEnvelope(k,"owner",v,entropy) }
        val decoded = RecoveryWords.decode(words)
        val restored = envelopes.associate { it.keyVersion to CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(it,"owner",decoded) }
        val rows = keys.map { (v,k) ->
            val commitment = CloudProtocolV2.commitment(k,"owner","epoch",v)
            CloudKeyCommitment(v,commitment,if(v==1)null else CloudProtocolV2.rotationProof(keys.getValue(v-1),"owner","epoch",v,commitment))
        }
        CloudKeyTrust.validate("owner","epoch",0,rows,restored)
        keys.forEach { (v,k) -> assertArrayEquals(k,restored[v]) }
    }
    @Test fun firstDeviceRecoveryDraftsAreFreshAndRemainEphemeral() {
        val manager = CloudKeyManager(null,preferencesOverride=MemoryPreferences())
        val old = manager.createRecoverySetup("owner",keyVersion=1)
        val next = manager.createRecoverySetup("owner",keyVersion=2)
        assertNotEquals(old.words,next.words)
        try { CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(next.envelope,"owner",RecoveryWords.decode(old.words)); fail("old words reused") }
        catch (_: javax.crypto.AEADBadTagException) { }
        assertFalse(manager.hasAccountKey("owner"))
    }
    @Test fun restoredEncryptedStorageResetsOwnXmlAndPersistentKeystoreFailureDisablesKeys() {
        val prefs = MemoryPreferences(); var creates = 0; var resets = 0
        assertSame(prefs, recoverCloudKeyPreferences({ if (++creates == 1) error("restored XML"); prefs }, { resets++ }))
        assertEquals(2, creates); assertEquals(1, resets)
        assertNull(recoverCloudKeyPreferences({ error("Keystore unavailable") }, { resets++ }))
        assertEquals(2, resets)
    }
    @Test fun accountAndBackendStatesNeverShareHeadsCursorsOrKeys() {
        val prefs=MemoryPreferences();val state=PhoneCloudStateStore(prefs)
        state.bindScope("https://a/","owner");state.libraryEpoch="e1";state.nextSequence(20);state.markApplied("device",9,true);state.markUploaded("h",mapOf("device" to "head"),true)
        state.bindScope("https://a","other");assertNull(state.libraryEpoch);assertEquals(0,state.appliedSequence("device",true));assertEquals(1,state.nextSequence(0));assertNull(state.lastParentHeads())
        state.bindScope("https://b","owner");assertNull(state.libraryEpoch);assertNull(state.lastContentHash(true))
        state.bindScope("https://a","owner");assertEquals("e1",state.libraryEpoch);assertEquals(9,state.appliedSequence("device",true));assertEquals(22,state.nextSequence(0));assertEquals("h",state.lastContentHash(true))
        val keys=CloudKeyManager(null,preferencesOverride=MemoryPreferences())
        keys.bindBackend("https://a");keys.saveVerifiedKeys("owner",mapOf(1 to ByteArray(32){1}))
        keys.bindBackend("https://b");assertFalse(keys.hasAccountKey("owner"))
        keys.bindBackend("https://a/");assertTrue(keys.hasAccountKey("owner"));assertFalse(keys.hasAccountKey("other"))
    }
    @Test fun epochChangeClearsOnlyCloudScopeAndOwnershipNeedsExplicitDecision() {
        val state=PhoneCloudStateStore(MemoryPreferences());state.bindScope("backend","a");state.claimLegacyOwner("a")
        state.observeEpoch("old"){};state.quarantineKey(8,"untrusted");assertEquals(setOf(8),state.quarantinedKeyVersions());state.nextSequence(42);state.markApplied("watch",12,true)
        var cleared=0
        assertTrue(state.observeEpoch("new") { state.clearScope();cleared++ })
        assertEquals(1,cleared);assertTrue(state.quarantinedKeyVersions().isEmpty());assertEquals("new",state.libraryEpoch);assertEquals(0,state.appliedSequence("watch",true));assertEquals(1,state.nextSequence(0))
        state.claimLegacyOwner("b");assertEquals("a",state.ownerUserId)
        try { state.requireUploadOwner("b");fail("implicit cross-account upload") } catch (_:IllegalStateException) { }
        state.ownerUserId="b";state.requireUploadOwner("b")
        state.clearScope();assertEquals("b",state.ownerUserId)
    }
    @Test fun preparingRecoveryNeverPersistsAnOrphanKeyAndValidatedKeySetCommitsTogether() {
        val prefs=MemoryPreferences();val manager=CloudKeyManager(null,preferencesOverride=prefs);manager.bindBackend("backend")
        val setup=manager.createRecoverySetup("owner",keyVersion=8)
        assertEquals(24,setup.words.size);assertEquals(8,setup.envelope.keyVersion);assertFalse(manager.hasAccountKey("owner"))
        val entropy=RecoveryWords.decode(setup.words)
        assertArrayEquals(setup.pendingKey,CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(setup.envelope,"owner",entropy))
        prefs.failCommit=true
        try { manager.saveVerifiedKeys("owner",mapOf(8 to setup.pendingKey,9 to ByteArray(32){9}));fail("commit failure") } catch (_:IllegalStateException) { }
        prefs.failCommit=false;assertFalse(manager.hasAccountKey("owner"))
        manager.saveVerifiedKeys("owner",mapOf(8 to setup.pendingKey,9 to ByteArray(32){9}))
        assertEquals(listOf(8,9),manager.keyVersions("owner"));assertEquals(9,manager.currentKeyVersion("owner"))
        manager.clearAccountKeys("owner");assertFalse(manager.hasAccountKey("owner"))
    }
}
