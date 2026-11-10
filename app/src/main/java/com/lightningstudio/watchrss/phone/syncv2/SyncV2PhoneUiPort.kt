package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot/notification and asynchronous actions only. No Compose code or translated copy. */
internal class SyncV2PhoneUiPort(private val product:SyncV2PhoneProduct,private val runtime:SyncV2PhoneProduct.Runtime) {
    private val _state=MutableStateFlow(SyncV2UiState())
    val state:StateFlow<SyncV2UiState> = _state
    private fun gates()=SyncV2UiState.Gates(BuildConfig.SYNC_OBJECTS_V2_HISTORY_RESTORE,BuildConfig.SYNC_OBJECTS_V2_HISTORY_RESTORE,runtime.client.noteSegmentsRead())
    private fun gate(which:String) {
        val g=gates()
        if(!(when(which){"history"->g.historyRestore;"choice"->g.choiceApply;else->g.historyRestore||g.choiceApply||g.noteSegments}))SyncV2Codec.fail("feature_disabled")
    }
    internal fun disabled(){_state.value=SyncV2UiState()}
    internal fun failed(error:Throwable) {
        val code=(error as? SyncV2CloudError)?.code?:error.message?.takeIf{Regex("[a-z][a-z0-9_]{0,63}").matches(it)}?:"temporarily_unavailable"
        _state.value=_state.value.copy(phase=if(code in setOf("storage_pressure","trusted_time_unavailable","signing_state_unavailable"))"blocked"else"error",errorCode=code)
    }
    internal fun refresh() {
        runtime.life.check();val gates=gates();val own=runtime.signingDeviceId()
        if(!gates.historyRestore && !gates.choiceApply && !gates.noteSegments){_state.value=SyncV2UiState(ownDeviceId=own);return}
        val projection=runtime.projection;val body=projection.state().body
        val pending=runtime.actions.pendingChoice()
        val choice=pending?.let {
            val summary=runtime.actions.previewSummary(it.preview)
            runtime.actions.closePreview(it.preview)
            SyncV2UiState.Choice(summary.getJSONObject("local").getInt("count"),summary.getJSONObject("cloud").getInt("count"),it.canMerge,it.reason,
                if(body.optJSONObject("editBase")?.optString("generation")?.let{g->SyncV2Codec.d64(g)<SyncV2Codec.d64(it.generation)}==true)"rollback"else"concurrent")
        }
        val isolation=projection.noteIsolation()
        val notes=isolation.keys().asSequence().mapNotNull{entity->
            val row=projection.cloudRows(setOf(entity)).firstOrNull()?:return@mapNotNull null
            val meta=row.getJSONObject("meta")
            if(row.getJSONObject("row").getBoolean("deleted"))null else SyncV2UiState.UnopenableNote(meta.getString("logicalId"),meta.getJSONObject("payload").optString("title").takeIf{it.isNotEmpty()})
        }.toList()
        val copies=if(runtime.time.now()==null)emptyList()else runtime.actions.recoveryCopies().let{a->(0 until a.length()).map{index->
            val c=a.getJSONObject(index);val h=c.optJSONObject("header")?:c
            SyncV2UiState.RecoveryCopy(c.getString("copyId"),h.getLong("createdAtMillis"),h.getLong("expiresAtMillis"))
        }}
        val publication=body.optJSONObject("pendingAccountAction")?.let{SyncV2UiState.Publication("pending",it.optLong("createdAtMillis",0))}
        _state.value=SyncV2UiState(true,if(choice!=null)"awaiting_choice"else"idle",runtime.lastSyncedAt,null,own,choice,notes,copies,
            runtime.actions.keepDiscardedLocalChanges7Days(),gates,publication)
    }
    data class ChoiceItem(val title:String,val kind:String)
    class ChoicePreview internal constructor(internal val handle:SyncV2AccountActions.Preview,val local:List<ChoiceItem>,val cloud:List<ChoiceItem>,
        val localMore:Int,val cloudMore:Int,val canMerge:Boolean,val reason:String?)
    private fun items(value:JSONObject)=value.getJSONArray("items").let{a->(0 until a.length()).map{a.getJSONObject(it).let{item->ChoiceItem(item.getString("title"),item.getString("kind"))}}}
    suspend fun bodyStatus(kind:String,logicalId:String):JSONObject=product.action(this) {
        SyncV2ArticleBody.placeholder(kind)?:if(kind=="note")it.actions.noteStatus(logicalId) else {
            val row=it.projection.cloudRows(setOf(it.identity.entity(kind,logicalId))).firstOrNull()?.optJSONObject("row")
            JSONObject().put("placeholder",row==null || row.isNull("recipeOid")).put("reason",if(row==null || row.isNull("recipeOid"))"body_not_synced"else JSONObject.NULL).put("keepLocalBody",true)
        }
    }
    suspend fun syncNow()=product.action(this){gate("any");_state.value=_state.value.copy(phase="syncing");it.round()}
    suspend fun openChoice():ChoicePreview=product.action(this) {
        gate("choice");val p=it.actions.preview();val s=it.actions.previewSummary(p)
        ChoicePreview(p,items(s.getJSONObject("local")),items(s.getJSONObject("cloud")),s.getJSONObject("local").getInt("more"),s.getJSONObject("cloud").getInt("more"),p.canMerge,p.reason)
    }
    private suspend fun choose(preview:ChoicePreview,kind:String)=product.action(this){gate("choice");it.actions.choose(preview.handle,kind,it.keys.newestVersion)}
    suspend fun chooseLocal(preview:ChoicePreview)=choose(preview,"local")
    suspend fun chooseCloud(preview:ChoicePreview)=choose(preview,"cloud")
    suspend fun chooseMerge(preview:ChoicePreview)=choose(preview,"merge")
    suspend fun closeChoice(preview:ChoicePreview)=product.action(this){it.actions.closePreview(preview.handle)}
    suspend fun history(pageToken:String?=null):JSONObject=product.action(this){gate("history");it.actions.history(pageToken)}
    suspend fun captureRollback():ChoiceCapture=product.action(this){gate("history");it.actions.captureRollback()}
    suspend fun rollback(commitOid:String,capture:ChoiceCapture)=product.action(this){gate("history");it.actions.rollback(capture,commitOid)}
    suspend fun restoreRecovery(copyId:String)=product.action(this){gate("choice");it.actions.restoreRecovery(copyId)}
    suspend fun setKeepDiscarded(value:Boolean)=product.action(this){gate("any");it.actions.setKeepDiscardedLocalChanges7Days(value)}
}
