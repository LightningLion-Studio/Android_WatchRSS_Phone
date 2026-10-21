package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.CancellationException

/** R10: loss of connectivity cannot retain the local device authorization on logout. */
internal suspend fun releaseCloudDevice(revokeRemote: suspend () -> Unit, clearDevicePrivateKey: () -> Unit) {
    try { revokeRemote() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { /* The account logout still proceeds and clears the local cloud scope. */ }
    finally { clearDevicePrivateKey() }
}

/** Explicit user-confirmed R20 repair: never discard an identity if self-revocation failed. */
internal suspend fun reregisterCloudDevice(
    revokeRemote: suspend () -> Unit,
    replaceIdentity: () -> Unit,
    registerReplacement: suspend () -> Unit
) {
    revokeRemote()
    replaceIdentity()
    registerReplacement()
}
