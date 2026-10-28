package com.lightningstudio.watchrss.phone.data.repo

import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.cloud.*
import com.lightningstudio.watchrss.phone.data.db.*
import org.json.JSONArray
import org.json.JSONObject

/** Sources, exact progress and accepted raw versions survive all synchronization-cache resets. */
data class ArticleProvenanceReadState(val readableRecordJson:String,val positionStatus:String,val readOnly:Boolean,val editPending:Boolean)
class ArticleProvenanceStore(private val db:PhoneCompanionDatabase) {
    private val dao get()=db.articleProvenanceDao()
    @Volatile private var observedProtection:Boolean?=null
    suspend fun anyProtected():Boolean=observedProtection?:dao.anyProtected().also{observedProtection=it}
    internal suspend fun record(id:String):ArticleRegisterProvenance.Record?=(if(anyProtected())dao.get(id) else null)?.raw?.takeIf{it.isNotEmpty() && ArticleRegisterProvenance.marked(CloudRawDocument.parse(it))}
        ?.let { ArticleRegisterProvenance.decodeRetained(CloudRawDocument.parse(it)) }
    internal suspend fun readOnlyValue(id:String):JSONObject? {
        if(!anyProtected())return null
        val raw=dao.get(id)?.raw?.takeIf{it.isNotEmpty()}?:return null
        val doc=CloudRawDocument.parse(raw)
        return if(ArticleRegisterProvenance.marked(doc))ArticleProvenanceValue(ArticleRegisterProvenance.decodeRetained(doc))
            else ReadOnlyArticleValue(doc,db.phoneArticleDao().getById(id)?.let(::entityValue)?:doc.json().getJSONObject("value"))
    }
    suspend fun pendingCount()=dao.pendingCount()
    fun observePendingCount()=dao.observePendingCount()
    /** Only explicit local-library/account replacement may clear business witnesses, never cache reset. */
    suspend fun clearLibrary()=db.withTransaction {
        dao.clearWitnesses();dao.clearOriginals();dao.clearVersions();observedProtection=null
    }
    suspend fun state(id:String):ArticleProvenanceReadState? {
        val value=readOnlyValue(id)?:return null
        val source=if(value is ArticleProvenanceValue)value.record.original.source else (value as ReadOnlyArticleValue).document.source
        val position=record(id)?.positionStatus()?:if(value.optString("readingPositionContentHash").isBlank())"unresolved" else if(value.optString("readingPositionContentHash")==value.optString("contentHash"))"applicable" else "requires_relocation"
        return ArticleProvenanceReadState(source,position,true,dao.get(id)?.pending!=null)
    }
    suspend fun protected(id:String)=anyProtected() && dao.get(id)?.raw?.isNotEmpty()==true
    suspend fun requireExportable(id:String?=null) {
        if(if(id==null)anyProtected() else protected(id))throw CloudFormatUpgradeRequired("article_provenance_write_disabled")
    }
    suspend fun guardEdit(id:String,intent:JSONObject) {
        if(!anyProtected())return
        db.withTransaction {
            val row=dao.get(id)?.takeIf{it.raw.isNotEmpty()}?:return@withTransaction
            val pending=row.pending?.let(::JSONObject)?:JSONObject().put("intents",JSONArray())
            val intents=pending.optJSONArray("intents")?:JSONArray().also{pending.put("intents",it)}
            // A bounded draft is durable before reporting read-only; never silently discard old edits.
            if(intents.length()>=128 || pending.toString().toByteArray().size+intent.toString().toByteArray().size>=64500)
                throw CloudFormatUpgradeRequired("article_pending_full")
            intents.put(intent);dao.put(row.copy(pending=pending.toString()))
        }
        if(protected(id))throw CloudFormatUpgradeRequired("article_provenance_write_disabled")
    }
    /** Reader autosave has no error UI. Keep the latest absolute intent without throwing an upgrade from Compose. */
    suspend fun stageReadingProgress(id:String,progress:Double):Boolean {
        if(!anyProtected())return false
        return db.withTransaction {
            val row=dao.get(id)?.takeIf{it.raw.isNotEmpty()}?:return@withTransaction false
            val pending=row.pending?.let(::JSONObject)?:JSONObject()
            pending.put("latestReadingProgress",JSONObject().put("readingProgress",progress)
                .put("readingPositionContentHash",db.phoneArticleDao().getById(id)?.contentHash?:""))
            if(pending.toString().toByteArray().size>65520)throw CloudFormatUpgradeRequired("article_pending_full")
            dao.put(row.copy(pending=pending.toString()));true
        }
    }
    internal suspend fun accept(incoming:ArticleRegisterProvenance.Record,checkOwner:()->Unit={})=db.withTransaction {
        observedProtection=true
        checkOwner();val id=incoming.value.getString("articleId")
        val current=db.phoneArticleDao().getById(id)
        val prior=dao.get(id)?:ArticleProvenanceEntity(id,current?.sourceDeviceId?:"",
            current?.let{if(it.readingPositionChangedAt>0)it.readingPositionChangedAt else if(it.contentText.isNotBlank()||!it.contentHtml.isNullOrBlank()||it.isRead||it.readingProgress!=0f||it.readingPositionBytes!=0L||it.readingPositionContentHash.isNotEmpty())it.updatedAt else 0}?:0)
            .also{dao.seed(it)}
        val rawHash=CloudSnapshotCodec.sha256(incoming.original.source.toByteArray())
        require(dao.versionCount(id,rawHash)<64) { "article_versions_limit" }
        dao.preserve(ArticleProvenanceVersion(id,rawHash,incoming.original.source))
        val accepted=prior.raw.takeIf{it.isNotEmpty()}?.let{CloudRawDocument.parse(it)}?.takeIf(ArticleRegisterProvenance::marked)?.let{ArticleRegisterProvenance.decodeRetained(it)}
        // After seeding, legacy metadata source changes never reassign another register's provenance.
        val local=accepted ?: current?.let { legacy(it,prior) }
        val merged=try {if(local==null)incoming else ArticleRegisterProvenance.merge(local,incoming)}
        catch(e:CloudFormatUpgradeRequired) {
            if(e.reason!="article_provenance_extension_conflict")throw e
            val pending=prior.pending?.let(::JSONObject)?:JSONObject()
            pending.put("extensionConflict",true)
            dao.put(prior.copy(raw=local!!.original.source,pending=pending.toString()))
            return@withTransaction
        }
        var pending=prior.pending
        if(current!=null && accepted!=null && entityValue(current).toString()!=entityValue(project(accepted.value,current)).toString()) {
            val saved=pending?.let(::JSONObject)?:JSONObject()
            saved.put("legacyLocalDraft",entityValue(current));pending=saved.toString()
        }
        val value=merged.value
        val hash=value.getString("contentHash")
        if(current!=null && current.contentHash!=hash && (current.contentText.isNotEmpty()||!current.contentHtml.isNullOrEmpty()))
            dao.retainOriginal(id,System.currentTimeMillis())
        var next=project(value,current)
        if(current?.contentHash!=hash && dao.hasOriginal(id,hash)) {
            next=next.copy(contentText=chunks{dao.textChunk(id,hash,it)}?:"",contentHtml=chunks{dao.htmlChunk(id,hash,it)})
        }
        checkOwner();db.phoneArticleDao().upsert(next)
        dao.put(prior.copy(raw=merged.original.source,pending=pending))
        dao.pruneOriginals(id,hash,System.currentTimeMillis()-7*86_400_000L)
    }
    internal suspend fun protectUnknown(document:CloudRawDocument) = db.withTransaction {
        observedProtection=true
        val v=document.json().getJSONObject("value");val id=v.getString("articleId")
        val current=db.phoneArticleDao().getById(id)
        val seed=dao.get(id)?:ArticleProvenanceEntity(id,current?.sourceDeviceId?:v.optString("sourceDeviceId"),current?.readingPositionChangedAt?:0)
        val hash=CloudSnapshotCodec.sha256(document.source.toByteArray());require(dao.versionCount(id,hash)<64)
        dao.preserve(ArticleProvenanceVersion(id,hash,document.source))
        if(seed.raw.isNotEmpty() && ArticleRegisterProvenance.marked(CloudRawDocument.parse(seed.raw)))return@withTransaction
        dao.put(seed.copy(raw=document.source))
    }
    /** Authenticated old archives may fill only the selected body, never a register or a restore clock. */
    suspend fun materializeOriginal(body:PhoneArticleEntity):Boolean=db.withTransaction {
        val value=readOnlyValue(body.articleId)?:return@withTransaction false
        val current=db.phoneArticleDao().getById(body.articleId)?:return@withTransaction false
        if(body.contentText.isBlank()&&body.contentHtml.isNullOrBlank())return@withTransaction false
        dao.putOriginal(ArticleOriginalEntity(body.articleId,body.contentHash,body.contentText,body.contentHtml,System.currentTimeMillis()))
        dao.pruneOriginals(body.articleId,value.getString("contentHash"),System.currentTimeMillis()-7*86_400_000L)
        if(body.contentHash!=value.getString("contentHash"))return@withTransaction false
        val text=body.contentText.ifBlank{current.contentText};val html=body.contentHtml?.takeIf{it.isNotBlank()}?:current.contentHtml
        if(text==current.contentText && html==current.contentHtml)return@withTransaction false
        db.phoneArticleDao().upsert(current.copy(contentText=text,contentHtml=html,syncBodyHash="",syncBodyByteCount=0,syncChunkSize=0,syncChunkHashesJson="",syncMetadataHash=""));true
    }
    suspend fun originalReferences()=dao.originalReferences()
    private suspend fun chunks(read:suspend(Int)->String?):String? {
        val first=read(1)?:return null;val out=StringBuilder(first);var part=first
        var offset=first.codePointCount(0,first.length)
        while(part.codePointCount(0,part.length)==65536){part=read(offset+1)?:break;out.append(part);offset+=part.codePointCount(0,part.length)};return out.toString()
    }
    private fun legacy(row:PhoneArticleEntity,seed:ArticleProvenanceEntity):ArticleRegisterProvenance.Record {
        val v=entityValue(row).put("sourceDeviceId",seed.legacySource)
            .put("readingPositionChangedAt",if(row.readingPositionChangedAt>0)row.readingPositionChangedAt else seed.legacyReadingClock)
            .put("registerSources",JSONObject().also{out->ArticleRegisterProvenance.registers.forEach{
                out.put(it,if(it=="reading" && seed.legacyReadingClock==0L && row.readingPositionChangedAt==0L && row.contentText.isBlank() && row.contentHtml.isNullOrBlank())"" else seed.legacySource)
            }})
        return ArticleRegisterProvenance.decodeRetained(CloudRawDocument.parse(ArticleRegisterProvenance.canonical(ArticleRegisterProvenance.envelope(v))))
    }
    fun entityValue(row:PhoneArticleEntity):JSONObject=JSONObject().apply {
        put("articleId",row.articleId);put("sourceDeviceId",row.sourceDeviceId);put("url",row.url);put("title",row.title);put("siteName",row.siteName);put("excerpt",row.excerpt)
        put("imageUrl",row.imageUrl?:JSONObject.NULL);put("contentHash",row.contentHash);put("importedAt",row.importedAt);put("updatedAt",row.updatedAt)
        put("rssSourceUrl",row.rssSourceUrl?:JSONObject.NULL);put("rssSourceTitle",row.rssSourceTitle?:JSONObject.NULL)
        put("isRead",row.isRead);put("readingProgress",row.readingProgress.toDouble());put("readingPositionBytes",row.readingPositionBytes)
        put("readingPositionChangedAt",row.readingPositionChangedAt);put("readingPositionContentHash",row.readingPositionContentHash)
        put("independentSaved",row.independentSaved);put("independentChangedAt",row.independentChangedAt);put("independentSortOrder",row.independentSortOrder)
        put("favoriteSaved",row.favoriteSaved);put("favoriteChangedAt",row.favoriteChangedAt);put("favoriteSortOrder",row.favoriteSortOrder)
        put("watchLaterSaved",row.watchLaterSaved);put("watchLaterChangedAt",row.watchLaterChangedAt);put("watchLaterSortOrder",row.watchLaterSortOrder)
        put("deleted",row.deleted);put("deletedAt",row.deletedAt)
    }
    private fun project(v:JSONObject,old:PhoneArticleEntity?):PhoneArticleEntity {
        fun nullable(k:String)=if(v.isNull(k))null else v.getString(k)
        val same=old?.contentHash==v.getString("contentHash")
        return PhoneArticleEntity(articleId=v.getString("articleId"),sourceDeviceId=v.getString("sourceDeviceId"),url=v.getString("url"),title=v.getString("title"),siteName=v.getString("siteName"),excerpt=v.getString("excerpt"),
            contentHtml=old?.contentHtml?.takeIf{same},contentText=old?.contentText?.takeIf{same}?:"",imageUrl=nullable("imageUrl"),contentHash=v.getString("contentHash"),importedAt=v.getLong("importedAt"),updatedAt=v.getLong("updatedAt"),
            independentSaved=v.getBoolean("independentSaved"),independentChangedAt=v.getLong("independentChangedAt"),independentSortOrder=v.getLong("independentSortOrder"),rssSourceUrl=nullable("rssSourceUrl"),rssSourceTitle=nullable("rssSourceTitle"),
            favoriteSaved=v.getBoolean("favoriteSaved"),favoriteChangedAt=v.getLong("favoriteChangedAt"),favoriteSortOrder=v.getLong("favoriteSortOrder"),watchLaterSaved=v.getBoolean("watchLaterSaved"),watchLaterChangedAt=v.getLong("watchLaterChangedAt"),watchLaterSortOrder=v.getLong("watchLaterSortOrder"),
            deleted=v.getBoolean("deleted"),deletedAt=v.getLong("deletedAt"),readingProgress=v.getDouble("readingProgress").toFloat(),isRead=v.getBoolean("isRead"),readingPositionBytes=v.getLong("readingPositionBytes"),readingPositionContentHash=v.getString("readingPositionContentHash"),readingPositionChangedAt=v.getLong("readingPositionChangedAt"),
            syncBodyHash=if(same)old!!.syncBodyHash else "",syncBodyByteCount=if(same)old!!.syncBodyByteCount else 0,syncChunkSize=if(same)old!!.syncChunkSize else 0,syncChunkHashesJson=if(same)old!!.syncChunkHashesJson else "")
    }
}
