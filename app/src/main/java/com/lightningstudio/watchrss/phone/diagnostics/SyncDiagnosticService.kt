package com.lightningstudio.watchrss.phone.diagnostics

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.PrintWriter

/** AMS gates `dumpsys activity service` with DUMP before forwarding to the app thread.
 * Service.dump runs on that thread; Binder.getCallingUid there is not the original shell UID.
 * No bind API, intent data, network call, or exported component is provided. */
class SyncDiagnosticService:Service() {
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun dump(fd:FileDescriptor,writer:PrintWriter,args:Array<out String>) {
        try {
            val (section,n)=DiagnosticFields.selection(args)
            val container=(application as PhoneCompanionApplication).container
            val result=JSONObject()
            if(section=="all") {
                val snapshot=container.diagnosticLibrarySnapshot()
                result.put("books",snapshot.first).put("summary",snapshot.second)
            } else if(section=="library.books")result.put("books",container.diagnosticLibraryBooks())
            else if(section=="library.summary")result.put("summary",container.diagnosticLibrarySummary())
            if(section=="all"||section=="sync.last")result.put("sync.last",JSONObject().put("sessions",container.syncDiagnostics.last(n)))
            writer.println("WRSS_SYNC_DIAGNOSTICS "+result)

        }catch(_:IllegalArgumentException){writer.println("WRSS_SYNC_DIAGNOSTICS {\"error\":\"invalid_argument\"}")}
        catch(_:Exception){writer.println("WRSS_SYNC_DIAGNOSTICS {\"error\":\"diagnostics_unavailable\"}")}
    }
}
