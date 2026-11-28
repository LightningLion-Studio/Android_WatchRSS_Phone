package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2ForegroundRefreshTest {
    private val probeRoutes=listOf("capabilities","checkpoint","devices/signing-keys")

    @Test fun phoneRoundWithMatchingWatermarksMakesOnlyAuthenticatedProbeCalls() {
        val writes=mutableListOf<String>()
        SyncV2P5Fixture(projectionFault={writes.add(it)}).use { w ->
            withRuntime(w) { runtime ->
                assertFalse(runtime.round())
                val before=w.projection.state()
                w.f.now+=1_000
                writes.clear()
                w.requests.clear()

                assertTrue(runtime.round())
                assertEquals(probeRoutes,w.requests)
                assertEquals(before.version,w.projection.state().version)
                assertTrue(writes.isEmpty())
                assertEquals(0,w.publishCalls)
            }
        }
    }

    @Test fun revocationAdvanceReverifiesEvenWithSameHeadAndRollbackFailsAfterRestart() {
        SyncV2P5Fixture().use { w ->
            w.start()
            w.revocationRevision="1"
            val head=w.current.getString("commitOid")
            val raw=w.f.objects.remove(head)!!
            assertEquals("object_missing",syncV2ErrorCode(runCatching{w.actions().refresh()}.exceptionOrNull()!!))
            assertEquals("1",w.projection.state().body.getString("revocationRevision"))
            w.f.objects[head]=raw
            w.reopen()
            w.revocationRevision="0"
            w.requests.clear()

            assertEquals("replay_detected",syncV2ErrorCode(runCatching{w.actions().refresh()}.exceptionOrNull()!!))
            assertEquals(probeRoutes,w.requests)
            w.revocationRevision="1"

            assertFalse(w.actions().refresh().getBoolean("unchanged"))
            assertTrue(w.requests.contains("objects/$head"))
            w.requests.clear()
            assertTrue(w.actions().refresh().getBoolean("unchanged"))
            assertEquals(probeRoutes,w.requests)
        }
    }

    @Test fun sameRevisionBindingRemovalStillReverifies() {
        SyncV2P5Fixture().use { w ->
            w.start()
            w.signingBindings={JSONArray()}
            w.requests.clear()

            assertFalse(w.actions().refresh().getBoolean("unchanged"))
            assertTrue(w.requests.contains("objects/${w.initial}"))
            assertEquals(0,w.publishCalls)
        }
    }

    @Test fun editDuringProbeRunsLocalProjectionAndPublish() {
        val writes=mutableListOf<String>()
        SyncV2P5Fixture(projectionFault={writes.add(it)}).use { w ->
            withRuntime(w) { runtime ->
                assertFalse(runtime.round())
                writes.clear()
                w.requests.clear()
                w.beforeSigningKeys={
                    w.beforeSigningKeys=null
                    w.sql.execSQL("UPDATE notes SET title='local title', updatedAt=updatedAt+1 WHERE noteId='note'")
                }

                assertFalse(runtime.round())
                assertTrue(writes.contains("before-checkpoint"))
                assertEquals(1,w.publishCalls)
                assertFalse(w.requests.contains("account/ancestry") &&
                    w.requests.indexOf("account/ancestry")<w.requests.indexOf("account/publish"))
                assertEquals(w.current.getString("commitOid"),w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
            }
        }
    }

    @Test fun cleanPhoneFollowsNewHeadWithoutLocalScanOrPublish() {
        val writes=mutableListOf<String>()
        SyncV2P5Fixture(projectionFault={writes.add(it)}).use { w ->
            withRuntime(w) { runtime ->
                assertFalse(runtime.round())
                val next=w.commit(listOf(w.note(markdown="from cloud\n")),w.initial,"0","normal",null)
                w.setCurrent(next,"3")
                w.requests.clear()
                writes.clear()

                assertFalse(runtime.round())
                assertTrue(w.requests.contains("account/ancestry"))
                assertEquals("from cloud\n",w.text())
                assertFalse(writes.contains("before-checkpoint"))
                assertEquals(0,w.publishCalls)
            }
        }
    }

    @Test fun unsettledAckPreventsFastPathAndIsRetried() {
        SyncV2P5Fixture().use { w ->
            withRuntime(w) { runtime ->
                assertFalse(runtime.round())
                w.projection.accountWriter(w.projection.state().version) { st ->
                    st.body.put("pendingAppliedAck", JSONObject().put("requestId","33".repeat(16))
                        .put("commitOid",w.initial).put("deviceId",w.f.signer.deviceId)
                        .put("refRevision","2").put("rollbackGeneration","0"))
                }
                w.requests.clear()

                assertFalse(runtime.round())
                assertTrue(w.requests.contains("acks/applied"))
                assertTrue(w.projection.state().body.isNull("pendingAppliedAck"))
            }
        }
    }

    @Test fun missingReferencedBodyKeepsTheNextRoundOnTheBodyPath() {
        SyncV2P5Fixture().use { w ->
            val head=w.commit(listOf(w.note(markdown="![missing](assets/missing.png)")),w.initial,"0","normal",null)
            w.setCurrent(head,"3")
            withRuntime(w, contentEnabled=true) { runtime ->
                assertFalse(runtime.round())
                assertEquals(listOf("cloud_note_asset_missing"),runtime.bodyResult.errors)
                w.requests.clear()

                assertFalse(runtime.round())
                assertEquals(listOf("cloud_note_asset_missing"),runtime.bodyResult.errors)
                assertEquals(probeRoutes,w.requests)
                assertEquals(0,w.publishCalls)
            }
        }
    }

    private fun withRuntime(w:SyncV2P5Fixture, contentEnabled:Boolean=false, run:(SyncV2PhoneProduct.Runtime)->Unit) {
        SyncV2Lifetime { true }.use { life ->
            val user=w.identity.accountId
            val prefs=RuntimeEnvironment.getApplication().getSharedPreferences(w.root.name,Context.MODE_PRIVATE)
            val manager=CloudKeyManager(null,preferencesOverride=prefs).also{
                it.bindBackend("https://synthetic.invalid")
                it.saveAccountKey(user,1,w.key,true)
            }
            val keys=life.own(SyncV2ContentKeys.authenticated(manager,user){})
            val store=SyncV2AccountStore(manager,user,life)
            val signer=w.f.signer
            store.write("local/signing/${w.identity.repoId}",JSONObject().put("privateHex","00".repeat(31)+"01")
                .put("deviceId",signer.deviceId).put("keyId",signer.keyId).put("sequence","0"))
            val vault=life.own(SyncV2SigningVault(store,w.identity.repoId,File(w.root,"counter"),life))
            val client=life.own(w.f.client())
            val actions=life.own(SyncV2AccountActions(client,w.identity,w.projection,vault.signer,keys::read,
                {it=="synthetic-phone"},w.root,true,{w.f.now}))
            val cloud=SyncV2CloudSync(client,w.identity,w.projection,{it=="synthetic-phone"},keys::read,{w.f.now})
            val time=life.own(SyncV2TrustedTime {0}).also{it.acceptVerifiedCheckpoint(w.f.now)}
            val bodies=if(contentEnabled)SyncV2BodyChannel(w.projection,w.identity,keys,vault.signer,client,
                SyncV2BodyRepository(w.sql,w.root),File(w.root,"bodies"),life,{it=="synthetic-phone"},{SyncV2TransportSignals()},{w.f.now})
                else null
            val account=SyncV2PhoneProduct.Account(user,"https://synthetic.invalid","synthetic-phone","epoch","0","","","",0)
            run(SyncV2PhoneProduct.Runtime(account,life,keys,w.identity,vault,w.projection,cloud,actions,client,
                null,bodies,time,contentEnabled,""))
        }
    }
}
