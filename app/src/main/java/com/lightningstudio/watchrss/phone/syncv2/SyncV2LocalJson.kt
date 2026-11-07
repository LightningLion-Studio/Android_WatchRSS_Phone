package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Private business-model JSON only. Legacy Room readingProgress is fractional; wire J
 * remains integer-only and goes through Business.split's readingProgressMicros conversion. */
internal object SyncV2LocalJson {
    fun canonical(value:Any?):String=when(value) {
        is JSONObject->value.keys().asSequence().sorted().joinToString(",","{","}"){SyncV2Codec.canonical(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is Float,is Double->{val n=(value as Number).toDouble();if(!n.isFinite())SyncV2Codec.fail("invalid_encoding");java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString()}
        else->SyncV2Codec.canonical(value)
    }
}
