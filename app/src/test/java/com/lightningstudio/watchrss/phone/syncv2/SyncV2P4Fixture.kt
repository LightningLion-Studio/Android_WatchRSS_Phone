package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

internal class SyncV2P4Fixture:AutoCloseable {
    val identity=SyncV2Fixtures.identity()
    val key=ByteArray(32){it.toByte()}
    val a="aa".repeat(16);val b="bb".repeat(16);val sid="cc".repeat(16)
    val signer=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),a,"ab".repeat(16))
    val peerSigner=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(2),b,"bc".repeat(16))
    val active=mutableSetOf(a,b)
    fun authors()=SyncV2Authors(identity){it in active}.also{authors->for(s in listOf(signer,peerSigner))authors.accept(identity.signingBinding(s,100,"0"),"0")}
    val root=Files.createTempDirectory(File(System.getenv("WRSS_P4_SCRATCH")?:System.getProperty("java.io.tmpdir")).toPath(),"wrss-p4-").toFile()
    fun store(name:String,fault:(String)->Unit={}):SyncV2ContentStore {
        val setup=SyncV2ContentStore(File(root,name),identity.scopeJson,SyncV2Fixtures.localKey)
        if(setup.load("local-snapshot")==null)setup.save("local-snapshot",JSONObject().put("refs",org.json.JSONArray()).put("serverRevision","0").put("historyFloorRevision","0").put("checkpointOid",JSONObject.NULL)
            .put("account",JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")))
        return SyncV2ContentStore(File(root,name),identity.scopeJson,SyncV2Fixtures.localKey,fault=fault)
    }
    fun session(self:String=a,peer:String=b,scope:JSONObject=identity.scope,session:String=sid,features:List<String> = emptyList(),member:(String)->String={"active"},close:()->Unit={}):SyncV2LocalSession =
        SyncV2LocalSession(scope,session,self,{key.copyOf()},member,{true},close,SyncV2LocalProtocol.Gates(true),supported=features)
    fun pair(features:List<String> = emptyList()):Pair<SyncV2LocalSession,SyncV2LocalSession> {
        val x=session(features=features);val y=session(b,a,features=features)
        val hx=x.hello("11".repeat(16));val hy=y.hello("22".repeat(16));x.receiveHello(hy);y.receiveHello(hx)
        x.receiveProofBody(y.proofBody());y.receiveProofBody(x.proofBody())
        val prepare=x.beginSwitch("dd".repeat(16));val ready=y.prepare(prepare);x.acceptReady(ready)
        y.commitRequest(JSONObject().put("switchId","dd".repeat(16)));y.finalAckWritten("dd".repeat(16));x.acceptCommit(JSONObject().put("switchId","dd".repeat(16)).put("committed",true))
        return x to y
    }
    fun blob(size:Int=150000):Pair<String,ByteArray> {
        val raw=ByteArray(size){(it*17).toByte()};val sealed=SyncV2Envelope.seal(identity,key,1,"blob",raw);return sealed.oid to sealed.bytes
    }
    fun bodyPackage(store:SyncV2ContentStore,bytes:Int=270000):JSONObject {
        val export=SyncV2ContentExport(identity,key,1,store)
        val binding=SyncV2ContentExport.Binding("11".repeat(32),"22".repeat(16),export.authenticationBinding)
        val source=object:SyncV2ContentExport.Source {
            override val size=bytes.toLong();override val version="p4-body"
            override fun read(offset:Long,maxBytes:Int)=ByteArray(maxBytes){((it+offset)%251).toByte()}
        }
        while(!export.step(binding,source)){}
        return export.descriptor(binding)!!
    }
    fun body(oid:String,window:String)=JSONObject().put("scope",identity.scope).put("windowId",window).put("oid",oid).put("range",JSONObject.NULL)
    override fun close(){root.deleteRecursively()}
}
