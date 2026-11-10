package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P6ApplySequenceTest {
    @Test fun roomBusinessFailureRollsBackAppliedAndDoesNotAckThenRetryApplies() {
        SyncV2P5Fixture().use {f->
            val action=f.actions(fault={if(it=="business-applied")error("synthetic_apply_failure")})
            try{action.refresh();fail("apply fault swallowed")}catch(e:IllegalStateException){assertEquals("synthetic_apply_failure",e.message)}
            assertEquals(0,f.ackCalls)
            assertTrue(f.projection.state().body.isNull("appliedAccount"))
            assertEquals(0,f.sql.query("SELECT COUNT(*) FROM notes").use{it.moveToFirst();it.getInt(0)})
            f.actions().refresh()
            assertEquals("base\nline\n",f.text());assertEquals(1,f.ackCalls)
            assertFalse(f.projection.state().body.isNull("appliedAccount"))
            f.reopen();assertEquals("base\nline\n",f.text())
        }
    }
    @Test fun missingTrustedTimeDoesNotPreventPullAndBusinessApply() {
        SyncV2P5Fixture().use {f->
            val actions=SyncV2AccountActions(f.f.client(),f.identity,f.projection,f.f.signer,{f.key},{it=="synthetic-phone"},f.root,true,{null},networkNow={f.f.now})
            actions.refresh();assertEquals("base\nline\n",f.text());assertEquals(1,f.ackCalls)
            try{actions.forkNote("note");fail("not isolated")}catch(_:Exception){}
        }
    }
    @Test fun sequenceSurvivesProjectionFallbackAndRejectsCorruptOrOldSecureRecord() {
        val context=RuntimeEnvironment.getApplication() as Context
        val manager=CloudKeyManager(null,preferencesOverride=context.getSharedPreferences("seq-${System.nanoTime()}",Context.MODE_PRIVATE)).also{it.bindBackend("https://synthetic.invalid")}
        val life=SyncV2Lifetime{true};val store=SyncV2AccountStore(manager,"u",life)
        val repo="11".repeat(16);val file=File(context.cacheDir,"counter-${System.nanoTime()}")
        try {
            val vault=life.own(SyncV2SigningVault(store,repo,file,life))
            assertEquals("1",vault.reserve("0"));val previous=store.read("local/signing/$repo")!!
            assertEquals("2",vault.reserve("0"))
            val reopened=life.own(SyncV2SigningVault(store,repo,file,life))
            assertEquals(vault.signer.publicHex,reopened.signer.publicHex);assertEquals("3",reopened.reserve("0"))
            val current=store.read("local/signing/$repo")!!
            store.write("local/signing/$repo",previous)
            try{reopened.reserve("0");fail("fallback sequence accepted")}catch(e:IllegalStateException){assertEquals("signing_state_unavailable",e.message)}
            store.write("local/signing/$repo",current);file.writeText("broken")
            val receiverStillOpens=life.own(SyncV2SigningVault(store,repo,file,life))
            try{receiverStillOpens.reserve("0");fail("corrupt sequence accepted")}catch(e:IllegalStateException){assertEquals("signing_state_unavailable",e.message)}
        } finally {life.close();file.delete()}
    }
}
