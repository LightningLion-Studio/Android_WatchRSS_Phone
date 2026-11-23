package com.lightningstudio.watchrss.phone.connection.watchs

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore

internal class WatchSBleBindings(
    private val now: () -> Long,
    private val read: () -> JSONObject,
    private val save: (JSONObject) -> Unit
) {
    enum class Mode(val wire: String) { SYNC("sync"), PENDING("pending"), RESET("reset"), REJECTED("rejected") }

    data class Status(
        val boundName: String? = null,
        val waiting: Boolean = false,
        val request: Request? = null,
        val hasResets: Boolean = false
    )

    class Request(val watchId: String, val name: String, val owner: String, val expiresAt: Long) {
        val decision = CompletableDeferred<Boolean>()
    }

    data class Hello(val mode: Mode, val revision: Long, val request: Request? = null, val otherBound: Boolean = false)

    private data class Window(val owner: String, val expiresAt: Long)

    private var window: Window? = null
    private var request: Request? = null
    private var observedOwner: String? = null
    @Volatile var revision = 0L
        private set
    private var storageFailed = false
    private val status = MutableStateFlow(Status())
    val ui: StateFlow<Status> = status

    @Synchronized
    fun initialize() { load() }

    @Synchronized
    fun refresh(owner: String) = display(forOwner(owner))

    @Synchronized
    fun begin(owner: String, allowed: () -> Boolean = { true }) {
        check(allowed())
        val state = forOwner(owner)
        check(state.optJSONObject("active") == null && request == null)
        window = Window(owner, now() + WINDOW_MS)
        display(state)
    }

    @Synchronized
    fun hello(owner: String, watchId: String, name: String): Hello {
        val state = forOwner(owner)
        val active = state.optJSONObject("active")
        if (active != null) {
            return if (active.getString("watchId") == watchId) Hello(Mode.SYNC, revision)
            else Hello(Mode.REJECTED, revision, otherBound = true)
        }
        if (resets(state).contains(watchId)) return Hello(Mode.RESET, revision)
        if (window == null) return Hello(Mode.REJECTED, revision)
        window = null
        val candidate = Request(watchId, name, owner, now() + WINDOW_MS)
        request = candidate
        display(state)
        return Hello(Mode.PENDING, revision, candidate)
    }

    @Synchronized
    fun decide(candidate: Request, allow: Boolean, owner: String?, allowed: () -> Boolean = { true }) {
        if (request !== candidate) return
        val state = load()
        if (allow && allowed() && candidate.owner == owner && now() < candidate.expiresAt) {
            check(state.optJSONObject("active") == null)
            state.put("active", JSONObject().put("watchId", candidate.watchId)
                .put("name", candidate.name).put("owner", candidate.owner))
            persist(state)
            finish(true)
        } else {
            finish(false)
        }
        display(state)
    }

    fun remaining(candidate: Request): Long = (candidate.expiresAt - now()).coerceAtLeast(0)

    @Synchronized
    fun cancel() {
        if (window == null && request == null) return
        window = null
        finish(false)
        status.value = status.value.copy(waiting = false, request = null)
    }

    @Synchronized
    fun revoke() {
        cancel()
        revision++
        val state = load()
        retire(state)
        display(state)
    }

    @Synchronized
    fun <T> withActive(owner: String, watchId: String, run: () -> T): T {
        check(forOwner(owner).optJSONObject("active")?.getString("watchId") == watchId)
        return run()
    }

    @Synchronized
    fun forget(owner: String, watchId: String, mode: Mode) {
        val state = forOwner(owner)
        when (mode) {
            Mode.SYNC -> {
                check(state.optJSONObject("active")?.getString("watchId") == watchId)
                state.remove("active")
            }
            Mode.RESET -> {
                val ids = resets(state)
                check(state.optJSONObject("active") == null && watchId in ids)
                state.put("resets", JSONArray(ids - watchId))
            }
            else -> error("invalid_mode")
        }
        persist(state)
        display(state)
    }

    private fun forOwner(owner: String): JSONObject {
        val state = load()
        var changed = false
        if (observedOwner?.let { it != owner } == true ||
            state.optJSONObject("active")?.getString("owner")?.let { it != owner } == true) {
            cancel()
            revision++
            retire(state)
            changed = true
        }
        observedOwner = owner
        if (window?.let { it.owner != owner || now() >= it.expiresAt } == true) {
            window = null
            changed = true
        }
        if (request?.let { now() >= it.expiresAt } == true) {
            finish(false)
            changed = true
        }
        if (changed) display(state)
        return state
    }

    private fun finish(allow: Boolean) {
        val candidate = request
        request = null
        candidate?.decision?.complete(allow)
    }

    private fun retire(state: JSONObject) {
        val active = state.remove("active") as? JSONObject ?: return
        val id = active.getString("watchId")
        state.put("resets", JSONArray((resets(state) - id + id).takeLast(8)))
        persist(state)
    }

    private fun resets(state: JSONObject): List<String> {
        val ids = state.optJSONArray("resets") ?: return emptyList()
        return List(ids.length(), ids::getString)
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
            cancel()
            status.value = Status()
            throw error
        }
    }

    private fun display(state: JSONObject) {
        status.value = Status(state.optJSONObject("active")?.getString("name"),
            window != null, request, resets(state).isNotEmpty())
    }

    companion object {
        private const val WINDOW_MS = 120_000L
        private const val LEGACY_STORE = "watchrss-watchs-pairings"

        // Use a synchronous commit before sending ready or forgotten.
        @SuppressLint("UseKtx")
        fun android(context: Context): WatchSBleBindings {
            val prefs by lazy {
                context.deleteSharedPreferences(LEGACY_STORE)
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    if (containsAlias(LEGACY_STORE)) deleteEntry(LEGACY_STORE)
                }
                context.getSharedPreferences("watchrss-watchs-bindings", Context.MODE_PRIVATE)
            }
            return WatchSBleBindings(SystemClock::elapsedRealtime,
                { JSONObject(prefs.getString("bindings", "{}") ?: "{}") },
                { check(prefs.edit().putString("bindings", it.toString()).commit()) })
        }
    }
}
