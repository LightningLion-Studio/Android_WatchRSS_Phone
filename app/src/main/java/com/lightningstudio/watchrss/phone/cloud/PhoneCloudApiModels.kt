package com.lightningstudio.watchrss.phone.cloud

data class CloudMemberState(
    val plan: String,
    val active: Boolean,
    val writable: Boolean,
    val readable: Boolean,
    val quotaBytes: Long,
    val usedBytes: Long,
    val reservedBytes: Long,
    val retentionDays: Int?,
    val readOnlyAt: String?,
    val deleteAfter: String?
) {
    val availableBytes: Long
        get() = (quotaBytes - usedBytes - reservedBytes).coerceAtLeast(0)
}

data class RegisteredCloudDevice(
    val deviceId: String,
    val platform: String,
    val displayName: String,
    val publicKeySpki: String,
    val keyVersion: Int,
    val lastSequence: Long,
    val revokedAt: String?
)

data class StoredCloudKeyEnvelope(
    val id: String,
    val recipientType: String,
    val recipientDeviceId: String?,
    val envelope: CloudKeyEnvelope
)

data class CloudBootstrap(
    val member: CloudMemberState,
    val devices: List<RegisteredCloudDevice>,
    val keyEnvelopes: List<StoredCloudKeyEnvelope>
)

data class CloudSnapshotHead(
    val id: String,
    val sourceDeviceId: String,
    val deviceSequence: Long,
    val keyVersion: Int,
    val manifestSha256: String,
    val manifestSizeBytes: Long,
    val parentHeads: Map<String, String>,
    val observedHeads: Map<String, Long>
)

data class CloudUploadObject(
    val kind: String,
    val sha256: String,
    val sizeBytes: Long,
    val objectPath: String,
    val signedUrl: String,
    val token: String,
    val tusEndpoint: String,
    val bucketName: String
)

data class CloudSnapshotReservation(
    val snapshotId: String,
    val reservedBytes: Long,
    val missingObjects: List<CloudUploadObject>
)

data class CloudDownloadObject(
    val sha256: String,
    val sizeBytes: Long,
    val signedUrl: String
)

data class CloudSnapshotDownload(
    val head: CloudSnapshotHead,
    val manifestSignedUrl: String,
    val chunks: List<CloudDownloadObject>
)

data class CloudSnapshotRestorePreview(
    val snapshotId: String,
    val sourceDeviceId: String,
    val deviceSequence: Long,
    val sourceCount: Int,
    val privateArticleCount: Int,
    val relayArticleCount: Int,
    val rssStateCount: Int,
    val encryptedDownloadBytes: Long,
    val hasPrivateArchive: Boolean
)

data class CloudSnapshotRestoreResult(
    val privateArticlesChanged: Int,
    val sourcesChanged: Int,
    val relayItemsChanged: Int,
    val rssStatesApplied: Int,
    val rssStatesPending: Int
)
