package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Disk staging for a control round. Only keys and lengths survive across entities.
 * Values are sealed wire objects or locally encrypted rows. No cleartext enters the spool.
 * The owner closes the spool on success and failure, including before exposing an ACK. */
internal class SyncV2Spool(db: SupportSQLiteDatabase) : AbstractMutableMap<String, ByteArray>(), AutoCloseable {
    private val directory = try{java.nio.file.Files.createTempDirectory(
        syncV2SpoolDirectory(db).toPath(), "syncv2-spool-").toFile()}catch(e:java.io.IOException){SyncV2Codec.fail("storage_pressure")}.also {
        it.setReadable(false,false);it.setWritable(false,false);it.setExecutable(false,false)
        it.setReadable(true,true);it.setWritable(true,true);it.setExecutable(true,true)
    }
    private val paths = linkedMapOf<String,java.io.File>()
    private val lengths = linkedMapOf<String, Int>()
    private var nextFile = 0L

    override val size get() = lengths.size
    override fun containsKey(key: String) = lengths.containsKey(key)
    override fun get(key: String): ByteArray? = if (!containsKey(key)) null else
        paths.getValue(key).readBytes()
    override fun put(key: String, value: ByteArray): ByteArray? {
        // Map callers never use the previous bytes; do not read a second object into memory.
        val file=paths.getOrPut(key){java.io.File(directory,(nextFile++).toString())}
        try {file.writeBytes(value)}catch(e:java.io.IOException){SyncV2Codec.fail("storage_pressure")}
        lengths[key] = value.size
        return null
    }
    override fun remove(key: String): ByteArray? {
        val old = get(key);paths.remove(key)?.delete(); lengths.remove(key); return old
    }
    override fun clear() { paths.values.forEach{it.delete()};paths.clear();lengths.clear() }
    override val keys: MutableSet<String> get() = lengths.keys.toMutableSet()
    override val entries: MutableSet<MutableMap.MutableEntry<String, ByteArray>> get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, ByteArray>>() {
        override val size get() = lengths.size
        override fun add(element: MutableMap.MutableEntry<String, ByteArray>): Boolean = throw UnsupportedOperationException()
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, ByteArray>> {
            val ids = lengths.keys.toList().iterator()
            return object : MutableIterator<MutableMap.MutableEntry<String, ByteArray>> {
                private var current: String? = null
                override fun hasNext() = ids.hasNext()
                override fun next(): MutableMap.MutableEntry<String, ByteArray> {
                    val id = ids.next(); current = id
                    return object : MutableMap.MutableEntry<String, ByteArray> {
                        override val key = id
                        override val value get() = this@SyncV2Spool.getValue(id)
                        override fun setValue(newValue: ByteArray): ByteArray { val old = value; put(id, newValue); return old }
                    }
                }
                override fun remove() { this@SyncV2Spool.remove(current ?: throw IllegalStateException()) }
            }
        }
    }
    fun retain(ids: Set<String>) { for (id in lengths.keys.toList()) if (id !in ids) remove(id) }
    override fun close() { clear();directory.delete() }
}

/** Lazy transformations are deliberate: map/associateBy would retain every note body. */
internal fun <A, B> List<A>.mapRows(transform: (A) -> B): List<B> = object : AbstractList<B>() {
    override val size get() = this@mapRows.size
    override fun get(index: Int) = transform(this@mapRows[index])
}
internal fun List<JSONObject>.indexRows(): Map<String, JSONObject> {
    val positions = indices.associateBy { get(it).getJSONObject("meta").getString("entityKey") }
    return object : AbstractMap<String, JSONObject>() {
        override val keys get() = positions.keys
        override fun get(key: String) = positions[key]?.let { this@indexRows[it] }
        override val entries get() = positions.keys.map { key -> object : Map.Entry<String, JSONObject> {
            override val key = key
            override val value get() = this@indexRows[positions.getValue(key)]
        } }.toSet()
    }
}

internal fun Map<String,ByteArray>.selectObjects(predicate:(String)->Boolean):Map<String,ByteArray> {
    val selected=keys.filter(predicate).toSet()
    return object:AbstractMap<String,ByteArray>() {
        override val keys get()=selected
        override fun get(key:String)=if(key in selected)this@selectObjects[key]else null
        override val entries get()=selected.map{key->object:Map.Entry<String,ByteArray>{
            override val key=key
            override val value get()=this@selectObjects.getValue(key)
        }}.toSet()
    }
}
internal fun objectOverlay(first:Map<String,ByteArray>,second:Map<String,ByteArray>):Map<String,ByteArray> {
    val ids=first.keys+second.keys
    return object:AbstractMap<String,ByteArray>() {
        override val keys get()=ids
        override fun get(key:String)=second[key]?:first[key]
        override val entries get()=ids.map{key->object:Map.Entry<String,ByteArray>{
            override val key=key
            override val value get()=second[key]?:first.getValue(key)
        }}.toSet()
    }
}

/** A frozen, locally encrypted snapshot. Reading a row never caches its plaintext. */
internal class SyncV2RowSpool(db:SupportSQLiteDatabase,private val seal:(JSONObject)->ByteArray,
    private val open:(ByteArray)->JSONObject):AbstractList<JSONObject>(),AutoCloseable {
    private val bytes=SyncV2Spool(db)
    private val ids=mutableListOf<String>()
    fun append(row:JSONObject) {val id=ids.size.toString();bytes[id]=seal(row);ids.add(id)}
    override val size get()=ids.size
    override fun get(index:Int)=open(bytes.getValue(ids[index]))
    override fun close() {bytes.close();ids.clear()}
}
internal fun List<JSONObject>.filterRows(predicate:(JSONObject)->Boolean):List<JSONObject> =
    indices.filter{predicate(get(it))}.mapRows{get(it)}
internal fun List<JSONObject>.plusRows(other:List<JSONObject>):List<JSONObject> = object:AbstractList<JSONObject>() {
    override val size get()=this@plusRows.size+other.size
    override fun get(index:Int)=if(index<this@plusRows.size)this@plusRows[index]else other[index-this@plusRows.size]
}
internal class SyncV2RowMap(db:SupportSQLiteDatabase,private val seal:(JSONObject)->ByteArray,
    private val open:(ByteArray)->JSONObject):AbstractMutableMap<String,JSONObject>(),AutoCloseable {
    private val bytes=SyncV2Spool(db)
    override val keys get()=bytes.keys
    override fun get(key:String)=bytes[key]?.let(open)
    override fun put(key:String,value:JSONObject):JSONObject? {bytes[key]=seal(value);return null}
    override fun remove(key:String)=bytes.remove(key)?.let(open)
    override val entries:MutableSet<MutableMap.MutableEntry<String,JSONObject>> get()=object:AbstractMutableSet<MutableMap.MutableEntry<String,JSONObject>>() {
        override val size get()=bytes.size
        override fun add(element:MutableMap.MutableEntry<String,JSONObject>):Boolean=throw UnsupportedOperationException()
        override fun iterator():MutableIterator<MutableMap.MutableEntry<String,JSONObject>> {
            val ids=keys.toList().iterator()
            return object:MutableIterator<MutableMap.MutableEntry<String,JSONObject>> {
                override fun hasNext()=ids.hasNext()
                override fun next():MutableMap.MutableEntry<String,JSONObject> {val id=ids.next();return object:MutableMap.MutableEntry<String,JSONObject>{
                    override val key=id
                    override val value get()=this@SyncV2RowMap.getValue(id)
                    override fun setValue(newValue:JSONObject):JSONObject {val old=value;put(id,newValue);return old}
                }}
                override fun remove():Unit=throw UnsupportedOperationException()
            }
        }
    }
    override fun close()=bytes.close()
}
