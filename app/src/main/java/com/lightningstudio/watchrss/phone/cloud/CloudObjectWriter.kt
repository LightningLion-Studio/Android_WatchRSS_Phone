package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Only enabled after all readers, shared vectors and server gate permit writes. */
class CloudFormatWritePolicy(val localWrite: Boolean = CloudFormatR48.writeV1, val localMigration: Boolean = CloudFormatR48.migrationV1) {
    fun canWrite(capabilities: JSONObject?): Boolean = localWrite && capabilities?.optJSONObject("phaseA")?.optBoolean("cloudFormatR48WriteV1") == true
    fun canMigrate(capabilities: JSONObject?): Boolean = localMigration && canWrite(capabilities) && capabilities?.optJSONObject("phaseB")?.optBoolean("cloudFormatMigrationR48V1") == true
}

/** Creates only authenticated byte references; business projections belong to each registered object reader. */
object CloudObjectWriter {
    fun entryReference(name: String, archive: CloudObjectDescriptor, entryPath: String, bytes: ByteArray, template: CloudObjectDescriptor? = null): CloudObjectDescriptor {
        require(archive.entryRef == null && archive.chunks.isNotEmpty() && archive.name != name)
        require(archive.access == CloudFormatAccess.WRITABLE)
        require(entryPath.isNotEmpty() && !entryPath.startsWith('/') && !entryPath.contains('\\') && !entryPath.contains('\u0000') && entryPath.split('/').none { it in setOf("", ".", "..") })
        require(template == null || template.access == CloudFormatAccess.WRITABLE)
        var document = template?.rawDocument ?: CloudRawDocument.parse("{}")
        for (field in listOf("chunks", "readerKeyEnvelope", "objectKeyEnvelope")) document = document.remove(listOf(field))
        document = features(document, "r48.entry-ref.v1")
        return CloudObjectDescriptor(name,"identity",bytes.size.toLong(),bytes.size.toLong(),emptyList(),
            privateArticle=template?.privateArticle,privateBook=template?.privateBook,
            entryRef=JSONObject().put("object",archive.name).put("entryPath",entryPath).put("encoding","identity"),rawDocument=document)
    }

    fun migrationOutput(output: CloudObjectDescriptor, accountKey: ByteArray, root: ByteArray, planId: String, crypto: CloudMigrationCrypto = CloudMigrationCrypto()): CloudObjectDescriptor {
        require(output.accountRoot && output.access == CloudFormatAccess.WRITABLE)
        return output.copy(objectKeyEnvelope=crypto.objectEnvelope(accountKey,root,planId),rawDocument=features(output.rawDocument ?: CloudRawDocument.parse("{}"),"r48.object-key.v1"))
    }

    private fun features(document: CloudRawDocument, feature: String): CloudRawDocument {
        val value = document.json().optJSONObject("features") ?: JSONObject()
        val incompat=value.optJSONArray("incompat") ?: JSONArray()
        val names=(0 until incompat.length()).map { incompat.getString(it) }.filter { it !in setOf("r48.entry-ref.v1", "r48.object-key.v1") } + feature
        value.put("incompat",JSONArray(names)).put("roCompat",value.optJSONArray("roCompat") ?: JSONArray())
        return document.patch(listOf("features"),value.toString())
    }
}
