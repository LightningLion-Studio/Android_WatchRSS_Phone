package com.lightningstudio.watchrss.phone.cloud

internal fun validateCloudManifestHeadIdentity(expected: CloudSnapshotHead, actual: CloudSnapshotHead) {
    require(expected.id == actual.id && expected.sourceDeviceId == actual.sourceDeviceId && expected.deviceSequence == actual.deviceSequence &&
        expected.keyVersion == actual.keyVersion && expected.manifestSha256 == actual.manifestSha256 && expected.manifestSizeBytes == actual.manifestSizeBytes)
}

internal object CloudSnapshotReuse {
    const val PRIVATE_FILES_OBSERVATION = "privateFilesObservationV1"
    fun privateFilesProven(manifest:CloudSnapshotManifest,owner:Owner,observation:com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.PrivateObservation):Boolean =
        owner.current(manifest) && com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.PrivateObservation.decode(
            manifest.settings.optJSONObject(PRIVATE_FILES_OBSERVATION))==observation

    const val OWN_OBJECTS_VERSION = 1
    const val OWN_OBJECTS_SETTING = "phoneOwnObjectsVersion"
    private val knownObjectNames = setOf("private-library.wrss","library-sync.json","rss-state.json","notes-state.json","notes-assets.zip",CloudPrivateOverlay.NAME)

    fun legacy(manifest:CloudSnapshotManifest):Boolean = manifest.settings.opt(OWN_OBJECTS_SETTING).let { it==null || it==0 }
    fun understoodObject(d:CloudObjectDescriptor):Boolean = d.name in knownObjectNames ||
        d.name.startsWith("private/articles/") || d.name.startsWith("books/") && d.privateBook!=null ||
        d.name.startsWith("reader/") && d.privateArticle!=null

    data class Owner(val deviceId:String,val userId:String,val epoch:String,val backendScope:String,val generation:String) {
        fun settings():org.json.JSONObject = org.json.JSONObject().put("ownerUserId",userId).put("libraryEpoch",epoch)
            .put("backendScope",backendScope).put("contentGeneration",generation)
        fun owns(manifest:CloudSnapshotManifest):Boolean = manifest.sourceDeviceId==deviceId &&
            listOf(userId,epoch,backendScope,generation).all { it.isNotBlank() } && scopeCompatible(manifest,settings())
        fun current(manifest:CloudSnapshotManifest):Boolean = owns(manifest) &&
            manifest.settings.opt(OWN_OBJECTS_SETTING)==OWN_OBJECTS_VERSION
    }
    fun scopeCompatible(manifest:CloudSnapshotManifest,settings:org.json.JSONObject,allowMissing:Boolean=false):Boolean =
        listOf("backendScope","ownerUserId","libraryEpoch","contentGeneration").all {key->
            val a=manifest.settings.optString(key,"");val b=settings.optString(key,"")
            a==b || allowMissing&&(a.isEmpty()||b.isEmpty())
        }
    /** Explicit descriptors must be locally authored outputs or selected from current own heads. */
    fun preserveObjects(manifests:List<CloudSnapshotManifest>,replaced:Set<String>,explicit:List<CloudObjectDescriptor>,owner:Owner):List<CloudObjectDescriptor> {
        val own=manifests.filter(owner::current)
        own.forEach{it.requireReadable()}
        val values=(explicit+own.flatMap{it.objects}.filter{it.name !in replaced&&it.name !in explicit.map{d->d.name}}).groupBy{it.name}
        return values.map {(_,rows)->
            if(rows.map{it.toRawString()}.distinct().size>1)throw CloudFormatUpgradeRequired("unknown_object_conflicting_heads")
            rows.first()
        }
    }
    fun authenticate(codec: CloudSnapshotCodec, key: ByteArray, head: CloudSnapshotHead, bytes: ByteArray): CloudSnapshotManifest {
        if (bytes.size.toLong()!=head.manifestSizeBytes || CloudSnapshotCodec.sha256(bytes)!=head.manifestSha256) throw CloudDownloadIntegrityException()
        return codec.decryptManifest(key,head.id,bytes).also { validateCloudManifestHead(it,head) }
    }
    fun completedArticleIds(manifests: List<CloudSnapshotManifest>, owner:Owner, version: Int, fingerprint: String?): Set<String> =
        manifests.filter { canCarry(it,owner,version,fingerprint) }.flatMap { manifest ->
            val ids=manifest.settings.optJSONArray("privateBodyArticleIds") ?: return@flatMap emptyList<String>()
            (0 until ids.length()).map { ids.getString(it) }
        }.toSet()

    fun completedBookIds(manifests: List<CloudSnapshotManifest>, owner:Owner, version: Int, fingerprint: String?): Set<String> =
        manifests.filter { canCarry(it,owner,version,fingerprint) }.flatMap { manifest ->
            val ids=manifest.settings.optJSONArray("privateBodyBookIds") ?: return@flatMap emptyList<String>()
            (0 until ids.length()).map {ids.getString(it)}
        }.toSet()

    fun canCarry(manifest: CloudSnapshotManifest, owner:Owner, version: Int, fingerprint: String?): Boolean =
        owner.current(manifest) && fingerprint != null && manifest.keyVersion == version && manifest.settings.optInt("snapshotContentLayout") == 3 &&
            manifest.settings.optString("privateBodyFingerprintV2") == fingerprint &&
            manifest.objects.any { it.name == "private-library.wrss" && it.access !in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED) }

    fun requireOwnPublicationWritable(manifest:CloudSnapshotManifest) {
        // Legacy descriptors are read into the repository, never inherited by the new writer.
        if(legacy(manifest)) {manifest.requireReadable();return}
        manifest.requireWritable()
        val version=manifest.settings.opt(OWN_OBJECTS_SETTING)
        if(version!=null && version!=0 && version!=OWN_OBJECTS_VERSION)throw CloudFormatUpgradeRequired("phone_ownership_version")
        for(d in manifest.objects) {
            if(!understoodObject(d))throw CloudFormatUpgradeRequired("unknown_own_object")
            if(d.access!=CloudFormatAccess.WRITABLE)throw CloudFormatUpgradeRequired("object_read_only")
        }
    }
}

internal fun validatePrivateReaderArticle(descriptor: CloudObjectDescriptor) {
    val value = requireNotNull(descriptor.privateArticle)
    val id = value.getString("articleId"); val hash = value.getString("contentHash")
    require(id.isNotBlank() && hash.isNotBlank())
    val path = "reader/" + CloudSnapshotCodec.sha256(id.toByteArray()).take(32) + "/" + CloudSnapshotCodec.sha256(hash.toByteArray()).take(32) + ".txt"
    require(descriptor.name == path) { "私有正文路径与条目不一致" }
}
