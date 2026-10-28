package com.lightningstudio.watchrss.phone.data.db

import androidx.room.*

/** Business witnesses, never synchronization caches. raw is the merged reader projection, not a write permission. */
@Entity(tableName="article_register_provenance")
data class ArticleProvenanceEntity(@PrimaryKey val articleId:String,val legacySource:String,val legacyReadingClock:Long,
    val raw:String="",val pending:String?=null)
@Entity(tableName="article_register_originals",primaryKeys=["articleId","contentHash"])
data class ArticleOriginalEntity(val articleId:String,val contentHash:String,val contentText:String,val contentHtml:String?,val retainedAt:Long)
@Entity(tableName="article_register_versions",primaryKeys=["articleId","sha256"])
data class ArticleProvenanceVersion(val articleId:String,val sha256:String,val raw:String)

@Dao
interface ArticleProvenanceDao {
    @Query("DELETE FROM article_register_provenance") suspend fun clearWitnesses()
    @Query("DELETE FROM article_register_originals") suspend fun clearOriginals()
    @Query("DELETE FROM article_register_versions") suspend fun clearVersions()
    @Query("SELECT * FROM article_register_provenance WHERE articleId=:id") suspend fun get(id:String):ArticleProvenanceEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun seed(row:ArticleProvenanceEntity)
    @Upsert suspend fun put(row:ArticleProvenanceEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun preserve(row:ArticleProvenanceVersion)
    @Query("SELECT * FROM article_register_versions WHERE articleId=:id ORDER BY sha256") suspend fun versions(id:String):List<ArticleProvenanceVersion>
    @Query("SELECT COUNT(*) FROM article_register_versions WHERE articleId=:id AND sha256!=:hash") suspend fun versionCount(id:String,hash:String):Int
    @Query("SELECT COUNT(*) FROM article_register_provenance WHERE pending IS NOT NULL") suspend fun pendingCount():Int
    @Query("SELECT COUNT(*) FROM article_register_provenance WHERE pending IS NOT NULL") fun observePendingCount():kotlinx.coroutines.flow.Flow<Int>
    @Query("SELECT EXISTS(SELECT 1 FROM article_register_provenance WHERE raw!='')") suspend fun anyProtected():Boolean
    @Query("INSERT OR IGNORE INTO article_register_originals(articleId,contentHash,contentText,contentHtml,retainedAt) SELECT articleId,contentHash,contentText,contentHtml,:now FROM phone_articles WHERE articleId=:id")
    suspend fun retainOriginal(id:String,now:Long)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putOriginal(row:ArticleOriginalEntity)
    @Query("SELECT substr(contentText,:start,65536) FROM article_register_originals WHERE articleId=:id AND contentHash=:hash") suspend fun textChunk(id:String,hash:String,start:Int):String?
    @Query("SELECT substr(contentHtml,:start,65536) FROM article_register_originals WHERE articleId=:id AND contentHash=:hash") suspend fun htmlChunk(id:String,hash:String,start:Int):String?
    @Query("SELECT EXISTS(SELECT 1 FROM article_register_originals WHERE articleId=:id AND contentHash=:hash)") suspend fun hasOriginal(id:String,hash:String):Boolean
    @Query("DELETE FROM article_register_originals WHERE articleId=:id AND retainedAt<:before AND contentHash!=:current AND contentHash NOT IN (SELECT contentHash FROM article_register_originals WHERE articleId=:id ORDER BY retainedAt DESC LIMIT 2)")
    suspend fun pruneOriginals(id:String,current:String,before:Long)
    @Query("SELECT contentText AS value FROM article_register_originals WHERE length(contentText)<256 UNION SELECT contentHtml AS value FROM article_register_originals WHERE contentHtml IS NOT NULL AND length(contentHtml)<256")
    suspend fun originalReferences():List<String>
}
