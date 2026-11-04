package com.lightningstudio.watchrss.phone.data.local

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Durable change witness for bytes outside Room. No directory traversal or content hashing.
 * Persist before mutation, and hold the same lock through installation and receipt comparison.
 * A failed/interrupted write conservatively leaves a new revision, hence a publication obligation.
 */
internal object CloudExportFileWitness {
    data class Revision(val notes:String="0",val privateFiles:String="0")
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private fun lock(root:File)=locks.getOrPut(root.canonicalPath){Any()}
    private fun witness(root:File)=File(root,".cloud-export-files-v1.json")
    private fun read(root:File):Revision {
        val file=witness(root)
        if(!file.exists())return Revision()
        val json=JSONObject(file.readText())
        require(json.getInt("version")==1)
        return Revision(json.getString("notes"),json.getString("privateFiles"))
    }
    fun revision(filesDir:File):Revision = synchronized(lock(filesDir)){read(filesDir)}
    fun <T> withRevision(filesDir:File, expected:Revision, block:()->T):T? = synchronized(lock(filesDir)) {
        if(read(filesDir)!=expected)null else block()
    }
    private fun scope(target:File):Pair<File,Boolean>? {
        var directory=target.parentFile
        while(directory!=null) {
            if(directory.name=="assets" && directory.parentFile?.name=="notes")return requireNotNull(directory.parentFile?.parentFile) to true
            if(directory.name in setOf("reader_assets","imported_books","imported_text"))return requireNotNull(directory.parentFile) to false
            directory=directory.parentFile
        }
        return null // temporary/cache/export destinations are not cloud producer inputs
    }
    fun writeBytes(target:File, bytes:ByteArray) = changing(target) {
        target.parentFile?.mkdirs()
        val temporary=File.createTempFile(".install-", ".part",target.parentFile)
        try {
            FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temporary.delete()}
    }
    fun <T> changing(target:File, block:()->T):T {
        val (root,notes)=scope(target) ?: return block()
        return synchronized(lock(root)) {
            val old=read(root);val token=UUID.randomUUID().toString()
            val next=if(notes)old.copy(notes=token) else old.copy(privateFiles=token)
            root.mkdirs()
            val temp=File.createTempFile(".cloud-export-", ".part",root)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(JSONObject().put("version",1).put("notes",next.notes).put("privateFiles",next.privateFiles).toString().toByteArray())
                    out.fd.sync()
                }
                Files.move(temp.toPath(),witness(root).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
            } finally {temp.delete()}
            block()
        }
    }
}
