package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Optional, per-session flags. Missing declarations always select the old wire behavior. */
internal data class BleSyncCapabilities(
    val noteManifest: Boolean=false, val noteAssets: Boolean=false,
    val canonicalRssIds: Boolean=false, val fullMetadataOnly: Boolean=false,
    val tokenCursor: Boolean=false, val books:Boolean=false, val bookReadingDelta:Boolean=false
) {
    fun withDeclarations(value:JSONObject):BleSyncCapabilities {
        fun flag(key:String,previous:Boolean)=if(value.has(key))value.opt(key)==true else previous
        return BleSyncCapabilities(flag("supportsNoteManifestV2",noteManifest),flag("supportsNoteAssetInventory",noteAssets),
            flag("supportsRssCanonicalIds",canonicalRssIds),flag("supportsFullMetadataOnlyArticles",fullMetadataOnly),
            flag("supportsTokenUsageCursor",tokenCursor),flag("supportsBookSyncV1",books),flag("supportsBookReadingDeltaV1",bookReadingDelta))
    }
    companion object { fun read(value:JSONObject)=BleSyncCapabilities().withDeclarations(value) }
}
internal fun JSONObject.advertiseBleSyncV2():JSONObject=apply {
    listOf("supportsNoteManifestV2","supportsNoteAssetInventory","supportsRssCanonicalIds",
        "supportsFullMetadataOnlyArticles","supportsTokenUsageCursor","supportsBookSyncV1","supportsBookReadingDeltaV1").forEach { put(it,true) }
}

internal data class NoteAssetInventoryEntry(val storageKey:String,val sha256:String,val size:Long) {
    fun json()=JSONObject().put("storageKey",storageKey).put("sha256",sha256).put("size",size)
    companion object {
        // Bad inventories cannot prove presence. Fall back to the existing chunk transfer.
        fun read(array:JSONArray?):Map<String,NoteAssetInventoryEntry> =
            runCatching { readVerified(array) }.getOrDefault(emptyMap())

        private fun readVerified(array:JSONArray?):Map<String,NoteAssetInventoryEntry> {
            val result=linkedMapOf<String,NoteAssetInventoryEntry>()
            for(i in 0 until (array?.length()?:0)) {
                val row=requireNotNull(array).getJSONObject(i)
                val key=row.getString("storageKey");val sha=row.getString("sha256");val rawSize=row.get("size");require(rawSize is Int || rawSize is Long);val size=(rawSize as Number).toLong()
                require(NoteAssetSyncPayload.isSafeStorageKey(key) && sha.matches(Regex("[0-9a-f]{64}")) && size in 0..9_007_199_254_740_991L)
                require(!result.containsKey(key)) { "笔记图片清单包含重复项" }
                result[key]=NoteAssetInventoryEntry(key,sha,size)
            }
            return result
        }
    }
}

/** No body reads on a stable path/mtime/size hit, including across process restarts. */
internal class NoteAssetHashCache(private val preferences:SharedPreferences?=null,
    private val hash:(File)->String={ file ->
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val buffer=ByteArray(64*1024)
            while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }) {
    private val memory=linkedMapOf<String,Triple<Long,Long,String>>()
    private fun trimMemory() { while(memory.size>2048)memory.remove(memory.keys.first()) }
    @Synchronized fun inventory(file:File,storageKey:String):NoteAssetInventoryEntry? {
        require(NoteAssetSyncPayload.isSafeStorageKey(storageKey))
        if(!file.isFile)return null
        val path=file.canonicalPath;val mtime=file.lastModified();val size=file.length()
        val key=MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached=memory[path] ?: preferences?.getString(key,null)?.let { raw -> runCatching {
            val row=JSONObject(raw);Triple(row.getLong("mtime"),row.getLong("size"),row.getString("sha256")) }.getOrNull() }
        if(cached!=null && cached.first==mtime && cached.second==size && cached.third.matches(Regex("[0-9a-f]{64}"))) {
            memory[path]=cached;trimMemory();return NoteAssetInventoryEntry(storageKey,cached.third,size)
        }
        val sha=hash(file)
        if(!file.isFile || file.lastModified()!=mtime || file.length()!=size)throw IOException("笔记图片在准备同步时发生变化，请重试")
        memory[path]=Triple(mtime,size,sha)
        trimMemory()
        preferences?.edit()?.apply {
            if(preferences.all.size>=2048 && !preferences.contains(key))preferences.all.keys.take(preferences.all.size-2047).forEach(::remove)
            putString(key,JSONObject().put("mtime",mtime).put("size",size).put("sha256",sha).toString())
        }?.let { check(it.commit()) { "笔记图片校验缓存保存失败" } }
        return NoteAssetInventoryEntry(storageKey,sha,size)
    }
}
