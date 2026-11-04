package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import org.json.JSONObject

enum class CloudNetworkPolicy {
    WIFI_BODIES_ANY_STATE,
    WIFI_AND_CHARGING,
    ANY_NETWORK
}

class PhoneCloudStateStore internal constructor(private val preferences: android.content.SharedPreferences) {
    constructor(context: Context, prefsName: String = "watchrss_cloud_state") : this(
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE))

    private var scope = ""
    fun bindScope(baseUrl: String, userId: String) = synchronized(preferences) {
        scope = CloudSnapshotCodec.sha256("${baseUrl.trimEnd('/')}\n$userId".toByteArray()) + ":"
        if (!preferences.getBoolean("scoped_cloud_v2", false)) {
            val editor = preferences.edit().putBoolean("scoped_cloud_v2", true)
            listOf(KEY_DEVICE_SEQUENCE, KEY_APPLIED_FULL, KEY_APPLIED_STATE, KEY_CONTENT_HASH, KEY_CONTENT_HASH_FULL, KEY_CONTENT_HASH_STATE, KEY_PARENT_HEADS).forEach(editor::remove)
            preferences.all.keys.filter { it.startsWith("body_restore_v2_") }.forEach(editor::remove)
            check(editor.commit())
        }
    }
    private fun scoped(key: String): String = scope + key
    private val failedCycleScopes=mutableSetOf<String>()
    internal data class CycleAdmission(val scope:String,val version:Long,val checkpoint:CloudCycleCheckpoint?)
    private fun invalidationVersion()=preferences.getLong(scoped("cycle_invalidation_version"),0)
    internal fun cycleCheckpoint():CloudCycleCheckpoint? = synchronized(preferences) {
        if(scope in failedCycleScopes)null else CloudCycleCheckpoint.decode(preferences.getString(scoped("cycle_checkpoint_v1"),null))
    }
    internal fun admitCycle():CycleAdmission = synchronized(preferences) {
        val prior=cycleCheckpoint()
        invalidateCycleCheckpoint()
        CycleAdmission(scope,invalidationVersion(),prior)
    }
    // Every source uses this durable counter. Completion may consume only its own admission.
    internal fun invalidateCycleCheckpoint() = synchronized(preferences) {
        failedCycleScopes.add(scope)
        val next=Math.addExact(invalidationVersion(),1L)
        check(preferences.edit().remove(scoped("cycle_checkpoint_v1")).putLong(scoped("cycle_invalidation_version"),next).commit())
    }
    internal fun closedPrivateArchive():ClosedPrivateArchiveProof? = synchronized(preferences) {
        ClosedPrivateArchiveProof.decode(preferences.getString(scoped("closed_private_archive_v1"),null))
    }
    internal fun completeCycle(checkpoint:CloudCycleCheckpoint, admission:CycleAdmission,closedPrivateArchive:ClosedPrivateArchiveProof?=null):Boolean = synchronized(preferences) {
        if(scope!=admission.scope || invalidationVersion()!=admission.version)return@synchronized false
        failedCycleScopes.add(scope)
        val edit=preferences.edit().putString(scoped("cycle_checkpoint_v1"),checkpoint.encode())
        if(closedPrivateArchive!=null) {
            require(checkpoint.heads[closedPrivateArchive.headId]==closedPrivateArchive.manifestSha256 &&
                checkpoint.filesRevision.privateFiles==closedPrivateArchive.token)
            edit.putString(scoped("closed_private_archive_v1"),closedPrivateArchive.encode())
        }
        check(edit.commit())
        failedCycleScopes.remove(scope)
        true
    }
    var libraryEpoch: String?
        get() = preferences.getString(scoped("library_epoch"), null)
        set(value) { check(preferences.edit().putString(scoped("library_epoch"), value).commit()) }
    fun observeEpoch(epoch: String, invalidate: () -> Unit): Boolean {
        require(epoch.isNotBlank())
        val changed = libraryEpoch != null && libraryEpoch != epoch
        if (changed) invalidate()
        libraryEpoch = epoch
        return changed
    }
    fun quarantineKey(version: Int, reason: String) {
        val key = scoped("quarantined_keys")
        val rows = JSONObject(preferences.getString(key, "{}").orEmpty())
        rows.put(version.toString(), reason)
        check(preferences.edit().putString(key, rows.toString()).commit())
    }
    fun quarantinedKeyVersions(): Set<Int> = JSONObject(preferences.getString(scoped("quarantined_keys"), "{}").orEmpty())
        .keys().asSequence().mapNotNull { it.toIntOrNull() }.toSet()
    var ownerUserId: String?
        get() = preferences.getString("private_library_owner", null)
        set(value) { check(preferences.edit().putString("private_library_owner", value).commit()) }
    fun claimLegacyOwner(userId: String) { if (ownerUserId == null) ownerUserId = userId }
    fun requireUploadOwner(userId: String) { check(ownerUserId == userId) { "本机资料属于其他账号，请先清空本机资料或明确确认合并" } }
    fun clearScope() = synchronized(preferences) {
        check(scope.isNotEmpty())
        invalidateCycleCheckpoint()
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(scope) && it!=scoped("cycle_invalidation_version") }.forEach(editor::remove)
        check(editor.commit())
    }

    var formatUpgradeRequiredVersion:Int
        get()=if(preferences.getString(scoped("format_upgrade_epoch"),null)==libraryEpoch &&
            preferences.getString(scoped("format_upgrade_generation"),null)==contentGeneration)
            preferences.getInt(scoped("format_upgrade_required_version"),0) else 0
        set(value) {check(preferences.edit().putInt(scoped("format_upgrade_required_version"),value)
            .putString(scoped("format_upgrade_epoch"),libraryEpoch).putString(scoped("format_upgrade_generation"),contentGeneration).commit())}

    var syncPaused: Boolean
        get() = preferences.getBoolean("sync_paused", false)
        set(value) { check(preferences.edit().putBoolean("sync_paused", value).commit()) }

    var retentionDays: Int?
        get() = preferences.getInt(KEY_RETENTION_DAYS, 30).takeIf { it > 0 }
        set(value) {
            require(value == null || value in setOf(7, 30, 90))
            preferences.edit().putInt(KEY_RETENTION_DAYS, value ?: 0).apply()
        }

    var networkPolicy: CloudNetworkPolicy
        get() = runCatching {
            CloudNetworkPolicy.valueOf(
                preferences.getString(KEY_NETWORK_POLICY, null)
                    ?: CloudNetworkPolicy.ANY_NETWORK.name
            )
        }.getOrDefault(CloudNetworkPolicy.ANY_NETWORK)
        set(value) {
            preferences.edit().putString(KEY_NETWORK_POLICY, value.name).apply()
        }

    // Older versions could acknowledge an index without restoring private bodies.
    fun ensureFullBodyRestoreVersion(userId: String) {
        val key = scoped("body_restore_v2_$userId")
        if (!preferences.getBoolean(key, false)) {
            check(preferences.edit().remove(scoped(KEY_APPLIED_FULL)).putBoolean(key, true).commit())
        }
    }

    @Synchronized
    fun nextSequence(serverSequence: Long): Long {
        val next = maxOf(preferences.getLong(scoped(KEY_DEVICE_SEQUENCE), 0L), serverSequence) + 1L
        check(preferences.edit().putLong(scoped(KEY_DEVICE_SEQUENCE), next).commit())
        return next
    }

    fun appliedSequence(deviceId: String, full: Boolean): Long =
        appliedHeads(full).optLong(deviceId)

    fun markApplied(deviceId: String, sequence: Long, full: Boolean) {
        val json = appliedHeads(full)
        json.put(deviceId, maxOf(json.optLong(deviceId), sequence))
        preferences.edit().putString(appliedKey(full), json.toString()).apply()
    }

    fun lastContentHash(full: Boolean): String? =
        preferences.getString(
            contentHashKey(full),
            preferences.getString(scoped(KEY_CONTENT_HASH), null)
        )

    fun ensureRssChannelOnlyVersion(resetRecords: () -> Unit) {
        val key = scoped("rss_channel_only_v1")
        if (!preferences.getBoolean(key,false)) {
            resetRecords()
            check(preferences.edit().putBoolean(key,true).commit())
        }
    }

    var lastSnapshotPublishedAt: Long
        get() = preferences.getLong(scoped("last_snapshot_published_at_v3"), 0)
        set(value) { check(preferences.edit().putLong(scoped("last_snapshot_published_at_v3"), value).commit()) }

    var ownershipUpgradeAttemptAt: Long
        get()=if(preferences.getString(scoped("ownership_attempt_epoch"),null)==libraryEpoch &&
            preferences.getString(scoped("ownership_attempt_generation"),null)==contentGeneration)
            preferences.getLong(scoped("ownership_attempt_at"),0) else 0
        set(value) {check(preferences.edit().putLong(scoped("ownership_attempt_at"),value)
            .putString(scoped("ownership_attempt_epoch"),libraryEpoch).putString(scoped("ownership_attempt_generation"),contentGeneration).commit())}

    fun lastParentHeads(): String? = preferences.getString(scoped(KEY_PARENT_HEADS), null)

    fun markUploaded(
        contentHash: String,
        parentHeads: Map<String, String>,
        full: Boolean
    ) {
        preferences.edit()
            .putString(contentHashKey(full), contentHash)
            .putString(scoped(KEY_PARENT_HEADS), JSONObject(parentHeads).toString())
            .apply()
    }

    /** Revisit objects abandoned by the old generic-IllegalArgumentException classification. */
    internal fun ensureRestoreFailureClassificationV2() {
        val marker=scoped("restore_failure_rules_v2")
        if (preferences.getBoolean(marker,false)) return
        val edit=preferences.edit()
        var removed=false
        preferences.all.keys.filter { it.startsWith(scoped("abandoned_restore_v1:")) }.forEach { key ->
            val rows=JSONObject(preferences.getString(key,"{}").orEmpty())
            rows.keys().asSequence().toList().filter { rows.optString(it)=="private_object_unavailable" }.forEach {
                rows.remove(it); removed=true
            }
            edit.putString(key,rows.toString())
        }
        if (removed) { edit.remove(scoped(KEY_APPLIED_FULL));edit.remove(scoped(KEY_APPLIED_STATE)) }
        check(edit.putBoolean(marker,true).commit()) { "无法更新恢复校验状态" }
    }

    private fun abandonedRestoreKey(snapshotId: String): String = scoped("abandoned_restore_v1:" +
        CloudSnapshotCodec.sha256(requireNotNull(libraryEpoch).toByteArray()) + ":" + snapshotId)

    /** Permanent damage belongs to this immutable head, never to an article across future heads. */
    @Synchronized
    internal fun abandonRestore(snapshotId: String, warning: com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning): Boolean {
        require(warning.permanent)
        val key=abandonedRestoreKey(snapshotId)
        val rows=JSONObject(preferences.getString(key,"{}").orEmpty())
        val id=warning.articleId.ifBlank { "unknown-private-entry" }
        if (rows.has(id)) return false
        rows.put(id,warning.reason)
        check(preferences.edit().putString(key,rows.toString()).commit()) { "无法保存损坏条目跳过状态" }
        return true
    }

    internal fun abandonedRestoreIds(snapshotId: String): Set<String> =
        JSONObject(preferences.getString(abandonedRestoreKey(snapshotId),"{}").orEmpty()).keys().asSequence().toSet()

    var contentGeneration: String?
        get() = preferences.getString(scoped("content_generation"), null)
        private set(value) { check(preferences.edit().putString(scoped("content_generation"), value).commit()) }

    /** Commit the generation only after every cache was cleared; a crash repeats cleanup safely. */
    @Synchronized
    internal fun observeContentGeneration(generation: String, contentEmpty: Boolean, reset: () -> Unit): Boolean {
        require(java.util.UUID.fromString(generation).toString() == generation)
        val previous = contentGeneration
        val rebuild = (previous != null && previous != generation) || (previous == null && contentEmpty)
        if (rebuild) reset()
        if (previous != generation) contentGeneration = generation
        return rebuild
    }

    fun clearLibraryState(preserveDeviceSequence: Boolean = false) {
        invalidateCycleCheckpoint()
        val abandoned=preferences.edit()
        preferences.all.keys.filter { it.startsWith(scoped("abandoned_restore_v1:")) }.forEach(abandoned::remove)
        check(abandoned.commit())
        check(
            preferences.edit()
                .apply { if (!preserveDeviceSequence) remove(scoped(KEY_DEVICE_SEQUENCE)) }
                .remove(scoped("closed_private_archive_v1"))
                .remove(scoped("last_snapshot_published_at_v3"))
                .remove(scoped("ownership_attempt_at"))
                .remove(scoped("ownership_attempt_epoch"))
                .remove(scoped("ownership_attempt_generation"))
                .remove(scoped(KEY_APPLIED_FULL))
                .remove(scoped(KEY_APPLIED_STATE))
                .remove(scoped(KEY_CONTENT_HASH))
                .remove(scoped(KEY_CONTENT_HASH_FULL))
                .remove(scoped(KEY_CONTENT_HASH_STATE))
                .remove(scoped(KEY_PARENT_HEADS))
                .commit()
        ) { "本机云同步状态清理失败" }
    }

    private fun appliedHeads(full: Boolean): JSONObject =
        runCatching {
            JSONObject(preferences.getString(appliedKey(full), "{}").orEmpty())
        }.getOrDefault(JSONObject())

    private fun appliedKey(full: Boolean): String =
        if (full) scoped(KEY_APPLIED_FULL) else scoped(KEY_APPLIED_STATE)

    private fun contentHashKey(full: Boolean): String =
        if (full) scoped(KEY_CONTENT_HASH_FULL) else scoped(KEY_CONTENT_HASH_STATE)

    private companion object {
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_NETWORK_POLICY = "network_policy"
        private const val KEY_DEVICE_SEQUENCE = "device_sequence"
        private const val KEY_APPLIED_FULL = "applied_full_heads"
        private const val KEY_APPLIED_STATE = "applied_state_heads"
        private const val KEY_CONTENT_HASH = "last_content_hash"
        private const val KEY_CONTENT_HASH_FULL = "last_content_hash_full"
        private const val KEY_CONTENT_HASH_STATE = "last_content_hash_state"
        private const val KEY_PARENT_HEADS = "last_parent_heads"
    }
}
