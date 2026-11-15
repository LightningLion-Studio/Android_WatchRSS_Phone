package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** One repair budget follows the complete operation, including nested reader/resource actions. */
internal class AccountRepairBudget : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccountRepairBudget>
    var repaired = false
}

internal class AccountDataSession(val session: PhoneSyncSession, val gate: AccountSyncGate? = null, val owner: String? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccountDataSession>
}

internal suspend fun <T> withAccountOperation(block: suspend () -> T): T =
    if (currentCoroutineContext()[AccountRepairBudget] != null) block()
    else withContext(AccountRepairBudget()) { block() }
