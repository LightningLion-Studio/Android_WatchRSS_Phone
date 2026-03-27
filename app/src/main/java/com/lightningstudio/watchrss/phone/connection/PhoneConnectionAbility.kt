package com.lightningstudio.watchrss.phone.connection

import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType

enum class PhoneConnectionAbility(
    val wireCode: String,
    val displayName: String,
    val payloadAliases: Set<String> = emptySet()
) {
    REMOTE_INPUT(
        wireCode = "dc40517c-a09c-419c-8c4d-d3883258992e",
        displayName = "RSS订阅输入",
        payloadAliases = setOf("watchrss-remote-input")
    ),
    SYNC_FAVORITES(
        wireCode = "c4bf141f-b0de-46f7-a661-0a3ad0716bce",
        displayName = "收藏夹"
    ),
    SYNC_WATCH_LATER(
        wireCode = "f1aa43bd-0fe3-4771-ae6b-d4799ecf84b5",
        displayName = "稍后阅读"
    );

    val savedItemType: PhoneSavedItemType?
        get() = when (this) {
            REMOTE_INPUT -> null
            SYNC_FAVORITES -> PhoneSavedItemType.FAVORITE
            SYNC_WATCH_LATER -> PhoneSavedItemType.WATCH_LATER
        }

    companion object {
        fun fromPayloadValue(value: String): PhoneConnectionAbility {
            val normalized = value.trim()
            return entries.firstOrNull { ability ->
                ability.name == normalized ||
                    ability.wireCode == normalized ||
                    ability.displayName == normalized ||
                    normalized in ability.payloadAliases
            } ?: throw IllegalArgumentException("未知能力标识：$value")
        }
    }
}
