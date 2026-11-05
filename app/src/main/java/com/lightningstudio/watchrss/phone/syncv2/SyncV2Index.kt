package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Wire Merkle tree, distinct from P1's local candidate summaries. */
internal object SyncV2Index {
    data class Tree(val root: String, val objects: Map<String, ByteArray>)
    fun row(r: JSONObject) {
        SyncV2Codec.exact(r, setOf("entityKey", "metaOid", "stateOid", "addedAtMillis", "rowSequence", "deleted", "bodyRevision", "promiseId", "recipeOid", "opaqueContentId", "contentBytes", "requiredFeatures"))
        SyncV2Codec.unhex(SyncV2Wire.string(r, "entityKey"), 32)
        SyncV2Wire.oid(SyncV2Wire.string(r, "metaOid"), "meta"); SyncV2Wire.oid(SyncV2Wire.string(r, "stateOid"), "state")
        SyncV2Wire.uint(r, "addedAtMillis")
        if (SyncV2Codec.d64(SyncV2Wire.string(r, "rowSequence")).signum() == 0 || r.get("deleted") !is Boolean) SyncV2Codec.fail("invalid_schema")
        for (key in listOf("bodyRevision", "promiseId")) if (!r.isNull(key)) SyncV2Codec.unhex(SyncV2Wire.string(r, key), 16)
        if (!r.isNull("recipeOid")) SyncV2Wire.oid(SyncV2Wire.string(r, "recipeOid"), "recipe")
        if (!r.isNull("opaqueContentId")) SyncV2Codec.unhex(SyncV2Wire.string(r, "opaqueContentId"), 32)
        if (!r.isNull("contentBytes")) SyncV2Wire.uint(r, "contentBytes")
        if (r.isNull("opaqueContentId") != r.isNull("contentBytes")) SyncV2Codec.fail("invalid_schema")
        if (r.isNull("bodyRevision")) {
            if (!r.isNull("promiseId") || !r.isNull("recipeOid") || !r.isNull("opaqueContentId")) SyncV2Codec.fail("invalid_schema")
        } else if (r.isNull("recipeOid") == r.isNull("promiseId")) SyncV2Codec.fail("invalid_schema")
        SyncV2Wire.features(r.getJSONArray("requiredFeatures"))
    }
    fun build(scope: JSONObject, rows: List<JSONObject>): Tree {
        SyncV2Codec.scope(scope)
        if (rows.size > 1000000) SyncV2Codec.fail("object_too_large")
        rows.forEach(::row)
        val sorted = rows.sortedBy { it.getString("entityKey") }
        if (sorted.map { it.getString("entityKey") }.distinct().size != rows.size) SyncV2Codec.fail("invalid_schema")
        val objects = linkedMapOf<String, ByteArray>()
        fun node(prefix: String, values: List<JSONObject>): String {
            val leaf = JSONObject().put("version", 2).put("scope", scope).put("form", "leaf").put("prefix", prefix).put("rows", JSONArray(values))
            val leafBytes = SyncV2Codec.utf8(SyncV2Codec.canonical(leaf))
            val value = if (values.size <= 256 && leafBytes.size <= 262144) leaf else {
                if (prefix.length >= 64) SyncV2Codec.fail("object_too_large")
                val children = JSONArray()
                for ((digit, group) in values.groupBy { it.getString("entityKey")[prefix.length] }.toSortedMap()) {
                    children.put(JSONObject().put("digit", digit.toString()).put("oid", node(prefix + digit, group)).put("count", group.size))
                }
                JSONObject().put("version", 2).put("scope", scope).put("form", "node").put("prefix", prefix).put("children", children)
            }
            val bytes = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
            val oid = "tree:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}"; objects[oid] = bytes; return oid
        }
        return Tree(node("", sorted), objects)
    }
    /** Rebuild verifies canonical split/count/prefix rules as well as hashes. */
    fun read(scope: JSONObject, root: String, get: (String)->ByteArray): List<JSONObject> {
        val actual = linkedMapOf<String, ByteArray>(); val rows = mutableListOf<JSONObject>()
        fun walk(oid: String, prefix: String) {
            SyncV2Wire.oid(oid, "tree")
            if (prefix.length > 64 || actual.size > 1000000 || actual.containsKey(oid)) SyncV2Codec.fail("invalid_schema")
            val bytes = get(oid); val n = SyncV2Wire.parse(bytes, 262144, true)
            if ("tree:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}" != oid) SyncV2Codec.fail("hash_mismatch")
            actual[oid] = bytes
            if (n.get("version") != 2 || n.get("prefix") != prefix || SyncV2Codec.scope(n.getJSONObject("scope")) != SyncV2Codec.scope(scope)) SyncV2Codec.fail("invalid_schema")
            when (n.get("form")) {
                "leaf" -> {
                    SyncV2Codec.exact(n, setOf("version", "scope", "form", "prefix", "rows"))
                    val a = n.getJSONArray("rows")
                    if (a.length() > 256) SyncV2Codec.fail("invalid_schema")
                    for (i in 0 until a.length()) { val r = a.getJSONObject(i); row(r); if (!r.getString("entityKey").startsWith(prefix)) SyncV2Codec.fail("invalid_schema"); rows.add(r) }
                }
                "node" -> {
                    SyncV2Codec.exact(n, setOf("version", "scope", "form", "prefix", "children"))
                    val children = n.getJSONArray("children"); var previous = ""
                    if (prefix.length >= 64 || children.length() !in 1..16) SyncV2Codec.fail("invalid_schema")
                    for (i in 0 until children.length()) {
                        val c = children.getJSONObject(i); SyncV2Codec.exact(c, setOf("digit", "oid", "count"))
                        val digit = SyncV2Wire.string(c, "digit")
                        if (!Regex("[0-9a-f]").matches(digit) || digit <= previous) SyncV2Codec.fail("invalid_schema")
                        previous = digit; val start = rows.size
                        walk(SyncV2Wire.string(c, "oid"), prefix + digit)
                        if (SyncV2Wire.uint(c, "count", 1000000) != (rows.size - start).toLong()) SyncV2Codec.fail("invalid_schema")
                    }
                }
                else -> SyncV2Codec.fail("invalid_schema")
            }
            if (rows.size > 1000000) SyncV2Codec.fail("object_too_large")
        }
        walk(root, "")
        val rebuilt = build(scope, rows)
        if (rebuilt.root != root || actual.keys != rebuilt.objects.keys) SyncV2Codec.fail("invalid_schema")
        return rows
    }
    fun refs(identity: SyncV2Identity, signer: SyncV2Signing, stateOid: String, segments: JSONArray = JSONArray()): JSONObject {
        SyncV2Wire.oid(stateOid, "state")
        SyncV2Wire.strings(segments, 2048, false).also { if (it.distinct().size != it.size) SyncV2Codec.fail("invalid_schema"); it.forEach { oid -> SyncV2Wire.oid(oid, "state") } }
        return signer.signed("wrss.sync.v2/state-refs-sign", JSONObject().put("scope", identity.scope).put("stateOid", stateOid).put("segmentOids", segments))
    }
}
