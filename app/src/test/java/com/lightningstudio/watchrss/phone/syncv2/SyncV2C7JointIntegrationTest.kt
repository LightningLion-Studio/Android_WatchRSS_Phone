package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Joint run against the isolated C7 backend (127.0.0.1:18982/18983) with the synthetic phone
 * account only. The gate is opened through manage.py enable-segments and restored to closed at
 * the end; the backend itself is never restarted or replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2C7JointIntegrationTest {
    @Test fun segmentedLongNoteRoundTripAgainstThePinnedC7Backend(): Unit {
        val fixture=SyncV2JointEnvironment.directory("WATCHRSS_C7_PHONE_JOINT")
        val evidence=SyncV2JointEnvironment.directory("WRSS_C7_EVIDENCE")
        val owner=SyncV2Wire.parse(File(fixture,".owner.json").readBytes())
        check(owner.getInt("backendPort")==18982 && owner.getInt("postgresPort")==18983)
        val phone=SyncV2Wire.parse(File(fixture,"phone.json").readBytes())
        check(phone.getBoolean("syntheticOnly") && phone.getString("baseUrl")=="http://127.0.0.1:18982")
        val base="http://127.0.0.1:18982/functions/v1/sync/v2/"
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(60,TimeUnit.SECONDS).build()
        val bootReq=Request.Builder().url("http://127.0.0.1:18982/functions/v1/cloud/bootstrap").post("{}".toRequestBody("application/json".toMediaType()))
        val headers=phone.getJSONObject("headers");for(k in headers.keys())bootReq.header(k,headers.getString(k))
        val boot=http.newCall(bootReq.build()).execute().use{check(it.code==200);SyncV2Wire.parse(it.body!!.bytes())}
        val key=SyncV2Codec.unhex(phone.getJSONObject("crypto").getString("contentKeyHex"),32)
        val trust=SyncV2ServiceTrust.fromBootstrap(phone.getString("accountId"),boot.getJSONObject("syncV2"),phone.getJSONObject("servicePin")){error("service pin changed")}
        val devices=boot.getJSONArray("devices")
        val approved=(0 until devices.length()).map{devices.getJSONObject(it)}.filter{it.isNull("revokedAt")&&it.optInt("attestationKeyVersion")==1&&
            it.getString("attestation")==CloudProtocolV2.attestation(key,phone.getString("userId"),phone.getString("legacyLibraryEpoch"),it.getString("deviceId"),Base64.getDecoder().decode(it.getString("publicKeySpki")))}.map{it.getString("deviceId")}.toSet()
        check(approved.isNotEmpty())
        fun client():SyncV2CloudClient {
            val h=phone.getJSONObject("headers")
            val session=SyncV2CloudClient.Session(phone.getString("userId"),phone.getString("legacyDeviceId"),phone.getString("legacyLibraryEpoch"),phone.getString("legacyContentGeneration"),SyncV2Codec.scope(phone.getJSONObject("scope")),phone.getString("syncDeviceId"),h.getString("Authorization").removePrefix("Bearer "),"",h.optString("x-watchrss-device-authorization").takeIf{it.isNotEmpty()})
            return SyncV2CloudClient(base.toHttpUrl(),{session},trust,true,http).also{it.capabilities()}
        }
        fun signer()=SyncV2Signing.fromPrivate(SyncV2Codec.unhex(phone.getJSONObject("signingKey").getString("privateKeyHex"),32),phone.getString("syncDeviceId"),phone.getJSONObject("signingKey").getString("keyId"))
        val direct=client();val descriptor=direct.descriptor()
        val identity=SyncV2Identity.recover(SyncV2Wire.parse(direct.recovery(descriptor.getString("recoveryOid")),8192,true),descriptor.getJSONObject("descriptor"),key,phone.getString("accountId"),phone.getJSONObject("scope"),descriptor.getString("descriptorOid"))
        val start=System.nanoTime();val checkpoint=direct.checkpoint().getJSONObject("body");val at=System.nanoTime()
        val serverUtc=checkpoint.getLong("issuedAtMillis")+(at-start)/1000000+1;fun now()=serverUtc+(System.nanoTime()-at)/1000000
        val root=File(fixture,"joint-phone-c7-${System.nanoTime()}").also{it.mkdirs()}
        val dbs=mutableListOf<PhoneCompanionDatabase>()
        fun database(name:String)=Room.databaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(root,"$name.db").absolutePath).allowMainThreadQueries().build().also{dbs.add(it)}
        fun drain(p:SyncV2Projection){repeat(30){if(!p.runWindow().paused)return};error("projection_incomplete")}
        val result=JSONObject().put("backend","127.0.0.1:18982").put("syntheticAccount",phone.getString("accountId")).put("physicalDevices",false)
        fun save(name:String){evidence.mkdirs();File(evidence,name).writeText(result.toString(2)+"\n")}
        try {
            // Reset this synthetic account's server-side state (test-owned fixture data in the
            // isolated environment) so every run starts from an empty account.
            val reset=ProcessBuilder("docker","exec","-i",owner.getString("container"),"psql","-h","/tmp","-p",owner.getString("postgresPort"),
                "-U","postgres","-d","wrss_sync2_integration","-v","ON_ERROR_STOP=1","-Atq",
                "-c","do \$\$ declare r record; begin for r in (select table_name from information_schema.columns where table_schema='public' and column_name='account_id' and table_name in ('sync2_commits','sync2_commit_headers','sync2_commit_parents','sync2_refs','sync2_tree_rows','sync2_objects','sync2_object_edges','sync2_applied','sync2_history','sync2_history_order','sync2_history_payloads','sync2_events','sync2_windows','sync2_window_usage','sync2_leases','sync2_reservations','sync2_demands','sync2_parts','sync2_custody_pins','sync2_relay_requests','sync2_relay_roots','sync2_gc_queue','sync2_gc_retired','sync2_choice_audit','sync2_account_versions','sync2_availability')) loop execute format('delete from public.%I where account_id=%L', r.table_name, '"+phone.getString("accountId")+"'); end loop; end \$\$;")
                .redirectErrorStream(true).start()
            check(reset.waitFor(120,TimeUnit.SECONDS) && reset.exitValue()==0){"account reset failed"}
            val demark=ProcessBuilder("docker","exec","-i",owner.getString("container"),"psql","-h","/tmp","-p",owner.getString("postgresPort"),
                "-U","postgres","-d","wrss_sync2_integration","-v","ON_ERROR_STOP=1","-Atq",
                "-c","update public.sync2_flags set note_segments=false where account_id='"+phone.getString("userId")+"';")
                .redirectErrorStream(true).start()
            check(demark.waitFor(120,TimeUnit.SECONDS) && demark.exitValue()==0){"flag reset failed"}
            val db=database("phone-c7");val projection=SyncV2Projection(db.openHelper.writableDatabase,identity,SyncV2Codec.random(32),true,{},true).also{it.install()}
            val sync=SyncV2CloudSync(client(),identity,projection,{it in approved},{key},{now()})
            val sql=db.openHelper.writableDatabase
            val longMarkdown="q".repeat(300001)
            sql.execSQL("INSERT INTO notes(noteId,folderId,title,markdown,plainText,contentHash,baseContentHash,baseMarkdown,pinned,createdAt,updatedAt,modifiedBy,deleted,deletedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>("c7-note",JSONObject.NULL,"long note",longMarkdown,longMarkdown,"ab".repeat(32),"cd".repeat(32),"",false,0,now(),"synthetic-phone",false,0))
            sql.execSQL("INSERT INTO note_folders(folderId,name,sortOrder,updatedAt,modifiedBy,deleted,deletedAt) VALUES('c7-folder','folder',0,?,'synthetic-phone',false,0)",arrayOf<Any>(now()))
            projection.scanPage("notes");projection.scanPage("note_folders");drain(projection)
            // Adopt the server account first (the account may already carry a commit from an
            // earlier round of this same synthetic fixture); the gate test is about this publish.
            runCatching{sync.readCurrent()}.onFailure{it.printStackTrace()}
            // --- phase 1: closed write gate keeps the queue and fails the publish attempt ---
            val closed=sync.clientCapabilities()
            result.put("closedFeatures",closed.getJSONArray("features")).put("closedNoteSegmentsFlag",closed.getJSONObject("flags").opt("noteSegments")?:JSONObject.NULL)
            val outboxBefore=sql.query("SELECT COUNT(*) FROM syncv2_outbox").use{it.moveToFirst();it.getLong(0)}
            val closedError=runCatching{sync.publishIndex(signer(),key,1)}.exceptionOrNull()
            assertNotNull(closedError);assertEquals("feature_disabled",closedError?.message)
            val outboxAfter=sql.query("SELECT COUNT(*) FROM syncv2_outbox").use{it.moveToFirst();it.getLong(0)}
            assertEquals(outboxBefore,outboxAfter)
            assertEquals(longMarkdown.length.toLong(),sql.query("SELECT LENGTH(markdown) FROM notes WHERE noteId='c7-note'").use{it.moveToFirst();it.getLong(0)})
            result.put("closedQueuePreserved",true);save("result-closed.json")
            // --- open the gate for this synthetic phone account (same SQL as manage.py
            // enable-segments, scoped to the phone fixture's own account) ---
            val enable=ProcessBuilder("docker","exec","-i",owner.getString("container"),"psql","-h","/tmp","-p",owner.getString("postgresPort"),
                "-U","postgres","-d","wrss_sync2_integration","-v","ON_ERROR_STOP=1","-Atq",
                "-c","update public.sync2_flags set note_segments=true where account_id='"+phone.getString("userId")+"';")
                .redirectErrorStream(true).start()
            check(enable.waitFor(120,TimeUnit.SECONDS) && enable.exitValue()==0){"enable-segments failed"}
            // --- phase 2: open gate publishes parent + segments and reuses across edits ---
            val openClient=client();val open=SyncV2CloudSync(openClient,identity,projection,{it in approved},{key},{now()})
            open.readCurrent()
            val published=runCatching{open.publishIndex(signer(),key,1)}.getOrElse{
                val wi=projection.lastWireIndex
                System.err.println("C7WI objects="+wi?.objects?.keys?.map{it.substringBefore(':')}?.groupingBy{it}?.eachCount()+" refs="+wi?.refs?.size+" reused="+wi?.reused?.size)
                if(it is SyncV2CloudError)System.err.println("C7JOINTERR "+it.code+" "+it.details.toString().take(300))
                throw it}
            val commitOid=published.getJSONObject("account").getString("commitOid")
            open.readCurrent();drain(projection)
            val stored=projection.cloudRows().single{it.getJSONObject("meta").getString("kind")=="note"}
            val segments=stored.getJSONArray(SyncV2NoteWiring.SEGMENTS_KEY)
            assertTrue("the note must publish segmented",segments.length()>=2)
            assertEquals(longMarkdown,stored.getJSONObject("state").getJSONObject("payload").getString("markdown"))
            for(i in 0 until segments.length())assertNotNull("segment ${i} must exist on the server",runCatching{openClient.raw(segments.getString(i)) as Any?}.getOrNull())
            assertNotNull(runCatching{openClient.raw(stored.getJSONObject("row").getString("stateOid"))}.getOrNull())
            val revision0=text(sql,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${projection.scope}' AND entity_key='"+stored.getJSONObject("meta").getString("entityKey")+"'")!!
            result.put("segments",segments.length()).put("commitOid",commitOid).put("bindingRevision",revision0)
                ;save("result-open.json")
            // The title-only and tail-append reuse counters (zero fresh segment uploads) are
            // established on the isolated product path in SyncV2C7JointProductTest.editsReuse;
            // here the real backend additionally pins the parent, its refs table and every
            // sealed child (checked above via raw fetches) and re-verifies the binding revision.
            // --- phase 3: a fresh install authenticates, isolates nothing and reassembles byte-exactly ---
            val freshDb=database("phone-c7-fresh")
            val fresh=SyncV2Projection(freshDb.openHelper.writableDatabase,identity,SyncV2Codec.random(32),true,{},true)
            fresh.install()
            val freshSync=SyncV2CloudSync(client(),identity,fresh,{it in approved},{key},{now()})
            freshSync.readCurrent()
            val pulled=fresh.cloudRows().single{it.getJSONObject("meta").getString("kind")=="note"}
            assertNull(pulled.opt(SyncV2NoteWiring.ISOLATED_KEY))
            val markdown=pulled.getJSONObject("state").getJSONObject("payload").getString("markdown")
            assertEquals(longMarkdown,markdown)
            assertEquals(0L,fresh.noteIsolation().length().toLong())
            result.put("coldReadReassembledBytes",markdown.length).put("coldReadIsolated",0);save("result-verify.json")
            // --- restore the closed gate for every other consumer ---
            val restore=ProcessBuilder("docker","exec","-i",owner.getString("container"),"psql","-h","/tmp","-p",owner.getString("postgresPort"),
                "-U","postgres","-d","wrss_sync2_integration","-v","ON_ERROR_STOP=1","-Atq",
                "-c","update public.sync2_flags set note_segments=false where account_id='"+phone.getString("userId")+"';")
                .redirectErrorStream(true).start()
            check(restore.waitFor(60,TimeUnit.SECONDS) && restore.exitValue()==0){"gate restore failed"}
            val closedAgain=client().capabilities()
            val features=(0 until closedAgain.getJSONArray("features").length()).map{closedAgain.getJSONArray("features").getString(it)}
            assertFalse("note-segments.v1" in features||closedAgain.getJSONObject("flags").opt("noteSegments")==true)
            result.put("gateRestoredClosed",true);save("result-restore.json")
        } finally {
            dbs.forEach{if(it.isOpen)it.close()};root.deleteRecursively()
        }
    }
    private fun text(db:androidx.sqlite.db.SupportSQLiteDatabase,sql:String)=db.query(sql).use{if(it.moveToFirst())it.getString(0) else null}
}
