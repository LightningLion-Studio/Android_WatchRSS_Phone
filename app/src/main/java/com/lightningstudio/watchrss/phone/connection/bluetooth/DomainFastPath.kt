package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import org.json.JSONArray
import org.json.JSONObject

/** One frozen, physical-connection window. A staged receipt is not durable before complete's applied ACK. */
internal class DomainFastPath(
    private val phoneId:String,private val peer:String,
    val local:Map<String,String>,private val known:Map<String,Pair<String,String>>,
    private val versions:Map<String,BleDomainStore.Projection>
) {
    private var remote:Map<String,String> = emptyMap()
    private var remoteVersions:Map<String,BleDomainStore.Projection> = emptyMap()
    private var window:String?=null
    var skipped:Set<String> = emptySet();private set
    private var staged:Map<String,Pair<String,String>> = emptyMap()
    private var committed=false
    val remoteRootCount get()=remote.size
    val missingRemote get()=BleDomainStore.DOMAINS.filter{it !in remote}
    private var endRemote:Map<String,String> = emptyMap()
    private var retries:Set<String> = emptySet()
    fun diagnostics(current:Map<String,String>):Map<String,Any> =mapOf(
        "retryDomains" to retries.joinToString(","),
        "localChanged" to BleDomainStore.DOMAINS.filter{local[it]!=current[it]}.joinToString(","),
        "remoteChanged" to BleDomainStore.DOMAINS.filter{remote[it]!=endRemote[it]}.joinToString(","),
        "missingRemote" to missingRemote.joinToString(","))
    val remoteAccountRoot get()=remote["account"]
    fun accountGrantWitness(remoteRoot:String?):String? = remoteRoot?.let { root->
        versions["account"]?.let {p->BleDomainStore.hash("watchrss.ble.account-grant.v2\n${p.text("account")}\n$root")}
    }
    var finalRemoteAccountRoot:String?=null;private set
    companion object {
        fun enabled(cap:BleSyncCapabilities,persistent:Boolean,forceFull:Boolean,mode:LibrarySyncMode)=persistent&&!forceFull&&mode==LibrarySyncMode.MERGE&&cap.domainFastPath&&cap.canonicalRssIds&&cap.books&&cap.bookReadingDelta
        private fun roots(value:JSONObject):Map<String,String> = value.keys().asSequence().associateWith { key->
            require(key in BleDomainStore.DOMAINS){"未知同步域"};val root=value.get(key);require(root is String&&root.matches(Regex("[0-9a-f]{64}"))){"无效同步域根"};root
        }
        private fun domains(value:JSONArray):Set<String> {
            val rows=(0 until value.length()).map{value.get(it).also {d->require(d is String&&d in BleDomainStore.DOMAINS){"未知同步域"}} as String}
            require(rows.distinct().size==rows.size){"重复同步域"};return rows.toSet()
        }
    }
    private fun request(phase:String)=JSONObject().put("action","syncDomains").put("version",2).put("phase",phase).put("deviceId",phoneId).put("projectionVersions",JSONObject().apply { versions.forEach { (d,p)->put(d,p.json()) } })
    private fun validate(response:JSONObject,phase:String) {
        require(response.opt("success")==true&&response.optString("action")=="syncDomains"&&response.opt("version")==2&&response.optString("phase")==phase&&response.optString("deviceId")==peer){"手表同步域响应不匹配"}
    }
    private fun projections(response:JSONObject,roots:Map<String,String>):Map<String,BleDomainStore.Projection> {
        val value=response.getJSONObject("projectionVersions")
        val result=value.keys().asSequence().associateWith { d->
            require(d in BleDomainStore.DOMAINS)
            val p=value.getJSONObject(d);val e=p.get("encoder");val s=p.get("schema");val h=p.get("definition")
            require(e is Int && e>0 && s is Int && s>=0 && h is String && h.matches(Regex("[0-9a-f]{64}"))) {"无效投影版本"}
            BleDomainStore.Projection(e,s,h)
        }
        require(result.keys.containsAll(roots.keys)){"同步域缺少投影版本"}
        return result
    }
    fun summaryRequest():JSONObject = request("summary").put("profile",BleDomainStore.PROFILE).put("roots",JSONObject(local)).put("known",JSONObject().apply{
        known.filterKeys{it in local}.forEach{(d,p)->put(d,JSONObject().put("localRoot",p.first).put("remoteRoot",p.second))}
    })
    fun acceptSummary(response:JSONObject) {
        validate(response,"summary")
        val id=response.getString("windowId");require(id.length in 1..128&&id.all{it.code in 0x21..0x7e}){"无效同步窗口"}
        val nextRemote=roots(response.getJSONObject("roots"));val nextVersions=projections(response,nextRemote);val skip=domains(response.getJSONArray("skipDomains"))
        require(skip.all{d->local[d]!=null&&nextRemote[d]!=null&&known[d]==(local.getValue(d) to nextRemote.getValue(d))}){"手表错误跳过同步域"}
        window=id;remote=nextRemote;remoteVersions=nextVersions;skipped=skip
    }
    fun commitRequest(current:Map<String,String>,completed:Set<String>):JSONObject {
        require(completed.all{it in BleDomainStore.DOMAINS})
        return request("commit").put("windowId",requireNotNull(window)).put("roots",JSONObject(current)).put("completedDomains",JSONArray(BleDomainStore.DOMAINS.filter{it in completed}))
    }
    fun acceptCommit(response:JSONObject,current:Map<String,String>,completed:Set<String>) {
        validate(response,"commit");require(response.getString("windowId")==window){"同步窗口已变化"}
        val endRemote=roots(response.getJSONObject("roots"));val endVersions=projections(response,endRemote);val saved=domains(response.getJSONArray("committedDomains"));val retry=domains(response.getJSONArray("retryDomains"))
        require(saved.intersect(retry).isEmpty()&&(saved+retry)==completed){"同步域提交集合不匹配"}
        require(saved.all{d->local[d]!=null&&remote[d]!=null&&local[d]==current[d]&&remote[d]==endRemote[d]&&remoteVersions[d]==endVersions[d]}){"手表错误提交变化中的同步域"}
        this.endRemote=endRemote;retries=retry
        finalRemoteAccountRoot=endRemote["account"]
        staged=saved.associateWith{local.getValue(it) to remote.getValue(it)};committed=true
    }
    fun publishable(current:Map<String,String>,completeAppliedAck:Boolean):Map<String,Pair<String,String>> =
        if(!committed||!completeAppliedAck)emptyMap() else staged.filter{(d,p)->current[d]==p.first}
}
