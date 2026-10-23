package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.testing.MemoryPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudLegacyStorageRegressionTest {
    private val user = "synthetic-owner"
    private val backend = "https://test.invalid"
    private val key = ByteArray(32) { 7 }
    private val oldHash = CloudSnapshotCodec.sha256(user.toByteArray()).take(24)
    private val oldName = "account_key_$oldHash"
    private fun legacy(prefs: MemoryPreferences, versioned: Boolean = false): CloudKeyManager {
        val editor = prefs.edit()
        if (versioned) editor.putString("${oldName}_v1", key.base64()).putInt("account_key_current_$oldHash", 1)
        else editor.putString(oldName, key.base64())
        check(editor.commit())
        return CloudKeyManager(null, preferencesOverride = prefs).also { it.bindBackend(backend) }
    }

    @Test fun repeatedLegacyReadsStageOnceAndPreserveOriginalUntilServerVerification() {
        for (versioned in listOf(false, true)) {
            val prefs = MemoryPreferences(); val manager = legacy(prefs, versioned)
            assertTrue(manager.hasAccountKey(user))
            val writes = prefs.commitCount
            repeat(1000) {
                assertTrue(manager.hasAccountKey(user))
                assertEquals(listOf(1), manager.keyVersions(user))
                assertEquals(1, manager.currentKeyVersion(user))
                assertArrayEquals(key, manager.getAccountKey(user))
            }
            assertEquals(writes, prefs.commitCount)
            assertTrue(prefs.contains(if (versioned) "${oldName}_v1" else oldName))
            val restored = CloudKeyManager(null, preferencesOverride = prefs).also { it.bindBackend(backend) }
            assertArrayEquals(key, restored.getAccountKey(user))
            assertEquals(writes, prefs.commitCount)
            val row = CloudKeyCommitment(1, CloudProtocolV2.commitment(key, user, "epoch", 1))
            CloudKeyTrust.validate(user, "epoch", 0, listOf(row), mapOf(1 to key))
            manager.saveVerifiedKeys(user, mapOf(1 to key))
            assertFalse(prefs.contains(if (versioned) "${oldName}_v1" else oldName))
            assertArrayEquals(key, manager.getAccountKey(user))
        }
    }

    @Test fun unversionedV1AndRotatedV2RemainOneCompleteVerifiableChain() {
        val prefs = MemoryPreferences(); val manager = legacy(prefs)
        val second = ByteArray(32) { 9 }
        prefs.edit().putString("${oldName}_v2", second.base64()).putInt("account_key_current_$oldHash", 2).commit()
        assertEquals(listOf(1,2), manager.keyVersions(user))
        val local = manager.keyVersions(user).associateWith { manager.getAccountKey(user,it)!! }
        val c1 = CloudProtocolV2.commitment(key,user,"epoch",1)
        val c2 = CloudProtocolV2.commitment(second,user,"epoch",2)
        val rows = listOf(CloudKeyCommitment(1,c1),CloudKeyCommitment(2,c2,
            CloudProtocolV2.rotationProof(key,user,"epoch",2,c2)))
        assertEquals(setOf(1,2), CloudKeyTrust.verifiedSubset(user,"epoch",0,rows,local).keys)
        val bootstrap = CloudBootstrap(CloudMemberState("test",true,true,true,100,0,0,null,null,null),
            emptyList(),emptyList(),"epoch",0,emptyList(),listOf(1,2))
        assertTrue(legacyMigrationHasCompleteKeySet(bootstrap,local.keys))
        CloudKeyTrust.validate(user,"epoch",0,rows,local)
        manager.saveVerifiedKeys(user,local)
        assertFalse(prefs.contains(oldName)); assertFalse(prefs.contains("${oldName}_v2"))
        assertEquals(listOf(1,2),manager.keyVersions(user)); assertEquals(2,manager.currentKeyVersion(user))
    }

    @Test fun coldAndEmptyLocalPossessionChecksDoNotNeedBootstrapOrWritePreferences() {
        val prefs = MemoryPreferences(); val manager = CloudKeyManager(null, preferencesOverride = prefs)
        manager.bindBackend(backend)
        repeat(1000) { assertFalse(manager.hasAccountKey(user)) }
        assertEquals(0, prefs.commitCount)
        manager.saveVerifiedKeys(user, mapOf(1 to key))
        val writes = prefs.commitCount
        val restarted = CloudKeyManager(null, preferencesOverride = prefs).also { it.bindBackend(backend) }
        repeat(1000) { assertTrue(restarted.hasAccountKey(user)) }
        assertEquals(writes, prefs.commitCount)
    }

    @Test fun validatedBootstrapSavingSameKeysDoesNotFeedItsOwnChangeCollector() = runTest {
        val prefs = MemoryPreferences(); val manager = CloudKeyManager(null, preferencesOverride = prefs)
        manager.bindBackend(backend); manager.saveVerifiedKeys(user, mapOf(1 to key))
        val writes = prefs.commitCount; var observations = 0
        val collector = launch {
            manager.changes.collect {
                check(++observations < 5) { "key change feedback loop" }
                manager.saveVerifiedKeys(user, mapOf(1 to key))
            }
        }
        testScheduler.runCurrent()
        assertEquals(1, observations); assertEquals(writes, prefs.commitCount)
        repeat(100) { manager.saveAccountKey(user, 1, key, true) }
        testScheduler.runCurrent()
        assertEquals(1, observations); assertEquals(writes, prefs.commitCount)
        collector.cancel()
    }

    @Test fun failedValidationAndIncompleteLegacyVersionsKeepOriginalForRecovery() {
        val prefs = MemoryPreferences(); val manager = legacy(prefs)
        val local = mapOf(1 to manager.getAccountKey(user)!!)
        val bootstrap = CloudBootstrap(CloudMemberState("test",true,true,true,100,0,0,null,null,null), emptyList(), emptyList(), "epoch", 0,
            emptyList(), listOf(1,2))
        assertFalse(legacyMigrationHasCompleteKeySet(bootstrap, local.keys))
        try {
            CloudKeyTrust.validate(user, "wrong-epoch", 0,
                listOf(CloudKeyCommitment(1, CloudProtocolV2.commitment(key,user,"epoch",1))), local)
            fail("must reject wrong epoch")
        } catch (_: CloudTrustException) { }
        assertTrue(prefs.contains(oldName)); assertArrayEquals(key, manager.getAccountKey(user))
    }

    @Test fun failedPromotionIsAtomicAndDoesNotLoseStagedOrOriginalKeys() {
        val prefs = MemoryPreferences(); val manager = legacy(prefs)
        manager.hasAccountKey(user); val before = prefs.all
        prefs.failCommit = true
        try { manager.saveVerifiedKeys(user, mapOf(1 to key)); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(before, prefs.all)
        prefs.failCommit = false
        assertArrayEquals(key, manager.getAccountKey(user))
        manager.saveVerifiedKeys(user, mapOf(1 to key)); assertFalse(prefs.contains(oldName))
    }

    @Test fun legacyClaimIsBackendIsolatedAndLogoutCannotResurrectOldKeys() {
        val prefs = MemoryPreferences(); val manager = legacy(prefs)
        assertTrue(manager.hasAccountKey(user)); val writes = prefs.commitCount
        manager.bindBackend("https://other.invalid")
        repeat(10) { assertFalse(manager.hasAccountKey(user)) }
        manager.clearAccountKeys(user)
        assertEquals(writes, prefs.commitCount); assertTrue(prefs.contains(oldName))
        manager.bindBackend(backend); assertArrayEquals(key, manager.getAccountKey(user))
        manager.clearAccountKeys(user); val cleared = prefs.commitCount
        repeat(10) { assertFalse(manager.hasAccountKey(user)); manager.clearAccountKeys(user) }
        assertEquals(cleared, prefs.commitCount); assertFalse(prefs.contains(oldName))
    }
}
