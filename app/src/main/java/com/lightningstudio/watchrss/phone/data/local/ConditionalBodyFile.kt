package com.lightningstudio.watchrss.phone.data.local

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Conditional publication for originals and caches. All writers share this CAS boundary. */
internal object ConditionalBodyFile {
    private val locks=ConcurrentHashMap<String,ReentrantLock>()
    fun digest(file:File):String? {
        if(!file.exists())return null
        check(file.isFile){"body_revision_changed"}
        return file.inputStream().use{input->val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hex(hash.digest())}
    }
    private fun hex(raw:ByteArray)=raw.joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun stateDirectory(target:File):File {
        val parent=target.absoluteFile.parentFile!!
        var directory:File?=parent
        var root=parent.parentFile?:parent
        while(directory!=null) {
            if(directory.name=="assets"&&directory.parentFile?.name=="notes"){root=directory.parentFile!!.parentFile!!;break}
            if(directory.name in setOf("imported_books","imported_text","reader_assets")){root=directory.parentFile!!;break}
            directory=directory.parentFile
        }
        return File(root,".wrss-body-writes").apply{mkdirs()}
    }
    private fun id(target:File)=hex(MessageDigest.getInstance("SHA-256").digest(target.canonicalPath.toByteArray(Charsets.UTF_8)))
    private fun <T> locked(target:File,run:()->T):T {
        val key=target.canonicalPath
        return locks.getOrPut(key){ReentrantLock()}.withLock {
            val state=stateDirectory(target)
            RandomAccessFile(File(state,id(target)+".lock"),"rw").use{file->file.channel.lock().use{run()}}
        }
    }
    private fun conflict(target:File,expected:String?,actual:String?,incoming:String?):Nothing {
        val record=File(stateDirectory(target),id(target)+".conflict.json")
        val count=runCatching{JSONObject(record.readText()).getLong("attempts")}.getOrDefault(0L)
        val body=JSONObject().put("version",1).put("code","body_revision_changed").put("expectedSha256",expected?:JSONObject.NULL)
            .put("actualSha256",actual?:JSONObject.NULL).put("incomingSha256",incoming?:JSONObject.NULL).put("attempts",Math.addExact(count,1))
        val stage=File.createTempFile(".conflict-",".part",record.parentFile)
        try{FileOutputStream(stage).use{it.write(body.toString().toByteArray());it.fd.sync()};Files.move(stage.toPath(),record.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(record.parentFile!!)}finally{stage.delete()}
        throw IllegalStateException("body_revision_changed")
    }
    fun conflictRecord(target:File):File=File(stateDirectory(target),id(target)+".conflict.json")
    fun install(target:File,raw:ByteArray,expectedSha:String?=hex(MessageDigest.getInstance("SHA-256").digest(raw)),beforeCommit:()->Unit={}) {
        target.parentFile!!.mkdirs();val stage=File.createTempFile(".body-cas-",".part",target.parentFile)
        try{FileOutputStream(stage).use{it.write(raw);it.fd.sync()};install(target,stage,expectedSha,beforeCommit)}finally{stage.delete()}
    }
    /** The final comparison and publication are inside both process and OS leases.
     * link(2) also preserves a target created by an uncooperative writer after the comparison. */
    fun install(target:File,staged:File,expectedSha:String?=digest(staged),beforeCommit:()->Unit={}) {
        installStaged(target,staged,expectedSha,beforeCommit,useHardLink=true)
    }

    // Every book publisher shares the lock below. Android can deny link(2) in app data.
    fun installPrivateBook(target: File, staged: File, expectedSha: String? = digest(staged), beforeCommit: () -> Unit = {}) {
        installStaged(target, staged, expectedSha, beforeCommit, useHardLink = false)
    }

    fun installGuarded(target: File, raw: ByteArray, expectedSha: String?, beforeStagingCommit: () -> Unit,
        beforePublish: () -> Unit, privateBook: Boolean) {
        target.parentFile!!.mkdirs()
        val staged = File.createTempFile(".body-cas-", ".part", target.parentFile)
        try {
            FileOutputStream(staged).use { it.write(raw); it.fd.sync() }
            installStaged(target, staged, expectedSha, beforeStagingCommit, useHardLink = !privateBook,
                beforePublish = beforePublish)
        } finally { staged.delete() }
    }

    fun publishNewBook(target: File, staged: File, sha256: String, authorize: () -> Unit,
        onPublished: (PublishedBook) -> Unit) {
        installStaged(target, staged, sha256, {}, useHardLink = false, beforePublish = authorize,
            onPublish = { installed -> onPublished(PublishedBook(target, installed, sha256)) })
    }

    class PublishedBook internal constructor(private val target: File, private val installed: BasicFileAttributes,
        private val sha256: String) {
        fun withdraw() = locked(target) {
            val actual = try {
                Files.readAttributes(target.toPath(), BasicFileAttributes::class.java)
            } catch (_: java.nio.file.NoSuchFileException) {
                return@locked
            }
            if (installed.fileKey() != null && actual.fileKey() == installed.fileKey() &&
                actual.size() == installed.size() && actual.lastModifiedTime() == installed.lastModifiedTime() &&
                digest(target) == sha256) {
                val latest = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java)
                if (latest.fileKey() == installed.fileKey() && latest.size() == installed.size() &&
                    latest.lastModifiedTime() == installed.lastModifiedTime() && Files.deleteIfExists(target.toPath())) {
                    syncDirectory(target.parentFile!!)
                }
            }
        }
    }

    private fun installStaged(
        target: File,
        staged: File,
        expectedSha: String?,
        beforeCommit: () -> Unit,
        useHardLink: Boolean,
        beforePublish: () -> Unit = {},
        onPublish: ((BasicFileAttributes) -> Unit)? = null
    ) {
        val incoming=digest(staged)?:error("closure_incomplete")
        val expected=expectedSha
        target.parentFile!!.mkdirs();beforeCommit()
        locked(target) {
            val actual=digest(target)
            if(actual!=null&&actual!=expected)conflict(target,expected,actual,incoming)
            if(actual!=incoming) {
                // Staging may be in another directory: publish a same-filesystem durable copy.
                val local=File.createTempFile(".body-cas-",".part",target.parentFile)
                try {
                    staged.inputStream().use{input->FileOutputStream(local).use{out->input.copyTo(out);out.fd.sync()}}
                    check(digest(local)==incoming){"hash_mismatch"}
                    val installed = onPublish?.let { Files.readAttributes(local.toPath(), BasicFileAttributes::class.java) }
                    beforePublish()
                    if(actual==null && useHardLink) {
                        try{Files.createLink(target.toPath(),local.toPath())}
                        catch(_:FileAlreadyExistsException){val raced=digest(target);if(raced!=expected)conflict(target,expected,raced,incoming)}
                    } else Files.move(local.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                    if (installed != null) onPublish?.invoke(installed)
                    syncDirectory(target.parentFile!!)
                }finally{local.delete()}
            } else beforePublish()
        }
    }
    fun delete(target:File,expected:String?)=locked(target) {
        val actual=digest(target)
        if(actual!=expected)conflict(target,expected,actual,null)
        if(actual!=null){check(target.delete()){"body_revision_changed"};syncDirectory(target.parentFile!!)}
    }
}
