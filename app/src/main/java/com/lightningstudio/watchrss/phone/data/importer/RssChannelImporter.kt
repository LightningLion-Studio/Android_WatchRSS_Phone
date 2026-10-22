package com.lightningstudio.watchrss.phone.data.importer

import com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity

/** R36 is an explicit local-only route, independent of the public pool and account session. */
internal class RssChannelImporter(
    private val publicImport: suspend (String) -> ImportedRssSource,
    private val localImport: (String) -> ImportedRssSource = RssSourceImporter()::importUrl
) {
    suspend fun importUrl(url: String): ImportedRssSource =
        if (CloudRssSourceIdentity.isLocalOnly(url)) localImport(url).copy(url=url,sourceId=null)
        else publicImport(url)
}
