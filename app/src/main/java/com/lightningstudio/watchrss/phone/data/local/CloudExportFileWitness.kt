package com.lightningstudio.watchrss.phone.data.local

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

/** A durable token before the first write of each unpublished window, plus an in-memory
 * sequence for every installation. The root lock also excludes reads/receipt completion.
 * Checkpoint consumption arms the next write; a fresh process always arms its first write.
 */
internal object CloudExportFileWitness {
    data class Revision(val notes:String="0",val privateFiles:String="0")
    data class Observation(val revision:Revision,val mutations:Long)
    private data class Record(val generation:Long,val revision:Revision)
    private class State(var record:Record) {
        var notesNeedsBump=true
        var privateNeedsBump=true
        // After a restart, an existing token without a receipt is conservatively unpublished.
        var notesPending=record.revision.notes!="0"
        var privatePending=record.revision.privateFiles!="0"
        var mutations=0L
    }
    private class Root(val directory:File) {var state:State?=null}
    private val roots=java.util.concurrent.ConcurrentHashMap<String,Root>()
    internal val fileSyncs=java.util.concurrent.atomic.AtomicLong()
    internal val directorySyncs=java.util.concurrent.atomic.AtomicLong()
    internal fun resetSyncCounters(){fileSyncs.set(0);directorySyncs.set(0)}
    private const val SLOT_BYTES=128
    private fun file(root:File)=File(root,".cloud-export-files-v2")
    private fun <T> locked(root:File,block:(File,State)->T):T {
        val key=root.canonicalPath
        val handle=roots.getOrPut(key){Root(File(key))}
        return synchronized(handle) {
            val state=handle.state ?: State(load(handle.directory)).also{handle.state=it}
            block(handle.directory,state)
        }
    }
    private fun encode(record:Record):ByteArray {
        val value="W2|${record.generation}|${record.revision.notes}|${record.revision.privateFiles}"
        val crc=CRC32().apply{update(value.toByteArray())}.value.toString(16)
        val bytes="$value|$crc\n".toByteArray()
        require(bytes.size<=SLOT_BYTES)
        return bytes.copyOf(SLOT_BYTES)
    }
    private fun decode(bytes:ByteArray):Record? = runCatching {
        val text=bytes.toString(Charsets.UTF_8).substringBefore('\n')
        val fields=text.split('|');require(fields.size==5 && fields[0]=="W2")
        val value=fields.take(4).joinToString("|")
        require(CRC32().apply{update(value.toByteArray())}.value==fields[4].toLong(16))
        val generation=fields[1].toLong().also{require(it>=0)}
        fun token(value:String):String {require(value=="0" || UUID.fromString(value).toString()==value);return value}
        Record(generation,Revision(token(fields[2]),token(fields[3])))
    }.getOrNull()
    private fun load(root:File):Record {
        val current=file(root)
        if(current.exists())return RandomAccessFile(current,"r").use { input ->
            (0..1).mapNotNull { slot ->
                if(input.length()<(slot+1)*SLOT_BYTES)null else {
                    input.seek((slot*SLOT_BYTES).toLong())
                    decode(ByteArray(SLOT_BYTES).also(input::readFully))
                }
            }.maxByOrNull{it.generation} ?: throw java.io.IOException("cloud_file_witness_invalid")
        }
        // One-time durable provisioning/migration; normal windows overwrite an existing slot.
        val legacy=File(root,".cloud-export-files-v1.json")
        val initial=if(!legacy.exists())Revision() else JSONObject(legacy.readText()).let {
            require(it.getInt("version")==1);Revision(it.getString("notes"),it.getString("privateFiles"))
        }
        val record=Record(0,initial)
        check(root.isDirectory || root.mkdirs())
        val temporary=File.createTempFile(".cloud-export-", ".part",root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encode(record));output.write(ByteArray(SLOT_BYTES))
                fileSyncs.incrementAndGet();output.fd.sync()
            }
            Files.move(temporary.toPath(),current.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use {
                directorySyncs.incrementAndGet();it.force(true)
            }
        } finally {temporary.delete()}
        return record
    }
    private fun persist(root:File,record:Record) {
        // Alternating checksummed slots preserve the previous durable record on a torn write.
        RandomAccessFile(file(root),"rw").use { output ->
            output.seek((record.generation%2*SLOT_BYTES).toLong())
            output.write(encode(record))
            fileSyncs.incrementAndGet();output.fd.sync()
        }
    }
    fun revision(filesDir:File):Revision = locked(filesDir){_,state->state.record.revision}
    fun observe(filesDir:File):Observation = locked(filesDir){_,state->Observation(state.record.revision,state.mutations)}
    fun privateFilesPending(filesDir:File):Boolean = locked(filesDir){_,state->state.privatePending}
    fun complete(filesDir:File,expected:Observation,published:Revision,commit:()->Boolean):Boolean = locked(filesDir) { _,state ->
        if(Observation(state.record.revision,state.mutations)!=expected)return@locked false
        val notes=published.notes==state.record.revision.notes
        val privateFiles=published.privateFiles==state.record.revision.privateFiles
        val oldNotes=state.notesNeedsBump;val oldPrivate=state.privateNeedsBump
        // Arm before committing: even a failed/ambiguous receipt commit must not let later
        // bytes reuse a token which the receipt may have persisted. No witness fsync here.
        if(notes)state.notesNeedsBump=true
        if(privateFiles)state.privateNeedsBump=true
        if(!commit()) {
            state.notesNeedsBump=oldNotes;state.privateNeedsBump=oldPrivate
            return@locked false
        }
        if(notes)state.notesPending=false
        if(privateFiles)state.privatePending=false
        true
    }
    private fun scope(target:File):Pair<File,Boolean>? {
        var directory=target.parentFile
        while(directory!=null) {
            if(directory.name=="assets" && directory.parentFile?.name=="notes")return requireNotNull(directory.parentFile?.parentFile) to true
            if(directory.name in setOf("reader_assets","imported_books","imported_text"))return requireNotNull(directory.parentFile) to false
            directory=directory.parentFile
        }
        return null
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
        return locked(root) { directory,state ->
            if(if(notes)state.notesNeedsBump else state.privateNeedsBump) {
                val old=state.record
                val token=UUID.randomUUID().toString()
                val next=Record(Math.addExact(old.generation,1L),if(notes)old.revision.copy(notes=token) else old.revision.copy(privateFiles=token))
                persist(directory,next) // durable BEFORE the first payload mutation
                state.record=next
                if(notes)state.notesNeedsBump=false else state.privateNeedsBump=false
            }
            if(notes)state.notesPending=true else state.privatePending=true
            state.mutations=Math.addExact(state.mutations,1L)
            block()
        }
    }
    /** Drop only volatile state to reproduce process death; the durable slots are untouched. */
    internal fun forgetProcessForTest(filesDir:File){roots.remove(filesDir.canonicalPath)}
}
