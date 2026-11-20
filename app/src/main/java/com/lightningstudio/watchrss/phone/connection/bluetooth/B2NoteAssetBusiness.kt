package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Note and attachment rows arrive via v1. B2 records the immutable asset reference
 * alongside APPLIED and withdraws its own publication if that transaction rolls back. */
internal class B2NoteAssetBusiness(private val store: B2Persistence, private val account: String,
    private val assets: File, private val authorize: () -> Unit) : B2BusinessPort {
    override val kinds = setOf("noteAsset")

    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String =
        applyAsset(null, descriptor, metadata, staged)

    override suspend fun applyFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject,
        staged: B2FileRef): String = applyAsset(context, descriptor, metadata, staged.file)

    private suspend fun applyAsset(context: B2ApplyContext?, descriptor: JSONObject, metadata: JSONObject,
        staged: File): String {
        context?.check?.invoke()
        authorize()
        B2Metadata.validate(descriptor)
        assets.mkdirs()
        val key = metadata.getString("storageKey")
        val target = File(assets, key)
        if (target.canonicalFile.parentFile != assets.canonicalFile) fail("BUSINESS_REJECTED")
        val expected = metadata.getString("sha256")
        var outcome = "alreadyPresent"
        var finish: ((Boolean) -> Unit)? = null
        var linked = false
        var completed = false
        try {
            if (!target.exists()) {
                val temp = File.createTempFile(".b2-", ".tmp", assets)
                finish = { committed ->
                    try {
                        if (!committed && linked) ConditionalBodyFile.unlinkPublication(target, temp)
                    } finally {
                        temp.delete()
                    }
                }
                if (context != null) {
                    context.finishAsset = finish
                    finish = null
                }
                FileOutputStream(temp).use { out ->
                    staged.inputStream().use { it.copyTo(out, 65536) }
                    out.fd.sync()
                }
                context?.check?.invoke()
                authorize()
                linked = ConditionalBodyFile.linkIfAbsent(target, temp)
                if (linked) {
                    outcome = "merged"
                    syncDirectory(assets)
                }
            }
            if (!target.isFile) fail("LOCAL_IO")
            val actual = ConditionalBodyFile.digest(target) ?: fail("LOCAL_IO")
            if (actual != expected) outcome = "retainedLocal"
            context?.check?.invoke()
            authorize()
            store.put(account, "assetReference", key,
                JSONObject().put("storageKey", key).put("sha256", actual).put("size", target.length()))
            completed = true
            return outcome
        } finally {
            finish?.invoke(completed)
        }
    }
}
