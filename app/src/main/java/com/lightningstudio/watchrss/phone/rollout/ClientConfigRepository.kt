package com.lightningstudio.watchrss.phone.rollout

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.features.EvaluateAt
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.features.FeatureRollout
import com.lightningstudio.watchrss.phone.features.FeatureRolloutPolicy
import com.lightningstudio.watchrss.phone.features.RegisteredFeature
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MAX_CONFIG_TTL_SECONDS = 600L
private const val CONFIG_EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000
internal const val SESSION_CONFIG_WAIT_MS = 1500L
// FIX1 could persist an elapsed anchor reconstructed from wall time.
private const val FETCHED_ELAPSED = "fetched_elapsed_at_download"
private const val FETCHED_BOOT = "fetched_boot_at_download"

internal data class ClientConfig(
    val version: Long = 0,
    val ttlSeconds: Long = MAX_CONFIG_TTL_SECONDS,
    val features: Map<String, FeatureRollout> = emptyMap(),
    val stale: Boolean = false,
    val cacheLoaded: Boolean = true
) {
    fun rollout(feature: RegisteredFeature) =
        if (!cacheLoaded && feature.evaluateAt == EvaluateAt.SESSION) feature.defaults.copy(percent = 0)
        else features[feature.key] ?: feature.defaults
}

internal class ClientConfigRepository(
    context: Context,
    environment: AccountEnvironment,
    private val installId: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val endpoint = "${environment.backendBaseUrl}/functions/v1/client-config".toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("platform", "phone")
        ?.addQueryParameter("appVersionCode", BuildConfig.VERSION_CODE.toString())
        ?.addQueryParameter("installId", installId)
        ?.build()
    private data class Cached(val config: ClientConfig, val elapsedAnchor: Long?) {
        private fun age() = elapsedAnchor?.let { SystemClock.elapsedRealtime() - it }

        fun isFresh() = age()?.let { it in 0 until config.ttlSeconds * 1000L } == true
        fun isExpired() = age()?.let { it !in 0..CONFIG_EXPIRATION_MS } != false
    }

    private data class Storage(val prefs: SharedPreferences, val boot: Int)
    private data class CacheSnapshot(val loaded: Boolean, val cached: Cached? = null)

    @Volatile private var cache = CacheSnapshot(loaded = false)
    @Volatile private var debugOverrides = emptyMap<String, FeatureOverride>()
    private var inFlight: Deferred<Unit>? = null
    private val storage = scope.async(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(
            "watchrss_client_config${environment.storageSuffix}", Context.MODE_PRIVATE
        )
        val boot = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        debugOverrides = FeatureRegistry.entries.filter(::supportsDebugOverride).mapNotNull { feature ->
            val wire = prefs.getString("debug_override_${feature.key}", null)
            FeatureOverride.entries.firstOrNull { it.wire == wire }?.let { feature.key to it }
        }.toMap()
        cache = CacheSnapshot(loaded = true, cached = readCache(prefs, boot))
        Storage(prefs, boot)
    }

    private fun supportsDebugOverride(feature: RegisteredFeature) = BuildConfig.DEBUG || feature.releaseDebugOverride

    suspend fun loadDebugOverride(feature: RegisteredFeature): FeatureOverride? {
        storage.await()
        return debugOverride(feature)
    }

    private fun debugOverride(feature: RegisteredFeature) =
        debugOverrides[feature.key].takeIf { supportsDebugOverride(feature) }

    suspend fun setDebugOverride(feature: RegisteredFeature, value: FeatureOverride?) {
        if (!supportsDebugOverride(feature)) return
        val prefs = storage.await().prefs
        withContext(Dispatchers.IO) {
            synchronized(prefs) {
                prefs.edit { putString("debug_override_${feature.key}", value?.wire) }
                debugOverrides = if (value == null) debugOverrides - feature.key
                    else debugOverrides + (feature.key to value)
            }
        }
    }

    @Synchronized
    fun refreshIfStale(): Deferred<Unit>? {
        val url = endpoint ?: return null
        if (cache.cached?.isFresh() == true) return null
        inFlight?.takeIf { !it.isCompleted }?.let { return it }
        return scope.async(Dispatchers.IO) {
            val (prefs, boot) = storage.await()
            if (cache.cached?.isFresh() == true) return@async
            try {
                val request = Request.Builder().url(url).withWatchRssAppVersionHeader().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.peekBody(16_385).string()
                    if (body.length > 16_384) return@use
                    val json = JSONObject(body)
                    val config = parseClientConfig(json)
                    if (config.version < (cache.cached?.config?.version ?: 0)) return@use
                    val fetchedAt = now()
                    val elapsed = SystemClock.elapsedRealtime()
                    prefs.edit {
                        putString("last_config", json.toString())
                        putString("install_id", installId)
                        putLong("fetched_at", fetchedAt)
                        putLong(FETCHED_ELAPSED, elapsed)
                        putInt(FETCHED_BOOT, boot)
                    }
                    cache = CacheSnapshot(loaded = true, cached = Cached(config, elapsed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // The last successful snapshot stays usable when offline.
            } catch (_: JSONException) {
                // An invalid response cannot replace a valid snapshot.
            }
            Unit
        }.also { inFlight = it }
    }

    // The caller owns the deadline so it can include repository initialization.
    suspend fun awaitSessionConfig(waitForRefresh: Boolean) {
        val pending = if (waitForRefresh) refreshIfStale() ?: storage else storage
        pending.await()
    }

    suspend fun snapshotForSession(waitForRefresh: Boolean = true): ClientConfig {
        withTimeoutOrNull(SESSION_CONFIG_WAIT_MS) { awaitSessionConfig(waitForRefresh) }
        return currentSnapshot()
    }

    fun currentSnapshot(): ClientConfig {
        val observed = cache
        val snapshot = observed.cached ?: return ClientConfig(cacheLoaded = observed.loaded)
        return if (snapshot.isExpired()) snapshot.config.copy(stale = true) else snapshot.config
    }

    fun decide(config: ClientConfig, feature: RegisteredFeature, eligible: Boolean) =
        FeatureRolloutPolicy.decide(config.rollout(feature), installId, eligible,
            BuildConfig.VERSION_CODE, debugOverride(feature), stale = config.stale && feature.evaluateAt == EvaluateAt.SESSION)

    private fun readCache(prefs: SharedPreferences, boot: Int): Cached? = try {
        prefs.getString("last_config", null)?.takeIf { prefs.getString("install_id", null) == installId }?.let {
            val config = parseClientConfig(JSONObject(it))
            val savedAnchor = prefs.getLong(FETCHED_ELAPSED, -1L)
            val sameBoot = boot >= 0 && boot == prefs.getInt(FETCHED_BOOT, -1)
            // Once the boot changes there is no trustworthy elapsed age until a refresh.
            val anchor = savedAnchor.takeIf { sameBoot && it in 0..SystemClock.elapsedRealtime() }
            Cached(config, anchor)
        }
    } catch (_: JSONException) {
        null
    }
}

internal fun parseClientConfig(json: JSONObject): ClientConfig {
    fun integer(parent: JSONObject, field: String, min: Long, max: Long): Long {
        val value = parent.get(field)
        if (value !is Int && value !is Long) throw JSONException("Invalid $field")
        return (value as Number).toLong().also {
            if (it !in min..max) throw JSONException("Invalid $field")
        }
    }
    val features = json.getJSONObject("features")
    val rollouts = FeatureRegistry.entries.mapNotNull { feature ->
        if (!features.has(feature.key)) return@mapNotNull null
        feature.key to parseFeature(features.getJSONObject(feature.key), ::integer)
    }.toMap()
    return ClientConfig(
        version = integer(json, "configVersion", 0, 9_007_199_254_740_991L),
        ttlSeconds = integer(json, "ttlSeconds", 0, Long.MAX_VALUE).coerceAtMost(MAX_CONFIG_TTL_SECONDS),
        features = rollouts
    )
}

private fun parseFeature(
    rollout: JSONObject,
    integer: (JSONObject, String, Long, Long) -> Long
): FeatureRollout {
    val salt = rollout.get("salt") as? String ?: throw JSONException("Invalid salt")
    if (salt.isBlank() || salt.toByteArray(Charsets.UTF_8).size > 128 || salt.any(Char::isISOControl)) throw JSONException("Invalid salt")
    val forced = if (rollout.isNull("forced")) null else {
        FeatureOverride.entries.firstOrNull { it.wire == rollout.get("forced") }
            ?: throw JSONException("Invalid forced")
    }
    val minCode = integer(rollout, "minAppVersionCode", 0, Int.MAX_VALUE.toLong()).toInt()
    val maxCode = if (rollout.isNull("maxAppVersionCode")) null else {
        integer(rollout, "maxAppVersionCode", minCode.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
    return FeatureRollout(integer(rollout, "percent", 0, 100).toInt(), salt, minCode, maxCode, forced)
}
