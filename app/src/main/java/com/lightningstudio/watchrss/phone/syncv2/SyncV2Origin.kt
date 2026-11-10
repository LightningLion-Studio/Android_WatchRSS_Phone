package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The authenticated v1 backend is the authority. There is no runtime loopback override. */
internal object SyncV2Origin {
    const val path = "/functions/v1/sync/v2/"
    private val loopback = setOf("localhost", "127.0.0.1", "::1")

    fun endpoint(backendBaseUrl: String): HttpUrl {
        val raw = try { java.net.URI(backendBaseUrl) } catch (_:Exception) { bad() }
        val backend = backendBaseUrl.toHttpUrlOrNull() ?: bad()
        if (raw.rawUserInfo != null || raw.host == null) bad()
        // HttpUrl canonicalizes IPv4-mapped IPv6 and numeric IPv4 aliases. They do
        // not widen the exact loopback exception in the configured v1 authority.
        val literal = raw.host.removePrefix("[").removeSuffix("]")
        if (backend.host in setOf("127.0.0.1", "localhost") && literal != backend.host) bad()
        clean(backend)
        if (backend.encodedPath != "/") bad()
        return backend.newBuilder().encodedPath(path).build().also { validate(it, backend) }
    }

    fun validate(base: HttpUrl, authenticatedBackend: HttpUrl?) {
        clean(base)
        if (base.encodedPath != path) bad()
        if (base.host in loopback) {
            if (!BuildConfig.DEBUG) bad()
        } else if (!base.isHttps || authenticatedBackend == null) bad()
        authenticatedBackend?.let {
            clean(it)
            if (it.encodedPath != "/" || base.host != it.host || base.port != it.port || base.scheme != it.scheme) bad()
        }
    }

    private fun clean(url: HttpUrl) {
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) bad()
    }
    private fun bad(): Nothing = SyncV2Codec.fail("isolated_backend_required")
}
