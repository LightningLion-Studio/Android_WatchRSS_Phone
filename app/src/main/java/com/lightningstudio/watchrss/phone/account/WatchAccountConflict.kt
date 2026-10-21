package com.lightningstudio.watchrss.phone.account

/** Shared by HTTP credential issuance and watch Bluetooth error responses. */
internal fun watchAccountConflictMessage(code: String): String? = when (code) {
    "account_switch_requires_logout", "device_owned_by_another_account", "installation_owned_by_another_account", "device_revoked" ->
        "这块手表已登录其他账号，请先在手表上退出"
    else -> null
}
