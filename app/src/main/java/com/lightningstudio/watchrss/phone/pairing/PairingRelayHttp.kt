package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only the configured backend receives login/app-access/capability credentials; redirects are disabled. */
internal class PairingRelayHttp(
    private val environment: AccountEnvironment, private val session: () -> PhoneAccountSession,
    private val deviceToken: () -> String?,
    private val refreshDeviceSession: suspend (String,String) -> Boolean,
    private val http: OkHttpClient = OkHttpClient()
):PairingHttp {
    override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject = withContext(Dispatchers.IO) {
        require(path.matches(Regex("/functions/v1/watch-pairing/sessions/[A-Za-z0-9_-]{22}/(?:claim|request|close|response\\?seq=(?:[0-9]|1[0-5]))")))
        val owner=session();require(!owner.isExpired) { "请先重新登录账号" }
        fun build():Request {
            val current=session();check(current.userId==owner.userId) { "账号已切换，请重新配对" }
            return Request.Builder().url(environment.backendBaseUrl.trimEnd('/')+path).withWatchRssAppVersionHeader()
                .header("authorization","Bearer ${current.accessToken}").header("apikey",environment.supabaseAnonKey)
                .header("cache-control","no-store").apply {
                    deviceToken()?.let { header("x-watchrss-device-authorization","Bearer $it") }
                    capability?.let { header("x-watchrss-pairing-token",it) }
                    if(method=="GET") get() else {require(method=="POST");post(requireNotNull(body).toRequestBody("application/json".toMediaType()))}
                }.build()
        }
        val client=http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(timeoutMillis,TimeUnit.MILLISECONDS).build()
        var req=build();var response=client.newCall(req).execute()
        val rejected=req.header("x-watchrss-device-authorization")?.removePrefix("Bearer ")
        val errorCode=runCatching { JSONObject(response.peekBody(4096).string()).optString("error") }.getOrDefault("")
        if(response.code==403 && rejected!=null && errorCode in setOf("device_session_revoked","device_session_expired")) {
            response.close()
            check(refreshDeviceSession(rejected,owner.userId)) { "请先在账号页面重新验证手机身份" }
            req=build();response=client.newCall(req).execute()
        }
        response.use {
            val text=it.body?.string().orEmpty()
            if(!it.isSuccessful) throw PairingHttpException(it.code,runCatching{JSONObject(text).optString("error")}.getOrDefault(""))
            check(session().userId==owner.userId) { "账号已切换，请重新配对" }
            if(text.isBlank())JSONObject()else JSONObject(text)
        }
    }
}
