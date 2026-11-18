package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Json
import org.json.JSONObject

/** Static bounds available from the watch probe. The receiver still checks live disk reservations. */
internal class B2WatchReceiveBudget(private val limits: JSONObject) {
    private var count = 0
    private var manifestBytes = 0L
    private var payloadBytes = 0L
    private var objectPeakBytes = 0L
    private var hasBody = false

    fun add(descriptor: JSONObject) {
        val size = descriptor.getLong("size")
        val descriptorBytes = B2Json.utf8(descriptor.toString()).size
        count++
        manifestBytes += descriptorBytes + 1L
        payloadBytes += size
        // Match the watch's complete-manifest reservation, including both decoded file copies.
        objectPeakBytes += size * 4 + descriptorBytes + 1024
        hasBody = hasBody || descriptor.getString("kind") == B2_ARTICLE_BODY
        val overhead = if (hasBody) 64L * 1024 * 1024 else limits.getLong("maxJournalBytes")
        if (size > limits.getLong("maxObjectBytes") ||
            B2Json.utf8(descriptor.getString("metadataUtf8")).size > limits.getInt("maxMetadataBytes") ||
            count > limits.getInt("maxPlanObjects") || manifestBytes > limits.getLong("maxManifestBytes") ||
            payloadBytes > 64L * 1024 * 1024 || objectPeakBytes + overhead > limits.getLong("maxStagingBytes")) {
            throw B2Exception("LIMIT_EXCEEDED")
        }
    }
}
