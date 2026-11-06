package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import kotlinx.coroutines.runBlocking
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
class SyncV2P3ProjectionTest {
    @Test fun unpluggedFullBatteryAndUnknownPowerDoNotAuthorizeChargingTransfer() {
        assertEquals(false,SyncV2AndroidTransport.charging(android.os.BatteryManager.BATTERY_STATUS_FULL,0))
        assertEquals(true,SyncV2AndroidTransport.charging(android.os.BatteryManager.BATTERY_STATUS_FULL,1))
        assertNull(SyncV2AndroidTransport.charging(android.os.BatteryManager.BATTERY_STATUS_FULL,-1))
        assertNull(SyncV2AndroidTransport.charging(android.os.BatteryManager.BATTERY_STATUS_CHARGING,0))
    }
    @Test fun vpnWithoutAnIdentifiedUnderlyingRouteKeepsCellularEligibilityUnknown() {
        val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        val shadow=org.robolectric.Shadows.shadowOf(caps)
        shadow.addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN);shadow.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val signals=SyncV2AndroidTransport.network(true,caps,false,false)
        assertNull(signals.cellular);assertNull(signals.wifi);assertFalse(signals.eligible(false).getBoolean("cloudAllowed"))
        assertTrue(SyncV2AndroidTransport.network(true,caps,true,false).eligible(false).getBoolean("cloudAllowed"))
    }
    @Test fun smallReadingEditPreservesPublishedRecipeBodyRevisionAndRawBusinessFile()=runBlocking {
        val context:Context=RuntimeEnvironment.getApplication();val dir=java.io.File(context.cacheDir,"p3-projection-${System.nanoTime()}").apply{mkdirs()}
        val db=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,dir.resolve("phone.db").absolutePath).allowMainThreadQueries().build()
        try {
            val identity=SyncV2Fixtures.identity();val p=SyncV2Projection(db.openHelper.writableDatabase,identity,SyncV2Fixtures.localKey,true);p.install()
            val article=PhoneArticleEntity("a","old-device","https://example.invalid/a","title","site","excerpt",null,"original body",null,"hash",13,15,true,16,-1,null,null,true,17,18,true,19,20,false,0)
            db.phoneArticleDao().upsert(article);p.scanPage("phone_articles")
            val signer=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"ab".repeat(16));p.prepareWireIndex(signer,ByteArray(32),1)
            val before=p.wireContentRow(identity.entity("article","a"))!!
            val recipe=JSONObject().put("version",2).put("entityKey",before.get("entityKey")).put("bodyRevision",before.get("bodyRevision")).put("opaqueContentId","33".repeat(32))
                .put("plaintextSha256","44".repeat(32)).put("totalPlainBytes",0).put("blocks",JSONArray()).put("ranges",JSONArray())
            p.attachContentRecipe(before,"recipe:sha256:"+"55".repeat(32),recipe)
            val attached=p.wireContentRow(identity.entity("article","a"))!!
            db.phoneArticleDao().upsert(article.copy(isRead=true,readingPositionChangedAt=99));p.scanPage("phone_articles");p.prepareWireIndex(signer,ByteArray(32),1)
            val after=p.wireContentRow(identity.entity("article","a"))!!
            for(k in listOf("bodyRevision","recipeOid","opaqueContentId","contentBytes","promiseId"))assertEquals(attached.get(k),after.get(k))
            try{p.attachContentRecipe(before,"recipe:sha256:"+"55".repeat(32),recipe);fail()}catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
            assertEquals("original body",db.phoneArticleDao().getById("a")!!.contentText)
            val settings=SyncV2ContentSettings(context);assertTrue(settings.allowChargingCellular);settings.allowChargingCellular=false
            assertFalse(SyncV2ContentSettings(context).allowChargingCellular);settings.allowChargingCellular=true
        } finally {db.close();dir.deleteRecursively()}
    }
}
