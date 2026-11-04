package com.lightningstudio.watchrss.phone.cloud

/** Per-service diagnostics for producer work. No payload or account identity. */
internal class CloudSyncWorkCounters {
    var exports = 0L
    var fingerprints = 0L
    var notesZips = 0L
    var recordHashes = 0L
    var manifestDecrypts = 0L
    fun reset() { exports=0; fingerprints=0; notesZips=0; recordHashes=0; manifestDecrypts=0 }
    fun json() = org.json.JSONObject().put("exports",exports).put("fingerprints",fingerprints)
        .put("notesZips",notesZips).put("recordHashes",recordHashes).put("manifestDecrypts",manifestDecrypts)
}
