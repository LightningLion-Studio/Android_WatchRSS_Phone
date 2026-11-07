package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Private authenticated selector pages. The root lives INSIDE the existing AEAD
 * stateVersion row. An untrusted SQL index cannot hide a dirty entity or a note's
 * conflict channel. Payloads contain only entity pseudonyms and ciphertext hashes. */
internal class SyncV2SelectorTree(private val db:SupportSQLiteDatabase,private val scope:String,private val kind:String) {
    private val cache=object:LinkedHashMap<String,String>(64,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>64}
    private fun load(prefix:String,expected:String):JSONObject {
        val raw=cache[expected]?:db.query("SELECT body FROM syncv2_p5_selector_nodes WHERE scope=? AND kind=? AND prefix=?",arrayOf(scope,kind,prefix)).use{if(!it.moveToFirst())SyncV2Codec.fail("closure_incomplete");it.getString(0)}
        if(SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(raw)))!=expected)SyncV2Codec.fail("hash_mismatch")
        cache[expected]=raw
        val value=JSONObject(raw)
        if(value.getString("scope")!=scope||value.getString("kind")!=kind||value.getString("prefix")!=prefix)SyncV2Codec.fail("identity_conflict")
        return value
    }
    private fun save(prefix:String,value:JSONObject):String {
        val raw=SyncV2Codec.canonical(value);val hash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(raw)))
        db.execSQL("INSERT OR REPLACE INTO syncv2_p5_selector_nodes VALUES(?,?,?,?)",arrayOf(scope,kind,prefix,raw));cache[hash]=raw;return hash
    }
    private fun leaf(prefix:String,values:Map<String,String>)=JSONObject().put("scope",scope).put("kind",kind).put("prefix",prefix).put("form","leaf").put("values",JSONObject(values.toSortedMap()))
    fun set(root:String?,entity:String,value:String?):String? {
        check(db.inTransaction());SyncV2Codec.unhex(entity,32)
        fun build(prefix:String,values:Map<String,String>):String {
            if(values.size<=128)return save(prefix,leaf(prefix,values))
            if(prefix.length>=64)SyncV2Codec.fail("object_too_large")
            val children=JSONObject();for((digit,entries)in values.entries.groupBy{it.key[prefix.length]})children.put(digit.toString(),build(prefix+digit,entries.associate{it.key to it.value}))
            return save(prefix,JSONObject().put("scope",scope).put("kind",kind).put("prefix",prefix).put("form","node").put("children",children))
        }
        fun edit(prefix:String,hash:String?):String? {
            val n=hash?.let{load(prefix,it)}
            if(n==null||n.getString("form")=="leaf") {
                val values=n?.getJSONObject("values")?.let{v->v.keys().asSequence().associateWith{v.getString(it)}.toMutableMap()}?:mutableMapOf()
                if(value==null)values.remove(entity)else values[entity]=value
                if(values.isEmpty()){db.execSQL("DELETE FROM syncv2_p5_selector_nodes WHERE scope=? AND kind=? AND prefix=?",arrayOf(scope,kind,prefix));return null}
                val next=leaf(prefix,values);if(hash!=null&&values.size<=128&&SyncV2Codec.hash(next)==hash)return hash
                return build(prefix,values)
            }
            val children=n.getJSONObject("children");val digit=entity[prefix.length].toString();val next=edit(prefix+digit,children.optString(digit).takeIf{it.isNotEmpty()})
            if(next==null)children.remove(digit)else children.put(digit,next)
            if(children.length()==0){db.execSQL("DELETE FROM syncv2_p5_selector_nodes WHERE scope=? AND kind=? AND prefix=?",arrayOf(scope,kind,prefix));return null}
            // Private nodes need not collapse; their authenticated traversal remains bounded.
            return save(prefix,n)
        }
        return edit("",root)
    }
    fun find(root:String?,entity:String):String? {
        if(root==null)return null;SyncV2Codec.unhex(entity,32);var prefix="";var hash=root
        repeat(65){val n=load(prefix,hash!!)
            if(n.getString("form")=="leaf")return n.getJSONObject("values").optString(entity).takeIf{it.isNotEmpty()}
            if(n.getString("form")!="node"||prefix.length>=64)SyncV2Codec.fail("invalid_schema")
            val digit=entity[prefix.length].toString();val child=n.getJSONObject("children").optString(digit).takeIf{it.isNotEmpty()}?:return null;prefix+=digit;hash=child
        };SyncV2Codec.fail("invalid_schema")
    }
    fun read(root:String?,limit:Int=1000000):Map<String,String> {
        if(root==null)return emptyMap();val result=linkedMapOf<String,String>()
        fun walk(prefix:String,hash:String) {
            if(result.size>=limit)return
            val n=load(prefix,hash)
            if(n.getString("form")=="leaf") {
                val values=n.getJSONObject("values");if(values.length()>128)SyncV2Codec.fail("invalid_schema")
                for(id in values.keys().asSequence().sorted()){SyncV2Codec.unhex(id,32);if(!id.startsWith(prefix))SyncV2Codec.fail("identity_conflict");result[id]=values.getString(id);if(result.size>=limit)break}
            }else {
                if(n.getString("form")!="node"||prefix.length>=64)SyncV2Codec.fail("invalid_schema")
                val children=n.getJSONObject("children");for(digit in children.keys().asSequence().sorted()){if(!Regex("[0-9a-f]").matches(digit))SyncV2Codec.fail("invalid_schema");walk(prefix+digit,children.getString(digit));if(result.size>=limit)break}
            }
        }
        walk("",root);return result
    }
}
