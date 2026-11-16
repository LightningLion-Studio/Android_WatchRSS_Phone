package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

/** Local-only authority travels with a request, including negotiation copies.
 * The guard encloses the complete synchronous frame write, never the response wait.
 * It adds no fields to the wire payload.
 */
internal class AuthorizedBluetoothRequest(
    payload: JSONObject,
    private val authorizeWrite: (() -> Unit) -> Unit
) : JSONObject(payload.toString()) {
    internal fun writeAuthorized(write: () -> Unit) = authorizeWrite(write)

    internal fun copyWithPayload(payload: JSONObject) = AuthorizedBluetoothRequest(payload, authorizeWrite)
}
