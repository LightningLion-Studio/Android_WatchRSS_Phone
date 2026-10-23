package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds

/** One immutable identity projection per negotiated library exchange. Never changes content identity. */
internal class BluetoothArticleIdentityProjection(
    private val rows:List<PhoneArticleEntity>,private val aliases:Map<String,String>,private val canonical:Boolean
) {
    private val byId=rows.associateBy { it.articleId }
    private val publicRows=rows.filter { !it.rssSourceUrl.isNullOrBlank() && !ImportedContentIds.isImportedContentUrl(it.rssSourceUrl) &&
        !ImportedContentIds.isImportedContentUrl(it.url) }
    private val unambiguousLegacy=publicRows.groupBy { WebArticleImporter.stableArticleId(it.url) }
        .filterValues { it.size==1 }.mapValues { it.value.single().articleId }
    fun local(id:String):String=aliases[id]?.takeIf { it in byId } ?: unambiguousLegacy[id] ?: id
    fun wire(id:String):String {
        val row=byId[local(id)]?:return id
        return if(!canonical && unambiguousLegacy[WebArticleImporter.stableArticleId(row.url)]==row.articleId) WebArticleImporter.stableArticleId(row.url)else row.articleId
    }
    fun outgoing(article:PhoneArticleEntity):PhoneArticleEntity {
        val id=wire(article.articleId)
        if(id==article.articleId)return article
        val projected=article.copy(articleId=id)
        return projected.copy(syncMetadataHash=ArticleSyncBody.metadataHashFor(projected))
    }
    fun incoming(article:PhoneArticleEntity):PhoneArticleEntity {
        val id=local(article.articleId)
        return if(id==article.articleId)article else article.copy(articleId=id,syncMetadataHash="")
    }
    private fun legacyIds(row:PhoneArticleEntity?,id:String):List<String> =
        if(canonical && row in publicRows) ((aliases.filterValues { it==row?.articleId }.keys)+listOfNotNull(row?.let { WebArticleImporter.stableArticleId(it.url).takeIf { old -> unambiguousLegacy[old]==it.articleId } })).filter { it!=id }.distinct() else emptyList()

    /** Used before batching, so alias bytes count toward the frame limit. */
    fun outgoingAliases(articles:List<PhoneArticleEntity>):Map<String,List<String>> = articles.associate {
        it.articleId to legacyIds(byId[local(it.articleId)],it.articleId)
    }.filterValues { it.isNotEmpty() }

    fun manifest(entry:ArticleSyncManifestEntry):ArticleSyncManifestEntry {
        val id=wire(entry.articleId);val row=byId[local(entry.articleId)]
        return entry.copy(articleId=id,metadataHash=if(id!=entry.articleId && row!=null)ArticleSyncBody.metadataHashFor(outgoing(row))else entry.metadataHash,
            legacyArticleIds=legacyIds(row,id))
    }
    fun localManifest(entry:ArticleSyncManifestEntry)=entry.copy(articleId=local(entry.articleId))
    fun request(request:ArticleBodyRequest)=request.copy(articleId=wire(request.articleId))
    fun incoming(payload:ChunkedArticlePayload)=payload.copy(article=incoming(payload.article))
}
