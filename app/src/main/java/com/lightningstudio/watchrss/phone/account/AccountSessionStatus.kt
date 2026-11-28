package com.lightningstudio.watchrss.phone.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

internal enum class AccountSessionStatus { SIGNED_OUT, EXPIRED, USABLE }

internal fun accountSessionStatus(
    session: PhoneAccountSession?,
    reauthenticationOwner: String?,
    now: Long = System.currentTimeMillis()
): AccountSessionStatus = when {
    reauthenticationOwner != null -> AccountSessionStatus.EXPIRED
    session == null -> AccountSessionStatus.SIGNED_OUT
    session.authenticationPaused || session.userId.isBlank() || session.accessToken.isBlank() ||
        (session.expiresAtMillis in 1..now) -> AccountSessionStatus.EXPIRED
    else -> AccountSessionStatus.USABLE
}

@Composable
internal fun rememberAccountSessionStatus(repository: PhoneAccountRepository): AccountSessionStatus {
    val session by repository.session.collectAsState()
    val owner by repository.reauthenticationOwner.collectAsState()
    val expiry = session?.expiresAtMillis ?: 0
    val expiredAt by produceState(0L, expiry) {
        if (expiry > 0) {
            val remaining = expiry - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            value = expiry
        }
    }
    val now = System.currentTimeMillis()
    return accountSessionStatus(session, owner, if (expiredAt == expiry) maxOf(expiry, now) else now)
}
