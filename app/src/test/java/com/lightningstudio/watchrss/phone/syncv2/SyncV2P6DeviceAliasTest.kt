package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SyncV2P6DeviceAliasTest {
    private class Host:AutoCloseable {
        val context=RuntimeEnvironment.getApplication() as Context
        val keys=CloudKeyManager(null,preferencesOverride=context.getSharedPreferences("alias-${System.nanoTime()}",Context.MODE_PRIVATE)).also{it.bindBackend("https://synthetic.invalid")}
        val life=SyncV2Lifetime{true};val store=SyncV2AccountStore(keys,"u",life)
        val repo="11".repeat(16);val counter=File(context.cacheDir,"alias-counter-${System.nanoTime()}")
        fun aliases(legacy:String="old")=SyncV2DeviceAlias(store,"12".repeat(16),legacy,"epoch","gen")
        fun vault(device:String?=null)=life.own(SyncV2SigningVault(store,repo,counter,life,device))
        fun record()=store.read("local/signing/$repo")!!
        val descriptor=JSONObject().put("body",JSONObject().put("creatorPlatform","android").put("creatorDeviceId","cc".repeat(16)))
        override fun close(){life.close();counter.delete();File(counter.path+".next").delete()}
    }
    @Test fun aliasSurvivesLogoutAndIsScopedToLegacyDevice()=Host().use{h->
        val device=h.aliases().bootstrap(null)
        h.keys.clearAccountKeys("u")
        assertEquals(device,h.aliases().installed(h.repo))
        assertNotEquals(device,h.aliases("other").bootstrap(null))
    }
    @Test fun twoAliasMigrationPreservesPrivateKeyKeyIdAndCounterBytes()=Host().use{h->
        val vault=h.vault();val before=h.record();val counter=h.counter.readBytes()
        val aliases=h.aliases();assertEquals(vault.signer.deviceId,aliases.installed(h.repo))
        val attempts=mutableListOf<String>()
        aliases.reconcile(vault,h.descriptor){attempts.add(it.deviceId);it.deviceId=="cc".repeat(16)}
        assertEquals(listOf(before.getString("deviceId"),"cc".repeat(16)),attempts)
        val after=h.record()
        for(field in listOf("privateHex","keyId","sequence"))assertTrue("migration changed $field",before.get(field)==after.get(field))
        assertArrayEquals(counter,h.counter.readBytes())
        assertEquals(vault.signer.publicHex,h.vault(aliases.installed(h.repo)).signer.publicHex)
        aliases.reconcile(vault,h.descriptor){fail("completed migration registered again");false}
    }
    @Test fun unknownRegistrationOutcomeRetriesPersistedAliasAndSameKey()=Host().use{h->
        val vault=h.vault();val aliases=h.aliases();aliases.installed(h.repo)
        val before=h.record()
        try{aliases.reconcile(vault,h.descriptor){if(it.deviceId!= "cc".repeat(16))false else throw IOException("synthetic_unknown")};fail("unknown outcome accepted")}catch(_:IOException){}
        val chosen=h.record();assertEquals("cc".repeat(16),chosen.getString("deviceId"))
        assertTrue("unknown outcome replaced signing private key",before.getString("privateHex")==chosen.getString("privateHex"))
        val reopened=h.vault(h.aliases().installed(h.repo));var calls=0
        h.aliases().reconcile(reopened,h.descriptor){calls++;assertEquals(chosen.getString("deviceId"),it.deviceId);true}
        assertEquals(1,calls)
    }
    @Test fun publishedAuthorIsNeverReboundAndHasExplicitRecoveryCode()=Host().use{h->
        val vault=h.vault();vault.reserve("0");val before=SyncV2Codec.canonical(h.record());val counter=h.counter.readBytes()
        val aliases=h.aliases();aliases.installed(h.repo)
        try{aliases.reconcile(vault,h.descriptor){false};fail("published identity rebound")}catch(e:IllegalStateException){assertEquals("device_alias_recovery_required",e.message)}
        assertTrue("signing record changed",before==SyncV2Codec.canonical(h.record()));assertArrayEquals(counter,h.counter.readBytes())
    }
    @Test fun counterDamageDoesNotPermitAliasMigrationOrKeyReplacement()=Host().use{h->
        val vault=h.vault();val before=SyncV2Codec.canonical(h.record());h.counter.writeText("broken")
        val aliases=h.aliases();aliases.installed(h.repo)
        try{aliases.reconcile(vault,h.descriptor){false};fail("counter damage ignored")}catch(e:IllegalStateException){assertEquals("signing_state_unavailable",e.message)}
        assertTrue("signing record changed",before==SyncV2Codec.canonical(h.record()));assertEquals("broken",h.counter.readText())
    }
    @Test fun foreignCreatorRejectionKeepsKeyAndNamesRecoveryPath()=Host().use{h->
        val vault=h.vault();val old=h.record();val aliases=h.aliases();aliases.installed(h.repo)
        try{aliases.reconcile(vault,h.descriptor){false};fail("foreign creator accepted")}catch(e:IllegalStateException){assertEquals("device_alias_recovery_required",e.message)}
        assertTrue("rejected migration replaced signing private key",old.getString("privateHex")==h.record().getString("privateHex"))
        assertTrue(com.lightningstudio.watchrss.phone.cloud.SyncV2View.errorText("device_alias_recovery_required").contains("保留应用数据"))
    }
}
