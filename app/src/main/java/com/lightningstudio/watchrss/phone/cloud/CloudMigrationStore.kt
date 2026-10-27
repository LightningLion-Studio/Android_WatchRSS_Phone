package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

interface CloudMigrationStore {
    fun load(scope: String, planId: String): JSONObject?
    fun save(scope: String, planId: String, value: JSONObject)
    fun remove(scope: String, planId: String)
}

/** Private details and output validation root are encrypted at rest; grants/chunk keys are never saved. */
class CloudMigrationPreferences(context: Context?, private val overridePreferences: SharedPreferences? = null): CloudMigrationStore {
    private val appContext=context?.applicationContext
    private val preferences by lazy { overridePreferences ?: EncryptedSharedPreferences.create(requireNotNull(appContext),"watchrss_cloud_migrations",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM) }
    private fun key(scope:String,id:String) = CloudSnapshotCodec.sha256("$scope\n$id".toByteArray())
    override fun load(scope:String,planId:String):JSONObject? = preferences.getString(key(scope,planId),null)?.let {CloudRawDocument.parse(it).json()}
    override fun save(scope:String,planId:String,value:JSONObject) {
        require(value.keys().asSequence().none { it in setOf("grant","sealedGrant","chunkKeys","accountKey","bearer") })
        try {
            check(preferences.edit().putString(key(scope,planId),value.toString()).commit())
        } catch(error:Exception) {throw IllegalStateException("migration_checkpoint_failed",error)}
    }
    override fun remove(scope:String,planId:String) {
        try {check(preferences.edit().remove(key(scope,planId)).commit())}
        catch(error:Exception) {throw IllegalStateException("migration_checkpoint_failed",error)}
    }
}

/** Limits accumulate actual IO/activity within one background run; callers schedule the next bounded run. */
class CloudMigrationRunBudget(private val activeMillis:Long=30_000,private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    init { require(activeMillis in 1..30_000) }
    private val started=now()
    var bytes:Long=0; private set
    fun reserve(size:Long) { require(size>=0 && size<=8_388_608-bytes && now()-started<activeMillis) { "migration_client_run_budget" };bytes+=size }
    fun checkpoint() { require(now()-started<activeMillis) { "migration_client_run_budget" } }
    internal fun remainingMillis():Long { checkpoint();return (activeMillis-(now()-started)).coerceAtLeast(1) }
    internal suspend fun <T> within(block:suspend()->T):T = kotlinx.coroutines.withContext(CloudMigrationDeadline(this)) {block()}

}

internal class CloudMigrationDeadline(val budget:CloudMigrationRunBudget):kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key:kotlin.coroutines.CoroutineContext.Key<CloudMigrationDeadline>
}
