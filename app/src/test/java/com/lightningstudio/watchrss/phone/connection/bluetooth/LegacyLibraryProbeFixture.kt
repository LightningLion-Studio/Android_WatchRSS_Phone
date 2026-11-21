package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

/** Identifies the synthetic v14 response as a legacy peer in either B2 configuration. */
internal fun JSONObject.asLegacyPodJsProbe(): JSONObject =
    put("phase", "probe").put("libraryPeer", JSONObject().put("implementation", "podjs").put("appVersion", "2.0.1-2"))
