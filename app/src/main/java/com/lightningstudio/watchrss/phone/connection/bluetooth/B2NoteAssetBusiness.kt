package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** noteAsset only: original note/asset rows still arrive through v1 notes. This port
 * atomically records the immutable asset reference alongside APPLIED in the same DB.
 * A concurrent, different local file always wins; never overwrite it. */
internal class B2NoteAssetBusiness(private val store: B2Persistence, private val account: String,
    private val assets: File, private val authorize: () -> Unit) : B2BusinessPort {
    override val kinds = setOf("noteAsset")
    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
        authorize(); B2Metadata.validate(descriptor)
        assets.mkdirs()
        val key = metadata.getString("storageKey")
        val target = File(assets, key)
        if (target.canonicalFile.parentFile != assets.canonicalFile) throw B2Exception("BUSINESS_REJECTED")
        val expected = metadata.getString("sha256")
        fun hash(file: File) = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
            B2Frames.hex(digest.digest())
        }
        var outcome = "alreadyPresent"
        if (!target.exists()) {
            val temp = File.createTempFile(".b2-", ".tmp", assets)
            try {
                FileOutputStream(temp).use { out -> staged.inputStream().use { it.copyTo(out, 65536) }; out.fd.sync() }
                authorize()
                // A link publishes without replacing a concurrent writer. The temp is on the same FS.
                try { Files.createLink(target.toPath(), temp.toPath()); outcome = "merged" }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* Reconcile below. */ }
                FileChannel.open(assets.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } finally { temp.delete() }
        }
        if (!target.isFile) throw B2Exception("LOCAL_IO")
        val actual = hash(target)
        if (actual != expected) outcome = "retainedLocal"
        authorize()
        store.put(account, "assetReference", key, JSONObject().put("storageKey", key).put("sha256", actual).put("size", target.length()))
        return outcome
    }
}
