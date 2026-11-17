package com.lightningstudio.watchrss.phone.diagnostics

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import org.json.JSONObject
import org.json.JSONArray
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors

/** AMS checks DUMP before forwarding. The main-thread callback only requests/polls bounded work. */
class SyncDiagnosticService:Service() {
    private val worker=Executors.newSingleThreadExecutor { task->Thread(task,"SyncDiagnostics").apply{isDaemon=true} }
    private val cache=DiagnosticSnapshotCache({ task->worker.execute(task) })
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
    internal var collect:((DiagnosticQuerySelection)->String)?=null
    override fun dump(fd:FileDescriptor,writer:PrintWriter,args:Array<out String>) {
        val result=try {
            val selection=DiagnosticQuerySelection.parse(args)
            cache.poll(selection){collect?.invoke(selection)?:snapshot(selection)}
        }catch(_:IllegalArgumentException){"{\"error\":\"invalid_argument\"}"}
        writer.println("WRSS_SYNC_DIAGNOSTICS $result")
    }
    private fun snapshot(query:DiagnosticQuerySelection):String {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        val container=(application as PhoneCompanionApplication).container
        val result=JSONObject().put("snapshotAt",System.currentTimeMillis())
        if(query.section=="all"||query.section=="library.books"){
            val page=container.diagnosticLibraryPage(query.offset,query.limit)
            result.put("books",page.getJSONArray("books")).put("offset",page.getInt("offset")).put("nextOffset",page.get("nextOffset"))
        }
        if(query.section=="all"||query.section=="library.summary")result.put("summary",container.diagnosticLibrarySummary())
        if(query.section=="all"||query.section=="sync.last"){
            val rows=container.syncDiagnostics.last(query.n);var more=false
            for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val items=row.getJSONArray("items");more=more||items.length()>query.itemOffset+query.itemLimit
                row.put("items",JSONArray().apply{for(j in query.itemOffset until minOf(items.length(),query.itemOffset+query.itemLimit))put(items.get(j))})}
            result.put("sync.last",JSONObject().put("sessions",rows).put("itemOffset",query.itemOffset).put("nextItemOffset",if(more)query.itemOffset+query.itemLimit else JSONObject.NULL))
        }
        return result.toString()
    }
}

internal data class DiagnosticQuerySelection(val section:String,val n:Int=3,val offset:Int=0,val limit:Int=8,val itemOffset:Int=0,val itemLimit:Int=4){
    companion object {
        fun parse(args:Array<out String>):DiagnosticQuerySelection {
            fun integer(value:String,min:Int,max:Int):Int{require(value.matches(Regex("0|[1-9][0-9]*")));return value.toIntOrNull()?.also{require(it in min..max)}?:throw IllegalArgumentException()}
            if(args.firstOrNull()=="library.books") {require(args.size==1||args.size==3);return DiagnosticQuerySelection("library.books",offset=if(args.size==3)integer(args[1],0,Int.MAX_VALUE)else 0,limit=if(args.size==3)integer(args[2],1,12)else 8)}
            if(args.firstOrNull()=="sync.last"&&args.size==4)return DiagnosticQuerySelection("sync.last",n=integer(args[1],1,12),itemOffset=integer(args[2],0,256),itemLimit=integer(args[3],1,4))
            val (section,n)=DiagnosticFields.selection(args);return DiagnosticQuerySelection(section,n)
        }
    }
}

/** One job and one cached page; polling never queues unbounded jobs or waits on the main thread. */
internal class DiagnosticSnapshotCache(private val execute:(Runnable)->Unit,private val now:()->Long=android.os.SystemClock::elapsedRealtime) {
    private var running:DiagnosticQuerySelection?=null;private var key:DiagnosticQuerySelection?=null;private var value:String?=null;private var expires=0L
    @Synchronized fun poll(query:DiagnosticQuerySelection,read:()->String):String {
        if(key==query&&value!=null&&now()<expires)return value!!
        if(running!=null)return "{\"status\":\"pending\"}"
        running=query
        execute(Runnable {
            val result=try{read().let{if(it.length>60000)"{\"error\":\"diagnostics_budget_exceeded\"}" else it}}catch(_:Exception){"{\"error\":\"diagnostics_unavailable\"}"}
            synchronized(this){key=query;value=result;expires=now()+10000;running=null}
        })
        return "{\"status\":\"pending\"}"
    }
}
