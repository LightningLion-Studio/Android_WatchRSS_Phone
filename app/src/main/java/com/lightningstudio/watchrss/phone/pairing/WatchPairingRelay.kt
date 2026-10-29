package com.lightningstudio.watchrss.phone.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

internal interface PairingHttp {
    suspend fun call(method: String, path: String, body: String?, capability: String?, timeoutMillis: Long = 8000): JSONObject
}
internal class PairingHttpException(val status: Int, val code: String) : IOException("配对服务暂不可用（$status）")
interface WatchPairingChannel {
    suspend fun connect()
    suspend fun request(message: JSONObject): JSONObject
    fun negotiateUx(receipt:JSONObject) {}
    fun invalidate()
    suspend fun close()
}

/** A relay owns one capability and one sequence. Ambiguous retries reuse the exact original body. */
internal class WatchPairingRelay(
    private val qr: WatchPairingQr, private val http: PairingHttp,
    private val checkOwner: () -> Unit, private val mayClose: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    random: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) }
) : WatchPairingChannel {
    private val mutex=Mutex()
    private val base="/functions/v1/watch-pairing/sessions/${qr.sessionId}"
    private var phoneToken= random(32).joinToString("") { "%02x".format(it) }
    @Volatile private var closed=false
    private val closeSent=AtomicBoolean(false)
    private var claimAttempted=false; private var claimed=false
    private var sequence=0
    private var maxSequence=15
    private var pendingPlain: String? = null
    private var pendingBody: String? = null
    private fun check() { if(closed) throw PairingInvalidated(); if(now() >= qr.expiresAt) throw PairingException("pairing_expired","配对码已过期，请重新扫描"); checkOwner() }
    private suspend fun send(method:String,suffix:String,body:String?):JSONObject {
        for(attempt in 0..2) {
            check()
            try { val result=http.call(method,base+suffix,body,phoneToken);check();return result }
            catch(error:IOException) {
                check()
                if(attempt==2 || (error is PairingHttpException && error.status < 500)) throw error
                sleep(500L*(attempt+1))
            }
        }
        error("unreachable")
    }
    override suspend fun connect() = mutex.withLock {
        check();if(claimed)return@withLock
        val body=JSONObject().put("claimToken",qr.claimToken).put("phoneToken",phoneToken).toString()
        claimAttempted=true
        val result=send("POST","/claim",body)
        require(result.optBoolean("claimed")) { "配对认领未确认" };claimed=true
    }
    override suspend fun request(message:JSONObject):JSONObject = mutex.withLock {
        check();check(claimed && sequence in 0..maxSequence) { "请重新扫描配对码" }
        val text=message.toString()
        if(pendingBody==null) {
            pendingPlain=text
            val wire=PairingWireCodec.seal(qr,"phone",sequence,message,maxSequence)
            pendingBody=JSONObject().put("seq",sequence).put("wire",wire).toString()
        } else require(pendingPlain==text) { "上一条配对消息尚未确认，请重试原操作" }
        val deadline=minOf(qr.expiresAt,now()+110_000)
        send("POST","/request",requireNotNull(pendingBody))
        while(now()<deadline) {
            check()
            val result=send("GET","/response?seq=$sequence",null)
            if(result.has("wire") && !result.isNull("wire")) {
                val plain=PairingWireCodec.open(qr,"watch",sequence,result.getJSONObject("wire"),maxSequence);check()
                sequence++;pendingPlain=null;pendingBody=null
                if(plain.opt("success")!=true) throw PairingPeerException(plain.optString("errorCode").ifBlank { plain.optString("error").ifBlank { plain.optString("code") } })
                return@withLock plain
            }
            sleep(1200)
        }
        throw PairingException("pairing_timeout","等待手表确认超时，请重试；若配对码过期请重新扫描")
    }
    override fun negotiateUx(receipt:JSONObject) {
        check();check(sequence==1 && pendingBody==null)
        PairingUxCodec.requireSupport(receipt);maxSequence=PairingUxCodec.SEQUENCE_MAX
    }
    override fun invalidate() { closed=true;qr.destroy() }
    override suspend fun close() {
        if(!closeSent.compareAndSet(false,true))return
        invalidate()
        val token=phoneToken;phoneToken="";pendingPlain=null;pendingBody=null
        if(claimAttempted && token.isNotBlank() && mayClose()) {
            try { http.call("POST",base+"/close","{}",token,5000) }
            catch(error:CancellationException) { throw error }
            catch(_:Exception) { /* The server TTL also removes interrupted sessions. */ }
        }
    }
    override fun toString():String="WatchPairingRelay(redacted)"
}
