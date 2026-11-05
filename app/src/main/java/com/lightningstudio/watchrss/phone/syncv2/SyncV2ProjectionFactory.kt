package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import org.json.JSONObject

/** Local-only entry for an already approved wrapping root and a pinned existing repo.
 * Deliberately not connected to cloud/BLE scheduling or UI apply in P1.
 */
internal class SyncV2ProjectionFactory(private val context:Context,private val database:PhoneCompanionDatabase) {
    /** Raw metadata is retained as a transport journal, never interpreted as current-generation edits. */
    @Synchronized fun captureLegacyMetadata(peer:String,version:Int,cursor:String,payload:JSONObject) {
        if(!BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION)return
        try {
            val sql=database.openHelper.writableDatabase
            val stored=sql.query("SELECT s.scope,s.account_id,s.descriptor_pin,s.identity FROM syncv2_state s JOIN syncv2_control c ON c.active_scope=s.scope WHERE c.id=1 AND c.enabled=1").use{c->
                if(c.moveToFirst())arrayOf<Any>(c.getString(0),c.getString(1),c.getString(2),c.getBlob(3))else null
            }?:return
            val scope=stored[0] as String;val account=stored[1] as String
            val master=MasterKey.Builder(context,"watchrss-syncv2-projection-master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs=EncryptedSharedPreferences.create(context,"watchrss-syncv2-local-keys",master,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val name=SyncV2Codec.hash(JSONObject().put("accountId",account).put("repoId",JSONObject(scope).getString("repoId")))
            val key=SyncV2Codec.unhex(prefs.getString(name,null)?:SyncV2Codec.fail("authentication_required"),32)
            try {
                val identity=SyncV2Identity.restoreInstalled(stored[3] as ByteArray,key,scope,account,stored[2] as String)
                val projection=SyncV2Projection(sql,identity,key)
                projection.receiveLegacy(SyncV2Projection.LegacyInput(peer,version,cursor,JSONObject().put("legacy-frame",payload.toString())),null,false)
            } finally {key.fill(0)}
        } catch (_:Exception) {
            // Stop only the new projection. The original channel, business writes and cursors continue.
            runCatching {database.openHelper.writableDatabase.execSQL("UPDATE syncv2_control SET enabled=0,failure_code='legacy_capture_failed' WHERE id=1")}
        }
    }
    @Synchronized fun recoverLocalProjection(capsule:JSONObject,descriptor:JSONObject,approvedWrappingRoot:ByteArray,
        accountId:String,scope:JSONObject,pinnedDescriptorOid:String):SyncV2Projection {
        if(!BuildConfig.SYNC_OBJECTS_V2_LOCAL_PROJECTION)SyncV2Codec.fail("feature_disabled")
        // Authenticate before opening secure storage or installing the local projection.
        val identity=SyncV2Identity.recover(capsule,descriptor,approvedWrappingRoot,accountId,scope,pinnedDescriptorOid)
        val master=MasterKey.Builder(context,"watchrss-syncv2-projection-master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs=EncryptedSharedPreferences.create(context,"watchrss-syncv2-local-keys",master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val name=SyncV2Codec.hash(JSONObject().put("accountId",accountId).put("repoId",identity.repoId))
        val stored=prefs.getString(name,null)
        val key=if(stored!=null)SyncV2Codec.unhex(stored,32)else SyncV2Codec.random(32).also {
            if(!prefs.edit().putString(name,SyncV2Codec.hex(it)).commit())SyncV2Codec.fail("storage_pressure")
        }
        try {
            return SyncV2Projection(database.openHelper.writableDatabase,identity,key).also{it.install()}
        } finally {key.fill(0)}
    }
}
