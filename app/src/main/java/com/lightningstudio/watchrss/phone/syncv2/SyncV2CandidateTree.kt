package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Incremental LOCAL candidate tree; these hashes are never wire OIDs or authenticated cloud rows.
 * Final WRS2 sealing and signed wire trees belong to P2. A leaf holds <=256 summaries.
 */
internal class SyncV2CandidateTree(private val db:SupportSQLiteDatabase,private val scope:String) {
    var rewritten=0;private set
    private fun load(prefix:String):JSONObject?=db.query("SELECT body FROM syncv2_tree_nodes WHERE scope=? AND prefix=?",arrayOf(scope,prefix)).use {
        if(it.moveToFirst())JSONObject(it.getString(0)) else null
    }
    private fun save(prefix:String,value:JSONObject):String {
        val hash=SyncV2Codec.hash(value)
        db.execSQL("INSERT OR REPLACE INTO syncv2_tree_nodes VALUES(?,?,?,?)",arrayOf(scope,prefix,SyncV2Codec.canonical(value),hash))
        rewritten++;return hash
    }
    private fun leaf(prefix:String,rows:List<JSONObject>)=JSONObject().put("domain","wrss.sync.v2/local-candidate-tree").put("scope",JSONObject(scope)).put("prefix",prefix).put("form","leaf").put("rows",JSONArray(rows.sortedBy{it.getString("entityKey")}))
    fun update(summary:JSONObject):String {rewritten=0;return updateAt("",summary)}
    private fun updateAt(prefix:String,row:JSONObject):String {
        val old=load(prefix)
        if(old==null || old.getString("form")=="leaf") {
            val rows=old?.getJSONArray("rows")?:JSONArray();val id=row.getString("entityKey")
            val next=(0 until rows.length()).map{rows.getJSONObject(it)}.filterNot{it.getString("entityKey")==id}+row
            return build(prefix,next)
        }
        val digit=row.getString("entityKey")[prefix.length].toString()
        val hash=updateAt(prefix+digit,row)
        val children=old.getJSONObject("children");children.put(digit,hash)
        return save(prefix,old)
    }
    private fun build(prefix:String,rows:List<JSONObject>):String {
        val value=leaf(prefix,rows)
        if(rows.size<=256 && SyncV2Codec.utf8(SyncV2Codec.canonical(value)).size<=262144)return save(prefix,value)
        if(prefix.length>=64)SyncV2Codec.fail("object_too_large")
        val children=JSONObject()
        for((digit,group)in rows.groupBy{it.getString("entityKey")[prefix.length]}.toSortedMap())children.put(digit.toString(),build(prefix+digit,group))
        return save(prefix,JSONObject().put("domain","wrss.sync.v2/local-candidate-tree").put("scope",JSONObject(scope)).put("prefix",prefix).put("form","node").put("children",children))
    }
}
