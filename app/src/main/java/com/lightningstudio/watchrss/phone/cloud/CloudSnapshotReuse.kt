package com.lightningstudio.watchrss.phone.cloud

internal fun validateCloudManifestHeadIdentity(expected: CloudSnapshotHead, actual: CloudSnapshotHead) {
    require(expected.id == actual.id && expected.sourceDeviceId == actual.sourceDeviceId && expected.deviceSequence == actual.deviceSequence &&
        expected.keyVersion == actual.keyVersion && expected.manifestSha256 == actual.manifestSha256 && expected.manifestSizeBytes == actual.manifestSizeBytes)
}

internal object CloudSnapshotReuse {
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
            manifest.objects.any { it.name == "private-library.wrss" && it.readerKeyEnvelope == null }
}

internal fun validatePrivateReaderArticle(descriptor: CloudObjectDescriptor) {
    val value = requireNotNull(descriptor.privateArticle)
    val id = value.getString("articleId"); val hash = value.getString("contentHash")
    require(id.isNotBlank() && hash.isNotBlank())
    val path = "reader/" + CloudSnapshotCodec.sha256(id.toByteArray()).take(32) + "/" + CloudSnapshotCodec.sha256(hash.toByteArray()).take(32) + ".txt"
    require(descriptor.name == path) { "私有正文路径与条目不一致" }
}
