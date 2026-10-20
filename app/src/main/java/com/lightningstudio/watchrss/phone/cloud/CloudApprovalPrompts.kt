package com.lightningstudio.watchrss.phone.cloud

import android.app.Activity
import android.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** One foreground poller across all activities. Never launches an activity from the background. */
class CloudApprovalPrompts(
    private val service: () -> PhoneCloudSyncService,
    private val owner: () -> String?,
    private val allowed: () -> Boolean
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job: Job?=null
    private var dialog: AlertDialog?=null
    private data class Notice(val owner:String,val expiresAt:Long,val name:String,val code:String,var dismissed:Boolean=false)
    private var notice:Notice?=null
    fun resume(activity:Activity) {
        pause()
        job=scope.launch {
            while(isActive && !activity.isFinishing && !activity.isDestroyed) {
                val current=owner()
                if(notice?.let { it.owner!=current || it.expiresAt<=System.currentTimeMillis() }==true)notice=null
                if(allowed() && current!=null) {
                    try {
                        if(dialog==null) {
                            val target=service().pendingCodeApprovals().firstOrNull()
                            if(target!=null && owner()==current) {
                                val code=String.format(java.util.Locale.US,"%08d",SecureRandom().nextInt(100_000_000))
                                service().offerCodeApproval(target,code)
                                if(owner()==current)notice=Notice(current,target.getLong("expiresAt"),target.optString("displayName","新设备").take(60),code)
                            }
                        }
                        val item=notice
                        if(item!=null && !item.dismissed && item.owner==owner() && dialog==null && isActive) {
                            dialog=AlertDialog.Builder(activity)
                                .setTitle("新设备请求云同步授权")
                                .setMessage("${item.name}\n配对码：${item.code}\n请在你正在添加的新设备输入，5 分钟内有效。若不是你本人操作，请勿提供配对码。")
                                .setPositiveButton("我知道了") { _,_ -> item.dismissed=true;dialog=null }
                                .setOnCancelListener { item.dismissed=true;dialog=null }
                                .show()
                        }
                    } catch (_:Exception) { /* Offline/expired requests retry only while foreground. */ }
                }
                delay(15_000)
            }
        }
    }
    fun pause() {job?.cancel();job=null;dialog?.dismiss();dialog=null}
}
