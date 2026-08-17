package com.lightningstudio.watchrss.phone.update

internal fun shouldOfferStoreUpdate(
    marketAvailable: Boolean,
    storeVersionCode: Int?,
    currentVersionCode: Int
): Boolean = marketAvailable && storeVersionCode != null && storeVersionCode > currentVersionCode
