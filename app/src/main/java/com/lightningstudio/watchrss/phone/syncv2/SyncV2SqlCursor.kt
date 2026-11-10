package com.lightningstudio.watchrss.phone.syncv2

import android.database.Cursor
import android.database.CursorWrapper
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import androidx.sqlite.db.SupportSQLiteDatabase

/** A point-read may contain old/new note values plus their encrypted journal. Android's
 * default 2 MiB window cannot hold a legitimate near-budget note. The fixed window never
 * scales with account size; body callers select a single entity before opening the cursor. */
internal fun SupportSQLiteDatabase.controlQuery(sql:String,args:Array<out Any?> = emptyArray()):Cursor =
    query(sql,args).also{cursor->
        var native=cursor
        while(native is CursorWrapper)native=native.wrappedCursor
        if(native is SQLiteCursor)native.window=CursorWindow("syncv2-row",16L*1024*1024)
    }

internal fun syncV2SpoolDirectory(db:SupportSQLiteDatabase):java.io.File =
    db.path?.takeUnless{it==":memory:"}?.let{java.io.File(it).absoluteFile.parentFile}
        ?:java.io.File(System.getProperty("java.io.tmpdir")?:"/data/local/tmp")
