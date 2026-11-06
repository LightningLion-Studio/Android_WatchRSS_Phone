package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONObject

/** Only application-authenticated, current-session Bluetooth evidence may set bluetooth=true. */
internal data class SyncV2TransportSignals(val charging:Boolean?=null,val wifi:Boolean?=null,
    val bluetooth:Boolean=false,val internet:Boolean?=null,val cellular:Boolean?=null,val localRoute:Boolean=false) {
    fun eligible(allowChargingCellular:Boolean=true):JSONObject {
        val large=charging==true || wifi==true || bluetooth
        // An unknown active route cannot establish that the charging-cellular exception is safe.
        val cloud=large && internet==true && (cellular!=null || wifi==true || bluetooth) &&
            !(cellular==true && charging==true && wifi!=true && !bluetooth && !allowChargingCellular)
        return JSONObject().put("largeEligible",large).put("cloudAllowed",cloud).put("localAllowed",large && localRoute)
    }
}

internal class SyncV2ContentSettings(context:Context) {
    private val prefs=context.getSharedPreferences("watchrss-syncv2-content-settings",Context.MODE_PRIVATE)
    var allowChargingCellular:Boolean
        get()=prefs.getBoolean("allowChargingCellular",true)
        set(value){if(!prefs.edit().putBoolean("allowChargingCellular",value).commit())SyncV2Codec.fail("storage_pressure")}
}

internal object SyncV2AndroidTransport {
    fun sample(context:Context,authenticatedBluetoothSession:Boolean=false,authenticatedLocalRouteAlive:Boolean=false):SyncV2TransportSignals {
        val charging=runCatching {
            val intent=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?:return@runCatching null
            charging(intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1),intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1))
        }.getOrNull()
        val caps=runCatching {val cm=context.getSystemService(ConnectivityManager::class.java);cm.getNetworkCapabilities(cm.activeNetwork)}.getOrNull()
        return network(charging,caps,authenticatedBluetoothSession,authenticatedLocalRouteAlive)
    }
    internal fun charging(status:Int,plugged:Int):Boolean?=when(status) {
        BatteryManager.BATTERY_STATUS_CHARGING->if(plugged>0)true else null
        BatteryManager.BATTERY_STATUS_FULL->if(plugged<0)null else plugged>0
        BatteryManager.BATTERY_STATUS_DISCHARGING,BatteryManager.BATTERY_STATUS_NOT_CHARGING->false
        else->null
    }
    internal fun network(charging:Boolean?,caps:NetworkCapabilities?,bluetooth:Boolean,local:Boolean):SyncV2TransportSignals {
        val wifi=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cellular=caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hiddenRoute=caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true && wifi!=true && cellular!=true
        return SyncV2TransportSignals(charging,if(hiddenRoute)null else wifi,bluetooth,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),if(hiddenRoute)null else cellular,local)
    }
}

/** Priority metadata never crosses the cloud boundary. Parent classification is inherited locally. */
internal object SyncV2ContentPriority {
    data class Item(val entityKey:String,val kind:String,val addedAtMillis:Long,val importedFile:Boolean=false,
        val complete:Boolean=false,val deleted:Boolean=false,val parentKind:String?=null,val parentEntity:String?=null,
        val demandAtMillis:Long?=null,val demandExpiresAtMillis:Long?=null,val blockIndex:Int=0)
    fun order(items:List<Item>,now:Long):List<Pair<Item,Int>> {
        val recent=items.filter{!it.deleted && it.importedFile && it.parentEntity==null && it.kind in setOf("book","asset","file")}
            .sortedWith(compareByDescending<Item>{it.addedAtMillis}.thenBy{it.entityKey}).take(2).map{it.entityKey}.toSet()
        return items.filter{!it.deleted && !it.complete}.map { item -> item to when {
            item.demandAtMillis!=null && item.demandExpiresAtMillis!=null && item.demandExpiresAtMillis>now->0
            item.kind in setOf("note","memo") || item.parentKind in setOf("note","memo")->1
            (item.parentEntity?:item.entityKey) in recent->2
            item.kind in setOf("article","rss") || item.parentKind in setOf("article","rss")->3
            else->4
        }}.sortedWith(compareBy<Pair<Item,Int>>{it.second}.thenByDescending{if(it.second==0)it.first.demandAtMillis?:0 else it.first.addedAtMillis}
            .thenBy{it.first.entityKey}.thenBy{it.first.blockIndex})
    }
    /** Persist after completed blobs only. Reconnects and renewed demand do not reset it. */
    data class Fairness(var demanded:Int=0,var nextOrdinary:Int=1) {
        init {if(demanded !in 0..8 || nextOrdinary !in 1..4)SyncV2Codec.fail("invalid_schema")}
        fun choose(ranks:Set<Int>):Int? {
            if(ranks.isEmpty())return null
            if(ranks.any{it !in 0..4})SyncV2Codec.fail("invalid_schema")
            if(0 !in ranks)return ranks.minOrNull()
            if(demanded<8 || ranks.size==1)return 0
            return (0..3).map{(nextOrdinary-1+it)%4+1}.firstOrNull{it in ranks}?:0
        }
        fun completed(rank:Int) {if(rank==0)demanded=(demanded+1).coerceAtMost(8) else {demanded=0;nextOrdinary=rank%4+1}}
    }
}
