package com.lightningstudio.watchrss.phone.cloud

internal object CloudSnapshotContent {
    fun hash(
        objects: List<CloudLogicalObject>,
        carried: List<CloudObjectDescriptor>,
        keyVersion: Int,
        privateFingerprint: String? = null
    ): String {
        fun body(name:String)=name in setOf("private-library.wrss","library-sync.json",CloudPrivateOverlay.NAME) || name.startsWith("private/articles/") || name.startsWith("books/")
        val description = buildString {
            append("keyVersion:").append(keyVersion).append(';')
            if (privateFingerprint != null) append("private:").append(privateFingerprint).append(';')
            objects.filter { it.name != "rss-state.json" && it.name != "library-sync.json" && (privateFingerprint == null || !body(it.name)) }.sortedBy(CloudLogicalObject::name).forEach {
                append(it.name).append(':').append(it.sha256()).append(';')
            }
            carried.filter { it.name != "rss-state.json" && it.name != "library-sync.json" && (privateFingerprint == null || !body(it.name)) }.sortedBy(CloudObjectDescriptor::name).forEach { descriptor ->
                append(descriptor.name).append(':')
                descriptor.chunks.forEach { append(it.ciphertextSha256) }
                append(';')
            }
        }
        return CloudSnapshotCodec.sha256(description.toByteArray())
    }

}
