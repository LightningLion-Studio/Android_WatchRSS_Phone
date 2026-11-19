package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Json
import org.json.JSONObject

/** Static bounds available from the watch probe. The receiver still checks live disk reservations. */
internal class B2WatchReceiveBudget(private val limits: JSONObject) {
    private var count = 0
    private var manifestBytes = 0L
    private var encodedBytes = 0L
    private var hasBody = false
    private var bookBytes = 0L

    fun add(descriptor: JSONObject) {
        val size = descriptor.getLong("size")
        val descriptorBytes = B2Json.utf8(descriptor.toString()).size
        count++
        manifestBytes += descriptorBytes + 1L
        val kind = descriptor.getString("kind")
        if (kind in com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire.bookKinds) bookBytes += size
        else encodedBytes += size
        hasBody = hasBody || descriptor.getString("kind") == B2_ARTICLE_BODY
        // The watch reserves four encoded copies and one import workspace per body job.
        // Manifest and receipts are checked separately by the planner's journal budget.
        val importBytes = if (hasBody) 64L * 1024 * 1024 else 0L
        val stagingBytes = encodedBytes * 4 + importBytes + bookBytes
        if (size > limits.getLong("maxObjectBytes") ||
            B2Json.utf8(descriptor.getString("metadataUtf8")).size > limits.getInt("maxMetadataBytes") ||
            count > limits.getInt("maxPlanObjects") || manifestBytes > limits.getLong("maxManifestBytes") ||
            encodedBytes > 64L * 1024 * 1024 || stagingBytes > limits.getLong("maxStagingBytes")) {
            throw B2Exception("LIMIT_EXCEEDED")
        }
    }
}
