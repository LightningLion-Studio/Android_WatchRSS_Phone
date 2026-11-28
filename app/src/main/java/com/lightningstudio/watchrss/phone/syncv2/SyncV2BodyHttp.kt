package com.lightningstudio.watchrss.phone.syncv2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Durable ciphertext checkpoints. No legacy business routes or business ACK API. */
internal class SyncV2BodyHttp(private val client:SyncV2CloudClient,private val check:()->Unit) {
    private val scope get()=client.scope
    @Volatile private var legacyDownloadPartSize = false
    private fun json(method: String, path: String, body: JSONObject? = null,
        query: Map<String, String> = emptyMap(), beforeRequest: () -> Unit = {}): JSONObject =
        jsonResponse(method, path, body, query, beforeRequest).first

    private fun jsonResponse(method: String, path: String, body: JSONObject?,
        query: Map<String, String> = emptyMap(), beforeRequest: () -> Unit): Pair<JSONObject, Map<String, String>> {
        check()
        val (raw, headers) = client.bodyBytes(method, path, body?.let { SyncV2Codec.utf8(SyncV2Codec.canonical(it)) },
            query = query, beforeRequest = beforeRequest)
        val value = SyncV2Wire.parse(raw, 1048576)
        if (value.get("version") != 1 || value.get("purpose") != SyncV2BodyCodec.PURPOSE ||
            SyncV2Codec.scope(value.getJSONObject("scope")) != SyncV2Codec.scope(scope)) SyncV2Codec.fail("invalid_schema")
        return value to headers
    }
    fun list(entity:String,revision:String,after:String=""):JSONObject {
        SyncV2Codec.unhex(entity,32);SyncV2Codec.unhex(revision,16);if(after.isNotEmpty())SyncV2Codec.unhex(after,16)
        val v=json("GET","carriers",query=mapOf("entityKey" to entity,"bodyRevision" to revision,"limit" to "20")+if(after.isEmpty())emptyMap()else mapOf("after" to after))
        SyncV2Codec.exact(v,setOf("version","purpose","scope","carriers","nextAfter"))
        val a=v.getJSONArray("carriers");if(a.length()>20)SyncV2Codec.fail("invalid_schema");var previous=after
        for(i in 0 until a.length()) {val c=a.getJSONObject(i);SyncV2Codec.exact(c,setOf("carrierId","descriptor"));val b=SyncV2BodyCodec.validate(c.getJSONObject("descriptor"),scope)
            val id=c.getString("carrierId");if(id<=previous || id!=b.getString("carrierId") || b.getString("entityKey")!=entity || b.getString("bodyRevision")!=revision)SyncV2Codec.fail("invalid_schema");previous=id}
        if(!v.isNull("nextAfter")&&(v.getString("nextAfter")!=previous || previous==after))SyncV2Codec.fail("invalid_schema")
        return v
    }
    private data class UploadStatus(val partBytes: Int, val offsets: Map<String, Int>?)

    private fun uploadStatus(path: String, body: JSONObject, id: String, expected: Map<String, Int>,
        beforeTransfer: () -> Unit): UploadStatus {
        val (reply, headers) = jsonResponse("POST", path, body, beforeRequest = beforeTransfer)
        SyncV2Codec.exact(reply, setOf("version", "purpose", "scope", "carrierId", "state", "expiresAtMillis", "objects"))
        val state = reply.getString("state")
        if (reply.getString("carrierId") != id || state !in setOf("reserved", "ready")) SyncV2Codec.fail("invalid_schema")
        SyncV2Wire.uint(reply, "expiresAtMillis")
        val partBytes = headers.entries.firstOrNull { it.key.equals("x-watchrss-max-body-part-bytes", true) }
            ?.value?.toIntOrNull()?.takeIf { it in PART_ALIGNMENT..MAX_PART_BYTES && it % PART_ALIGNMENT == 0 }
            ?: PART_ALIGNMENT
        if (state == "ready") return UploadStatus(partBytes, null)

        val objects = reply.getJSONArray("objects")
        if (objects.length() != expected.size) SyncV2Codec.fail("closure_incomplete")
        val offsets = linkedMapOf<String, Int>()
        for (i in 0 until objects.length()) {
            val item = objects.getJSONObject(i)
            SyncV2Codec.exact(item, setOf("sha256", "sizeBytes", "offset"))
            val hash = item.getString("sha256")
            val size = expected[hash] ?: SyncV2Codec.fail("closure_incomplete")
            if (SyncV2Wire.uint(item, "sizeBytes") != size.toLong() || hash in offsets) SyncV2Codec.fail("closure_incomplete")
            val offset = SyncV2Wire.uint(item, "offset", size.toLong()).toInt()
            if (offset != size && offset % PART_ALIGNMENT != 0) SyncV2Codec.fail("invalid_range")
            offsets[hash] = offset
        }
        return UploadStatus(partBytes, offsets)
    }

    fun upload(descriptor: JSONObject, directory: File, beforeTransfer: () -> Unit = {}) {
        val body = SyncV2BodyCodec.validate(descriptor, scope)
        val id = body.getString("carrierId")
        val expected = SyncV2BodyCodec.objects(body).associate { it.getString("sha256") to it.getInt("sizeBytes") }
        var status = uploadStatus("reserve", descriptor, id, expected, beforeTransfer)
        var legacyLimit = false
        var retriesWithoutProgress = 0
        while (true) {
            val offsets = status.offsets ?: return
            val partBytes = if (legacyLimit) PART_ALIGNMENT else status.partBytes
            val error = uploadObjects(id, directory, expected, offsets, partBytes, beforeTransfer) ?: break
            if (error.rejectsLargePart()) legacyLimit = true
            status = uploadStatus("carriers/$id/missing", JSONObject().put("scope", scope), id, expected, beforeTransfer)
            val resumed = status.offsets ?: return
            retriesWithoutProgress = if (resumed == offsets) retriesWithoutProgress + 1 else 0
            if (retriesWithoutProgress >= 3) throw error
        }

        val done = json("POST", "carriers/$id/complete", JSONObject().put("scope", scope), beforeRequest = beforeTransfer)
        SyncV2Codec.exact(done, setOf("version", "purpose", "scope", "carrierId", "state"))
        if (done.getString("carrierId") != id || done.getString("state") != "ready") SyncV2Codec.fail("closure_incomplete")
    }

    private fun uploadObjects(id: String, directory: File, expected: Map<String, Int>, offsets: Map<String, Int>,
        partBytes: Int, beforeTransfer: () -> Unit): SyncV2CloudError? {
        val pending = offsets.keys.filter { offsets.getValue(it) < expected.getValue(it) }
        val retry = AtomicReference<SyncV2CloudError>()
        val transferLock = Any()
        fun send(hash: String, checkWorker: () -> Unit) {
            val raw = File(directory, hash).readBytes()
            if (raw.size != expected.getValue(hash) || SyncV2BodyCodec.hash(raw) != hash) SyncV2Codec.fail("hash_mismatch")
            var offset = offsets.getValue(hash)
            while (offset < raw.size && retry.get() == null) {
                val part = raw.copyOfRange(offset, minOf(raw.size, offset + partBytes))
                val s = scope
                try {
                    client.bodyBytes("PUT", "carriers/$id/objects/$hash/parts/$offset", part, mapOf(
                        "x-watchrss-sync-part-sha256" to SyncV2BodyCodec.hash(part),
                        "x-watchrss-sync-repo-id" to s.getString("repoId"),
                        "x-watchrss-sync-library-epoch" to s.getString("libraryEpoch"),
                        "x-watchrss-sync-content-generation" to s.getString("contentGeneration")
                    ), beforeRequest = {
                        synchronized(transferLock) {
                            checkWorker()
                            check()
                            beforeTransfer()
                        }
                    })
                } catch (error: SyncV2CloudError) {
                    val downgrade = part.size > PART_ALIGNMENT && error.rejectsLargePart()
                    if (!downgrade && error.code !in setOf("offset_conflict", "part_conflict")) throw error
                    // A size rejection takes precedence over a conflict from another in-flight PUT.
                    if (downgrade) retry.set(error) else retry.compareAndSet(null, error)
                }
                offset += part.size
            }
        }

        if (partBytes == PART_ALIGNMENT || pending.size < 2) {
            for (hash in pending) {
                if (retry.get() != null) break
                send(hash) {}
            }
        } else {
            val workerContext = client.bodyUploadContext()
            runBlocking {
                val workerCount = minOf(UPLOAD_CONCURRENCY, pending.size)
                List(workerCount) { worker ->
                    async(Dispatchers.IO + workerContext) {
                        for (i in worker until pending.size step workerCount) {
                            ensureActive()
                            if (retry.get() != null) break
                            send(pending[i]) { ensureActive() }
                        }
                    }
                }.awaitAll()
            }
        }
        return retry.get()
    }

    private fun SyncV2CloudError.rejectsLargePart() =
        httpStatus == 413 || (httpStatus == 400 && code == "object_too_large")

    private fun SyncV2CloudError.rejectsDownloadPartSize() =
        httpStatus == 400 && code == "invalid_schema"

    fun download(id:String,hash:String,size:Int,directory:File,beforeTransfer:()->Unit={}):ByteArray {
        SyncV2Codec.unhex(id,16);SyncV2Codec.unhex(hash,32);if(size !in 1..16777216)SyncV2Codec.fail("invalid_schema")
        directory.mkdirs();val target=File(directory,hash);val partial=File(directory,"$hash.part")
        if(target.isFile && target.length()==size.toLong())target.readBytes().let{if(SyncV2BodyCodec.hash(it)==hash){removeCompletedPart(partial);return it}}
        if(partial.length()>size)atomic(partial,byteArrayOf())
        var at=partial.length().toInt()
        while(at<size) {
            check()
            val (bytes, headers) = readDownloadPart(id, hash, at, beforeTransfer)
            fun header(k:String)=headers.entries.firstOrNull{it.key.equals(k,true)}?.value
            val validSize = bytes.isNotEmpty() && bytes.size <= minOf(MAX_PART_BYTES, size - at) &&
                (at + bytes.size == size || bytes.size % PART_ALIGNMENT == 0)
            if(header("x-watchrss-body-total-bytes")!=size.toString() || header("x-watchrss-body-offset")!=at.toString() || !validSize)SyncV2Codec.fail("invalid_range")
            FileOutputStream(partial,true).use{it.write(bytes);it.fd.sync()};at+=bytes.size
        }
        val raw=partial.readBytes();if(raw.size!=size || SyncV2BodyCodec.hash(raw)!=hash){atomic(partial,byteArrayOf());SyncV2Codec.fail("hash_mismatch")}
        atomic(target,raw);removeCompletedPart(partial);return raw
    }

    private fun readDownloadPart(id: String, hash: String, offset: Int,
        beforeTransfer: () -> Unit): Pair<ByteArray, Map<String, String>> {
        val path = "carriers/$id/objects/$hash"
        val baseQuery = mapOf("offset" to offset.toString())
        if (legacyDownloadPartSize) {
            return client.bodyBytes("GET", path, query = baseQuery, raw = true, beforeRequest = beforeTransfer)
        }
        return try {
            client.bodyBytes("GET", path,
                query = baseQuery + ("partSize" to MAX_PART_BYTES.toString()),
                raw = true, beforeRequest = beforeTransfer)
        } catch (error: SyncV2CloudError) {
            if (!error.rejectsDownloadPartSize()) throw error
            legacyDownloadPartSize = true
            check()
            client.bodyBytes("GET", path, query = baseQuery, raw = true, beforeRequest = beforeTransfer)
        }
    }
    private fun removeCompletedPart(partial:File) {
        if(partial.exists()) {
            check(partial.delete()){"body_cache_cleanup_failed"}
            java.nio.channels.FileChannel.open(partial.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        }
    }
    companion object {
        private const val PART_ALIGNMENT = 65_536
        private const val MAX_PART_BYTES = 524_288
        private const val UPLOAD_CONCURRENCY = 3

        fun atomic(file:File,raw:ByteArray) {
            file.parentFile!!.mkdirs();val tmp=File.createTempFile(".body-",".tmp",file.parentFile)
            try{FileOutputStream(tmp).use{it.write(raw);it.fd.sync()};java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);java.nio.channels.FileChannel.open(file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}}finally{tmp.delete()}
        }
    }
}
