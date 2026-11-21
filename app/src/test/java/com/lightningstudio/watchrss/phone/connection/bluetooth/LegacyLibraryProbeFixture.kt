package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

/** Matches the capabilities advertised by the frozen PodJS v2.0.1-2 probe. */
internal fun JSONObject.asLegacyPodJsProbe(readerResources: Boolean = true): JSONObject = apply {
    put("phase", "probe")
    put("supportsManifestBatches", true)
    put("supportsReaderPresets", readerResources)
    put("supportsReaderAssets", readerResources)
    put("supportsResourceChunkAck", readerResources)
    put("supportsReaderPresetPreview", true)
    put("supportsPersistentSession", true)
    put("supportsTransferByteProgress", true)
}
