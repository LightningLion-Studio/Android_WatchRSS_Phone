package com.lightningstudio.watchrss.phone.rollout

import android.content.Context
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ClientConfig(
    val version: Long = 0,
    val ttlSeconds: Long = 3600,
    val features: Map<String, FeatureRollout> = emptyMap()
) {
    fun rollout(feature: RegisteredFeature) = features[feature.key] ?: feature.defaults
}

internal class ClientConfigRepository(
    context: Context,
    environment: AccountEnvironment,
    private val installId: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "watchrss_client_config${environment.storageSuffix}", Context.MODE_PRIVATE
    )
    private val endpoint = "${environment.backendBaseUrl}/functions/v1/client-config".toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("platform", "phone")
        ?.addQueryParameter("appVersionCode", BuildConfig.VERSION_CODE.toString())
        ?.addQueryParameter("installId", installId)
        ?.build()
    private data class Cached(val config: ClientConfig, val fetchedAt: Long)
    @Volatile private var cached = readCache()
    private var inFlight: Deferred<Unit>? = null

    fun debugOverride(feature: RegisteredFeature): FeatureOverride? =
        if (BuildConfig.DEBUG || feature.releaseDebugOverride) {
            FeatureOverride.entries.firstOrNull { it.wire == prefs.getString("debug_override_${feature.key}", null) }
        } else null

    fun setDebugOverride(feature: RegisteredFeature, value: FeatureOverride?) {
        if (BuildConfig.DEBUG || feature.releaseDebugOverride) {
            prefs.edit().putString("debug_override_${feature.key}", value?.wire).apply()
        }
    }

    @Synchronized
    fun refreshIfStale(): Deferred<Unit>? {
        val url = endpoint ?: return null
        val snapshot = cached
        if (snapshot != null && now() - snapshot.fetchedAt in 0 until snapshot.config.ttlSeconds * 1000L) {
            return null
        }
        inFlight?.takeIf { !it.isCompleted }?.let { return it }
        return scope.async(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).withWatchRssAppVersionHeader().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.peekBody(16_385).string()
                    if (body.length > 16_384) return@use
                    val json = JSONObject(body)
                    val config = parseClientConfig(json)
                    if (config.version < (cached?.config?.version ?: 0)) return@use
                    val fetchedAt = now()
                    cached = Cached(config, fetchedAt)
                    prefs.edit().putString("last_config", json.toString()).putString("install_id", installId)
                        .putLong("fetched_at", fetchedAt).apply()
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

    suspend fun snapshotForSession(): ClientConfig {
        val refresh = refreshIfStale()
        if (refresh != null) withTimeoutOrNull(1500L) { refresh.await() }
        return currentSnapshot()
    }

    fun currentSnapshot(): ClientConfig = cached?.config ?: ClientConfig()

    fun decide(config: ClientConfig, feature: RegisteredFeature, eligible: Boolean) =
        FeatureRolloutPolicy.decide(config.rollout(feature), installId, eligible,
            BuildConfig.VERSION_CODE, debugOverride(feature))

    private fun readCache(): Cached? = try {
        prefs.getString("last_config", null)?.takeIf { prefs.getString("install_id", null) == installId }?.let {
            Cached(parseClientConfig(JSONObject(it)), prefs.getLong("fetched_at", 0L))
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
        ttlSeconds = integer(json, "ttlSeconds", 0, Long.MAX_VALUE / 1000),
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
