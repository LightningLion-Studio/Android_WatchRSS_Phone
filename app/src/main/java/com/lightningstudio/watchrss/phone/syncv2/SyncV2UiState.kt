package com.lightningstudio.watchrss.phone.syncv2

/** Logic-only contract. Compose components and product copy are owned by the UI task. */
internal data class SyncV2UiState(
    val enabled:Boolean=false,
    val phase:String="idle",
    val lastSyncedAtMillis:Long?=null,
    val errorCode:String?=null,
    val ownDeviceId:String?=null,
    val choice:Choice?=null,
    val unopenableNotes:List<UnopenableNote> = emptyList(),
    val recoveryCopies:List<RecoveryCopy> = emptyList(),
    val keepDiscardedLocalChanges7Days:Boolean=true,
    val gates:Gates=Gates(),
    val publication:Publication?=null
) {
    data class Choice(val localChanges:Int,val cloudChanges:Int,val canMerge:Boolean,val mergeUnavailableReason:String?,val kind:String)
    data class UnopenableNote(val noteId:String,val title:String?)
    data class RecoveryCopy(val copyId:String,val createdAtMillis:Long,val expiresAtMillis:Long)
    data class Gates(val historyRestore:Boolean=false,val choiceApply:Boolean=false,val noteSegments:Boolean=false)
    data class Publication(val state:String,val since:Long)
}
