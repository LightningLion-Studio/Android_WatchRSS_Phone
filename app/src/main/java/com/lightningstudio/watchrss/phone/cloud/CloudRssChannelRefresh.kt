package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import kotlinx.coroutines.CancellationException

/** A failing feed cannot gate the personal cloud; account/epoch cancellation still fences the batch. */
internal suspend fun refreshCloudRssChannels(
    sources: List<PhoneRssSourceEntity>,
    stopped: () -> Boolean = { false },
    checkOwner: () -> Unit = {},
    refresh: suspend (PhoneRssSourceEntity) -> Unit
): List<String> {
    val failed=mutableListOf<String>()
    for (source in sources.filterNot { it.deleted || ImportedContentIds.isImportedContentUrl(it.url) || CloudRssSourceIdentity.isLocalOnly(it.url) }) {
        if (stopped()) break
        checkOwner()
        try { refresh(source);checkOwner() }
        catch (error: Exception) {
            checkOwner()
            if (error is CancellationException || error is CloudTrustException ||
                (error is CloudHttpException && error.detail.contains("library_epoch_mismatch"))) throw error
            failed += source.url
        }
    }
    return failed
}
