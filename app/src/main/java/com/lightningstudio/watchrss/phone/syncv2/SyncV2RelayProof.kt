package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** Signature-only validation shared by offline local receipt and the cloud carrier.
 * Local validation queues an unmodified request; only the cloud can authorize its publication. */
internal class SyncV2RelayProof(private val identity:SyncV2Identity,private val authors:SyncV2Authors,private val relayDeviceId:String) {
    fun validate(grant:JSONObject,commit:JSONObject,update:JSONObject,now:Long,allowStaged:Boolean=false):JSONObject {
        SyncV2Codec.exact(grant,setOf("body","signature"));val b=grant.getJSONObject("body")
        SyncV2Codec.exact(b,setOf("version","scope","authorDeviceId","relayDeviceId","ref","commitOid","objectOids","expiresAtMillis","grantId"))
        if(b.get("version")!=2)SyncV2Codec.fail("invalid_schema")
        if(SyncV2Codec.scope(b.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        val author=SyncV2LocalProtocol.id(SyncV2Wire.string(b,"authorDeviceId"));val relay=SyncV2LocalProtocol.id(SyncV2Wire.string(b,"relayDeviceId"))
        SyncV2LocalProtocol.id(SyncV2Wire.string(b,"grantId"));authors.publicKey(relay)
        val pub=authors.publicKey(author)
        if(relay!=relayDeviceId || author==relay || b.get("ref")!="refs/devices/$author/index")SyncV2Codec.fail("relay_denied")
        val expiry=SyncV2Wire.uint(b,"expiresAtMillis")
        if(expiry<now-30000 || expiry>now+630000)SyncV2Codec.fail("relay_denied")
        val oids=SyncV2Wire.strings(b.getJSONArray("objectOids"),512);oids.forEach{SyncV2Wire.oid(it)}
        if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/relay-grant-sign"),SyncV2LocalProtocol.bytes(b)),SyncV2Wire.string(grant,"signature")))SyncV2Codec.fail("signature_invalid")
        val cb=authors.header(commit,false);val oid=SyncV2Codec.oid("commit",cb)
        if(cb.get("authorDeviceId")!=author || b.get("commitOid")!=oid)SyncV2Codec.fail("relay_denied")
        verifyUpdate(update,author,pub,oid,now)
        if(update.getJSONObject("body").get("ref")!=b.get("ref"))SyncV2Codec.fail("relay_denied")
        if(update.getJSONObject("body").opt("expectedOid")!=cb.opt("previousOwnCommit") && !(allowStaged && update.getJSONObject("body").opt("expectedOid")==oid))SyncV2Codec.fail("relay_denied")
        return b
    }
    fun verifyUpdate(update:JSONObject,author:String,pub:String,oid:String,now:Long) {
        SyncV2Codec.exact(update,setOf("body","signature"));val b=update.getJSONObject("body")
        SyncV2Codec.exact(b,setOf("version","scope","ref","expectedOid","expectedRevision","newCommitOid","requestId","issuedAtMillis","expiresAtMillis"))
        if(b.get("version")!=2 || SyncV2Codec.scope(b.getJSONObject("scope"))!=identity.scopeJson)SyncV2Codec.fail("scope_mismatch")
        if(b.get("newCommitOid")!=oid || b.get("ref") !in setOf("refs/devices/$author/index","refs/account/current"))SyncV2Codec.fail("relay_denied")
        if(!b.isNull("expectedOid"))SyncV2Wire.oid(b.getString("expectedOid"),"commit")
        SyncV2Codec.d64(b.getString("expectedRevision"));SyncV2LocalProtocol.id(b.getString("requestId"))
        val issued=SyncV2Wire.uint(b,"issuedAtMillis");val expiry=SyncV2Wire.uint(b,"expiresAtMillis")
        if(expiry<=issued || expiry-issued>300000 || issued>now+30000 || expiry<now-30000)SyncV2Codec.fail("relay_denied")
        if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/ref-update-sign"),SyncV2LocalProtocol.bytes(b)),b64(update)))SyncV2Codec.fail("signature_invalid")
    }
    private fun b64(value:JSONObject)=SyncV2Wire.string(value,"signature")

    fun publication(request:JSONObject,commit:JSONObject,now:Long):JSONObject {
        SyncV2Codec.exact(request,setOf("account","device","relayGrant"))
        val b=validate(request.getJSONObject("relayGrant"),commit,request.getJSONObject("device"),now,true)
        val account=request.getJSONObject("account");val device=request.getJSONObject("device")
        verifyUpdate(account,b.getString("authorDeviceId"),authors.publicKey(b.getString("authorDeviceId")),b.getString("commitOid"),now)
        if(account.getJSONObject("body").get("ref")!="refs/account/current" || account.getJSONObject("body").get("requestId")!=device.getJSONObject("body").get("requestId"))SyncV2Codec.fail("relay_denied")
        if(account.getJSONObject("body").opt("expectedOid")!=commit.getJSONObject("body").opt("accountParent"))SyncV2Codec.fail("ref_conflict")
        return b
    }
}
