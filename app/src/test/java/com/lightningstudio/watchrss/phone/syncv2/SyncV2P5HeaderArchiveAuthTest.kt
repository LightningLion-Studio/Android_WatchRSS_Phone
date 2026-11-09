package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** N01 regression. archiveHeaders() shrinks the body's knownControlHeaders/knownAccountParents
 * to the newest 2 entries each on every writer commit. These product regressions prove the
 * shrink does not degrade authentication: previously authenticated old headers stay
 * acceptable through the sealed header archive and accountKnownHistoricalOid after the body
 * shrinks; unauthenticated or forged old headers stay rejected; and an archived old commit
 * can never be re-accepted as new state. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5HeaderArchiveAuthTest {
    private fun code(fn:()->Unit):String?=try{fn();null}catch(e:IllegalStateException){e.message}
    private fun bodyOids(w:SyncV2P5Fixture):Set<String> {
        val body=w.projection.state().body;val out=mutableSetOf<String>()
        body.optJSONArray("knownControlHeaders")?.let{a->for(i in 0 until a.length())out.add(SyncV2Codec.oid("commit",a.getJSONObject(i).getJSONObject("body")))}
        body.optJSONArray("knownAccountParents")?.let{a->for(i in 0 until a.length())out.add(a.getJSONObject(i).getString("oid"))}
        return out
    }
    /** 12 authenticated normal commits; every writer commit shrinks the body, so only the two
     * newest headers survive there while the older ones live exclusively in the sealed archive. */
    private fun shrink(w:SyncV2P5Fixture,depth:Int):Pair<String,Int> {
        var parent=w.initial
        val authors=w.f.authors()
        repeat(depth){index->
            parent=w.commit(listOf(w.note("title-$index")),parent,"0","normal",null)
            w.projection.observeVerified(JSONObject().put("oid",parent).put("revision",(index+3).toString()).put("generation","0"),listOf(w.headers.getValue(parent)),authors::header,{w.headers.getValue(it)})
        }
        return parent to depth+2
    }
    @Test fun archivedOldHeaderStillAuthenticatesTheRollbackAfterTheBodyShrank():Unit=SyncV2P5Fixture().use{w->
        w.start();val first=w.initial;val (parent,_)=shrink(w,12)
        val body=w.projection.state().body
        assertTrue(body.getJSONArray("knownControlHeaders").length()<=2)
        assertTrue(body.getJSONArray("knownAccountParents").length()<=2)
        assertFalse(first in bodyOids(w)) // Proof the body really dropped the old authenticated header.
        val rolled=w.commit(listOf(w.note("old title")),parent,"1","rollback",first)
        w.headers.remove(first) // Only the sealed local archive can still supply the old header.
        w.setCurrent(rolled,"999")
        assertNull(code{w.actions().refresh()})
        assertEquals(rolled,w.projection.state().body.getJSONObject("account").getString("oid"))
        assertEquals(0,w.publishCalls)
    }
    private fun watermark(w:SyncV2P5Fixture)=w.projection.state().body.optJSONObject("knownAuthors")?.getJSONObject(w.f.signer.deviceId)?.getString("oid")
    @Test fun forgedOldHeadersAreRejectedInsteadOfSilentlyBypassingTheArchive():Unit=SyncV2P5Fixture().use{w->
        w.start();val first=w.initial;val (parent,_)=shrink(w,12)
        // A. A SQL-tampered archive row must fail closed with hash_mismatch. The header()
        //    lookup throws before any network fallback, so a corrupted old header is never
        //    quietly swapped for another copy.
        val rolled=w.commit(listOf(w.note("old title")),parent,"1","rollback",first)
        w.sql.execSQL("UPDATE syncv2_p5_header_archive SET body=? WHERE oid=?",arrayOf(byteArrayOf(0),first))
        w.setCurrent(rolled,"999")
        assertEquals("hash_mismatch",code{w.actions().refresh()})
        assertEquals(parent,w.projection.state().body.getJSONObject("account").getString("oid"))
        assertEquals(parent,watermark(w))
        // B. A forged header the phone never authenticated, supplied by the (untrusted) server
        //    for an old slot: a mutated body under the preserved signature fails the ECDSA
        //    check even though history is sparse; it is never adopted.
        val forged=w.commit(listOf(w.note("ghost")),parent,"0","normal",null)
        val genuine=w.headers.getValue(forged)
        w.headers[forged]=JSONObject().put("body",JSONObject(genuine.getJSONObject("body").toString()).put("createdAtMillis",w.f.now+5)).put("signature",genuine.getString("signature"))
        w.setCurrent(forged,"1000")
        assertEquals("signature_invalid",code{w.actions().refresh()})
        assertEquals(parent,w.projection.state().body.getJSONObject("account").getString("oid"))
        assertEquals(parent,watermark(w))
        assertEquals(0,w.publishCalls)
        // C. The authenticated path itself still advances afterwards (no collateral lockout).
        w.headers[forged]=genuine // Honest re-serve; the new commit's own-chain passes through it.
        val next=w.commit(listOf(w.note("title-12")),parent,"0","normal",null)
        w.setCurrent(next,"1001")
        assertNull(code{w.actions().refresh()})
        assertEquals(next,w.projection.state().body.getJSONObject("account").getString("oid"))
    }
    @Test fun archivedOldCommitCannotBeReacceptedAsNewStateAfterTheShrink():Unit=SyncV2P5Fixture().use{w->
        w.start();val first=w.initial;val (parent,_)=shrink(w,12)
        // The replay pin must survive in the archive even though the body kept only two parents.
        assertFalse(first in bodyOids(w));assertTrue(w.projection.accountKnownHistoricalOid(first))
        val account=w.projection.state().body.getJSONObject("account").getString("oid")
        w.setCurrent(first,"999")
        assertEquals("replay_detected",code{w.actions().refresh()})
        assertEquals(account,w.projection.state().body.getJSONObject("account").getString("oid"))
        assertEquals(account,watermark(w))
        assertEquals(0,w.publishCalls)
        // A fresh forward commit still authenticates after the rejected replay.
        val next=w.commit(listOf(w.note("title-12")),parent,"0","normal",null)
        w.setCurrent(next,"1000")
        assertNull(code{w.actions().refresh()})
        assertEquals(next,w.projection.state().body.getJSONObject("account").getString("oid"))
    }
}
