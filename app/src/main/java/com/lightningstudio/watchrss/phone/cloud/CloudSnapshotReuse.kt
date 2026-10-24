package com.lightningstudio.watchrss.phone.cloud

internal fun validateCloudManifestHeadIdentity(expected: CloudSnapshotHead, actual: CloudSnapshotHead) {
    require(expected.id == actual.id && expected.sourceDeviceId == actual.sourceDeviceId && expected.deviceSequence == actual.deviceSequence &&
        expected.keyVersion == actual.keyVersion && expected.manifestSha256 == actual.manifestSha256 && expected.manifestSizeBytes == actual.manifestSizeBytes)
}

internal object CloudSnapshotReuse {
    fun scopeCompatible(manifest:CloudSnapshotManifest,settings:org.json.JSONObject,allowMissing:Boolean=false):Boolean =
        listOf("backendScope","ownerUserId","libraryEpoch","contentGeneration").all {key->
            val a=manifest.settings.optString(key,"");val b=settings.optString(key,"")
            a==b || allowMissing&&(a.isEmpty()||b.isEmpty())
        }
    fun preserveObjects(manifests:List<CloudSnapshotManifest>,replaced:Set<String>,explicit:List<CloudObjectDescriptor>):List<CloudObjectDescriptor> {
        manifests.forEach{it.requireReadable()}
        val values=(explicit+manifests.flatMap{it.objects}.filter{it.name !in replaced&&it.name !in explicit.map{d->d.name}}).groupBy{it.name}
        return values.map {(_,rows)->
            if(rows.map{it.toRawString()}.distinct().size>1)throw CloudFormatUpgradeRequired("unknown_object_conflicting_heads")
            rows.first()
        }
    }
    fun authenticate(codec: CloudSnapshotCodec, key: ByteArray, head: CloudSnapshotHead, bytes: ByteArray): CloudSnapshotManifest {
        if (bytes.size.toLong()!=head.manifestSizeBytes || CloudSnapshotCodec.sha256(bytes)!=head.manifestSha256) throw CloudDownloadIntegrityException()
        return codec.decryptManifest(key,head.id,bytes).also { validateCloudManifestHead(it,head) }
    }
    fun completedArticleIds(manifests: List<CloudSnapshotManifest>, user: String, epoch: String, version: Int, fingerprint: String?): Set<String> =
        manifests.filter { canCarry(it,user,epoch,version,fingerprint) }.flatMap { manifest ->
            val ids=manifest.settings.optJSONArray("privateBodyArticleIds") ?: return@flatMap emptyList<String>()
            (0 until ids.length()).map { ids.getString(it) }
        }.toSet()

    fun completedBookIds(manifests: List<CloudSnapshotManifest>, user: String, epoch: String, version: Int, fingerprint: String?): Set<String> =
        manifests.filter { canCarry(it,user,epoch,version,fingerprint) }.flatMap { manifest ->
            val ids=manifest.settings.optJSONArray("privateBodyBookIds") ?: return@flatMap emptyList<String>()
            (0 until ids.length()).map {ids.getString(it)}
        }.toSet()

    fun canCarry(manifest: CloudSnapshotManifest, user: String, epoch: String, version: Int, fingerprint: String?): Boolean =
        fingerprint != null && manifest.keyVersion == version && manifest.settings.optInt("snapshotContentLayout") == 3 &&
            manifest.settings.optString("ownerUserId") == user && manifest.settings.optString("libraryEpoch") == epoch &&
            manifest.settings.optString("privateBodyFingerprintV2") == fingerprint &&
            manifest.objects.any { it.name == "private-library.wrss" && it.access !in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED) }
}

internal fun validatePrivateReaderArticle(descriptor: CloudObjectDescriptor) {
    val value = requireNotNull(descriptor.privateArticle)
    val id = value.getString("articleId"); val hash = value.getString("contentHash")
    require(id.isNotBlank() && hash.isNotBlank())
    val path = "reader/" + CloudSnapshotCodec.sha256(id.toByteArray()).take(32) + "/" + CloudSnapshotCodec.sha256(hash.toByteArray()).take(32) + ".txt"
    require(descriptor.name == path) { "私有正文路径与条目不一致" }
}
