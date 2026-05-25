package com.lightningstudio.watchrss.phone.connection

enum class PhoneConnectionAbility(
    val wireCode: String,
    val displayName: String,
    val acousticCode: String
) {
    REMOTE_INPUT(
        wireCode = "dc40517c-a09c-419c-8c4d-d3883258992e",
        displayName = "RSS订阅输入",
        acousticCode = "r"
    ),
    SYNC_FAVORITES(
        wireCode = "c4bf141f-b0de-46f7-a661-0a3ad0716bce",
        displayName = "收藏夹",
        acousticCode = "f"
    ),
    SYNC_WATCH_LATER(
        wireCode = "f1aa43bd-0fe3-4771-ae6b-d4799ecf84b5",
        displayName = "稍后阅读",
        acousticCode = "w"
    );

    companion object {
        fun fromPayloadValue(value: String): PhoneConnectionAbility {
            val normalized = value.trim()
            return entries.firstOrNull { ability ->
                ability.name == normalized ||
                    ability.wireCode == normalized ||
                    ability.displayName == normalized ||
                    ability.acousticCode == normalized
            } ?: throw IllegalArgumentException("未知能力标识：$value")
        }
    }
}
