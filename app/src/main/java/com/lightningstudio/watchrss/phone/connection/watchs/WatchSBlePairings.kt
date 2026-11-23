package com.lightningstudio.watchrss.phone.connection.watchs

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One active pair. A retired pair retains only the key that authenticates a reset. */
internal class WatchSBlePairings(
    private val now: () -> Long,
    private val read: () -> JSONObject,
    private val save: (JSONObject) -> Unit
) {
    enum class Mode(val label: String) { SYNC("sync"), RESET("reset") }

    sealed interface Status {
        data object Idle : Status
        data object Paired : Status
        data object ResetPending : Status
        class Code(val text: String, val expiresAt: Long) : Status
    }

    class Credential(val mode: Mode, val key: ByteArray) : AutoCloseable {
        override fun close() = key.fill(0)
    }

    private class Pending(val owner: String, val id: String, val key: ByteArray, val code: Status.Code, val prior: Status)

    private var pending: Pending? = null
    @Volatile var revision = 0L
        private set
    private var storageFailed = false
    private val status = MutableStateFlow<Status>(Status.Idle)
    val ui: StateFlow<Status> = status

    @Synchronized
    fun refresh(owner: String) {
        val state = forOwner(owner)
        display(state)
    }

    @Synchronized
    fun begin(owner: String, allowed: () -> Boolean = { true }) {
        check(allowed())
        val state = forOwner(owner)
        check(state.optJSONObject(ACTIVE) == null)
        clearPending()
        display(state)
        val code = SyncV2Codec.random(16)
        try {
            val key = WatchSBleSecurity.pairKey(code)
            val text = SyncV2Codec.hex(code).uppercase().chunked(4).joinToString(" ")
            pending = Pending(owner, WatchSBleSecurity.pairId(key), key, Status.Code(text, now() + CODE_LIFETIME_MS), status.value)
            display(state)
        } finally {
            code.fill(0)
        }
    }

    @Synchronized
    fun cancel() {
        val previous = pending?.prior ?: return
        clearPending()
        status.value = previous
    }

    @Synchronized
    fun revoke() {
        clearPending()
        val state = load()
        retire(state)
        display(state)
    }

    @Synchronized
    fun lookup(owner: String, id: String): Credential {
        val state = forOwner(owner)
        val active = state.optJSONObject(ACTIVE)
        if (active?.optString("id") == id) return Credential(Mode.SYNC, key(active))
        pending?.takeIf { it.id == id }?.let { return Credential(Mode.SYNC, it.key.copyOf()) }
        val reset = state.optJSONObject(RESETS)?.optJSONObject(id) ?: error("authentication_required")
        return Credential(Mode.RESET, key(reset))
    }

    /** Commit the authenticated Watch counter before any encrypted reply can leave the phone. */
    @Synchronized
    fun confirm(owner: String, id: String, mode: Mode, counter: String) {
        val state = forOwner(owner)
        val existing = if (mode == Mode.RESET) {
            checkNotNull(state.optJSONObject(RESETS)?.optJSONObject(id))
        } else {
            state.optJSONObject(ACTIVE)?.takeIf { it.getString("id") == id }
        }
        if (existing != null) {
            check(counter > existing.getString("counter"))
            existing.put("counter", counter)
            persist(state)
            return
        }
        val candidate = checkNotNull(pending)
        check(candidate.id == id && state.optJSONObject(ACTIVE) == null)
        state.put(ACTIVE, JSONObject().put("id", id).put("owner", owner)
            .put("key", SyncV2Codec.hex(candidate.key)).put("counter", counter))
        persist(state)
        clearPending()
        display(state)
    }

    @Synchronized
    fun <T> withActive(owner: String, id: String, operation: () -> T): T {
        check(forOwner(owner).optJSONObject(ACTIVE)?.optString("id") == id)
        return operation()
    }

    @Synchronized
    fun forget(owner: String, id: String, mode: Mode) {
        val state = forOwner(owner)
        when (mode) {
            Mode.SYNC -> {
                check(state.optJSONObject(ACTIVE)?.optString("id") == id)
                state.remove(ACTIVE)
            }
            Mode.RESET -> check(state.optJSONObject(RESETS)?.remove(id) != null)
        }
        persist(state)
        display(state)
    }

    private fun forOwner(owner: String): JSONObject {
        val state = load()
        if (state.optJSONObject(ACTIVE)?.optString("owner")?.let { it != owner } == true) {
            retire(state)
            display(state)
        }
        if (pending?.let { it.owner != owner || now() >= it.code.expiresAt } == true) {
            clearPending()
            display(state)
        }
        return state
    }

    private fun retire(state: JSONObject) {
        val active = state.remove(ACTIVE) as? JSONObject ?: return
        revision++
        val resets = state.optJSONObject(RESETS) ?: JSONObject().also { state.put(RESETS, it) }
        val key = key(active)
        val reset = try { WatchSBleSecurity.resetKey(key) } finally { key.fill(0) }
        try {
            val sequence = state.optLong("resetSequence") + 1
            state.put("resetSequence", sequence)
            resets.put(active.getString("id"), JSONObject().put("key", SyncV2Codec.hex(reset))
                .put("sequence", sequence).put("counter", active.getString("counter")))
            val oldest = resets.keys().asSequence().sortedBy { resets.getJSONObject(it).getLong("sequence") }.toList()
            oldest.take((oldest.size - RESET_LIMIT).coerceAtLeast(0)).forEach { resets.remove(it) }
            persist(state)
        } finally {
            reset.fill(0)
        }
    }

    private fun load(): JSONObject {
        check(!storageFailed)
        return read()
    }

    private fun persist(state: JSONObject) {
        try {
            save(state)
        } catch (error: Exception) {
            storageFailed = true
            revision++
            clearPending()
            status.value = Status.Idle
            throw error
        }
    }

    private fun key(record: JSONObject) = SyncV2Codec.unhex(record.getString("key"), 32)

    private fun clearPending() {
        pending?.key?.fill(0)
        pending = null
    }

    private fun display(state: JSONObject) {
        status.value = pending?.code ?: when {
            state.optJSONObject(ACTIVE) != null -> Status.Paired
            (state.optJSONObject(RESETS)?.length() ?: 0) > 0 -> Status.ResetPending
            else -> Status.Idle
        }
    }

    companion object {
        private const val ACTIVE = "active"
        private const val RESETS = "resets"
        private const val RESET_LIMIT = 8
        private const val CODE_LIFETIME_MS = 300_000L

        // KTX edit does not return the synchronous commit result.
        @SuppressLint("UseKtx")
        @Suppress("DEPRECATION")
        fun android(context: Context): WatchSBlePairings {
            val prefs by lazy {
                val master = MasterKey.Builder(context, "watchrss-watchs-pairings")
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(context, "watchrss-watchs-pairings", master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            }
            return WatchSBlePairings(SystemClock::elapsedRealtime,
                { JSONObject(prefs.getString("pairings", "{}") ?: "{}") },
                { check(prefs.edit().putString("pairings", it.toString()).commit()) })
        }
    }
}
