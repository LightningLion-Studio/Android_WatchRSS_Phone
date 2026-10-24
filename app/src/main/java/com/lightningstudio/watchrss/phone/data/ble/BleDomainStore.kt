package com.lightningstudio.watchrss.phone.data.ble

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.connection.bluetooth.ArticleSyncManifestEntry
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Technical tables, deliberately independent of cloud cursors. Business triggers share the writer's transaction. */
class BleDomainStore(
    private val database:PhoneCompanionDatabase, private val files:File,
    private val encoderVersions:Map<String,Int> = DOMAINS.associateWith { if (it == "library") 3 else 1 },
    private val now:()->Long = System::currentTimeMillis,
    private val nativeProtocolVersion:Int = LibrarySyncPayload.PROTOCOL_VERSION
) {
    data class Projection(val encoder:Int,val schema:Int,val definition:String) {
        fun json()=JSONObject().put("encoder",encoder).put("schema",schema).put("definition",definition)
        fun text(domain:String)="watchrss.ble.projection.v1\n$domain\n$encoder\n$schema\n$definition"
    }
    private var schemaCookie=-1
    private var userVersion=-1
    private var projections:Map<String,Projection> = emptyMap()
    internal var inventoryTraversals=0; private set
    private val db get()=database.openHelper.writableDatabase
    @Volatile private var initialized=false
    companion object {
        val DOMAINS=listOf("library","books","bookReading","reader","notes","account","usage")
        const val PROFILE="rss-canonical-books-reading-v1:merge:domain-v2"
        const val RECEIPT_MAX_AGE_MS=604_800_000L
        fun receiptFresh(verifiedAt:Long,time:Long)=verifiedAt>0 && verifiedAt<=time && time-verifiedAt<RECEIPT_MAX_AGE_MS
        fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        fun versionRoot(domain:String,epoch:String,revision:Long,projection:Projection):String {
            require(domain in DOMAINS && epoch.matches(Regex("[0-9a-f]{32}")) && revision in 0..9_007_199_254_740_991L)
            require(projection.encoder>0 && projection.schema>=0 && projection.definition.matches(Regex("[0-9a-f]{64}")))
            return hash("watchrss.ble.domain-version.v2\n$domain\n${projection.encoder}\n${projection.schema}\n${projection.definition}\n$epoch\n$revision")
        }
        private fun eligible(alias:String)=listOf("independentSaved","favoriteSaved","watchLaterSaved","deleted").joinToString(" OR "){"$alias.$it != 0"}+" OR "+
            listOf("independentChangedAt","favoriteChangedAt","watchLaterChangedAt").joinToString(" OR "){"$alias.$it > 0"}+" OR "+
            listOf("url","rssSourceUrl").flatMap { column->listOf("import-content","import-epub","import-txt-novel").map{"lower(trim($alias.$column)) LIKE 'https://watchrss.local/$it%'"}}.joinToString(" OR ")
    }
    @Synchronized fun initialize() {
        val cookie=db.query("PRAGMA schema_version").use { it.moveToFirst();it.getInt(0) }
        if(initialized && cookie==schemaCookie && userVersion==db.version)return
        var installedCookie=-1
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_versions(domain TEXT PRIMARY KEY NOT NULL,epoch TEXT NOT NULL,revision INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_receipts(scope TEXT NOT NULL,peer TEXT NOT NULL,domain TEXT NOT NULL,localRoot TEXT NOT NULL,remoteRoot TEXT NOT NULL,PRIMARY KEY(scope,peer,domain))")
            val receiptColumns=db.query("PRAGMA table_info(ble_fast_receipts)").use { c->buildList { while(c.moveToNext())add(c.getString(1)) } }
            if("verifiedAt" !in receiptColumns)db.execSQL("ALTER TABLE ble_fast_receipts ADD COLUMN verifiedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_definitions(domain TEXT PRIMARY KEY NOT NULL,definition TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_library_baselines(peer TEXT PRIMARY KEY NOT NULL,definition TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_clock(id INTEGER PRIMARY KEY,tick INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO ble_fast_clock VALUES(1,0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_dirty(articleId TEXT PRIMARY KEY NOT NULL,seq INTEGER NOT NULL,bodyDirty INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_schema(id INTEGER PRIMARY KEY,version INTEGER NOT NULL)")
            val schema=db.query("SELECT version FROM ble_fast_schema WHERE id=1").use{if(it.moveToFirst())it.getInt(0) else 0}
            if(schema!=2) {
                val dirtyColumns=db.query("PRAGMA table_info(ble_fast_dirty)").use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
                if("bodyDirty" !in dirtyColumns)db.execSQL("ALTER TABLE ble_fast_dirty ADD COLUMN bodyDirty INTEGER NOT NULL DEFAULT 1")
                val triggers=db.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name GLOB 'ble_fast_*'").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
                triggers.forEach{db.execSQL("DROP TRIGGER IF EXISTS \"$it\"")}
                db.execSQL("INSERT OR REPLACE INTO ble_fast_schema VALUES(1,2)")
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_manifest(articleId TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_resources(path TEXT PRIMARY KEY NOT NULL,stamp TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_manifest_resources(articleId TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(articleId,path))")
            db.execSQL("CREATE TABLE IF NOT EXISTS ble_fast_identity(articleId TEXT PRIMARY KEY NOT NULL,url TEXT NOT NULL,rssSourceUrl TEXT,legacyId TEXT NOT NULL DEFAULT '')")
            val triggerSql=DOMAINS.associateWith { mutableListOf<String>() }
            val tables=DOMAINS.associateWith { linkedSetOf<String>() }
            for(domain in DOMAINS)db.execSQL("INSERT OR IGNORE INTO ble_fast_versions(domain,epoch,revision) VALUES(?,?,0)",arrayOf(domain,UUID.randomUUID().toString().replace("-","")))
            fun bump(domain:String)="UPDATE ble_fast_versions SET epoch=CASE WHEN revision>=9007199254740991 THEN lower(hex(randomblob(16))) ELSE epoch END,revision=CASE WHEN revision>=9007199254740991 THEN 0 ELSE revision+1 END WHERE domain='$domain';"
            fun fields(table:String)=db.query("PRAGMA table_info($table)").use { c->buildList{while(c.moveToNext())add(c.getString(1))} }
            fun changed(columns:List<String>)=columns.joinToString(" OR "){"OLD.\"$it\" IS NOT NEW.\"$it\""}
            fun trigger(table:String,domain:String,columns:List<String> = fields(table),guard:(String)->String={"1"},extra:(String)->String={""},extraUpdate:((String)->String)?=null) {
                tables.getValue(domain).add(table)
                for(op in listOf("INSERT","UPDATE","DELETE")) {
                    val alias=if(op=="DELETE")"OLD" else "NEW"
                    val condition=if(op=="UPDATE")"(${changed(columns)}) AND ((${guard("OLD")}) OR (${guard("NEW")}))" else guard(alias)
                    triggerSql.getValue(domain).add("CREATE TRIGGER IF NOT EXISTS ble_fast_${table}_${domain}_$op AFTER $op ON $table WHEN $condition BEGIN ${bump(domain)}${if(op=="UPDATE"&&extraUpdate!=null)extraUpdate(alias) else extra(alias)} END")
                }
            }
            val articleFields=fields("phone_articles").filterNot { it in setOf("syncBodyHash","syncBodyByteCount","syncChunkSize","syncChunkHashesJson","syncMetadataHash") }
            val knownArticleFields=setOf("articleId","sourceDeviceId","url","title","siteName","excerpt","contentHtml","contentText","imageUrl","contentHash","importedAt","updatedAt","independentSaved","independentChangedAt","independentSortOrder","rssSourceUrl","rssSourceTitle","favoriteSaved","favoriteChangedAt","favoriteSortOrder","watchLaterSaved","watchLaterChangedAt","watchLaterSortOrder","deleted","deletedAt","syncBodyHash","syncBodyByteCount","syncChunkSize","syncChunkHashesJson","syncMetadataHash","readingProgress","isRead","readingPositionBytes","readingPositionContentHash","readingPositionChangedAt")
            val futureArticleFields=articleFields.filterNot { it in knownArticleFields }
            fun queue(alias:String,body:String="1")="UPDATE ble_fast_clock SET tick=tick+1 WHERE id=1; INSERT INTO ble_fast_dirty(articleId,seq,bodyDirty) SELECT $alias.articleId,tick,$body FROM ble_fast_clock WHERE id=1 ON CONFLICT(articleId) DO UPDATE SET seq=excluded.seq,bodyDirty=MAX(ble_fast_dirty.bodyDirty,excluded.bodyDirty);"
            trigger("phone_articles","library",articleFields,{a->"(${eligible(a)}) AND NOT EXISTS(SELECT 1 FROM imported_book_chapters WHERE articleId=$a.articleId)"},
                extra={queue(it)},extraUpdate={queue(it,"CASE WHEN OLD.contentText IS NOT NEW.contentText OR OLD.contentHtml IS NOT NEW.contentHtml OR OLD.contentHash IS NOT NEW.contentHash THEN 1 ELSE 0 END")})
            // Identity cache stores only identity, never body prefixes for every RSS row.
            for(op in listOf("INSERT","UPDATE","DELETE")) {
                val body=if(op=="DELETE")"DELETE FROM ble_fast_identity WHERE articleId=OLD.articleId;" else "INSERT OR REPLACE INTO ble_fast_identity(articleId,url,rssSourceUrl) VALUES(NEW.articleId,NEW.url,NEW.rssSourceUrl);"
                val whenClause=if(op=="UPDATE")"WHEN OLD.url IS NOT NEW.url OR OLD.rssSourceUrl IS NOT NEW.rssSourceUrl OR OLD.articleId IS NOT NEW.articleId" else ""
                val urls=if(op=="UPDATE")"OLD.url,NEW.url" else if(op=="DELETE")"OLD.url" else "NEW.url"
                val projectionChanged=bump("library").removeSuffix(";")+" AND EXISTS(SELECT 1 FROM phone_articles a WHERE a.url IN ($urls) AND (${eligible("a")}) AND NOT EXISTS(SELECT 1 FROM imported_book_chapters b WHERE b.articleId=a.articleId));"
                triggerSql.getValue("library").add("CREATE TRIGGER IF NOT EXISTS ble_fast_identity_$op AFTER $op ON phone_articles $whenClause BEGIN $body $projectionChanged END")
            }
            trigger("phone_rss_sources","library",guard={a->"NOT EXISTS(SELECT 1 FROM imported_books WHERE json_extract(stateJson,'$.sourceUrl')=$a.url) AND ($a.url != 'https://watchrss.local/import-content' OR EXISTS(SELECT 1 FROM phone_articles p WHERE p.rssSourceUrl=$a.url AND NOT EXISTS(SELECT 1 FROM imported_book_chapters b WHERE b.articleId=p.articleId)))"})
            trigger("phone_rss_sources","books",guard={a->"EXISTS(SELECT 1 FROM imported_books WHERE json_extract(stateJson,'$.sourceUrl')=$a.url) OR EXISTS(SELECT 1 FROM imported_book_chapters b JOIN phone_articles p ON p.articleId=b.articleId WHERE p.rssSourceUrl=$a.url)"})
            trigger("imported_books","books")
            trigger("imported_book_reading","bookReading")
            trigger("phone_articles","bookReading",listOf("sourceDeviceId","readingProgress","isRead","readingPositionBytes","readingPositionContentHash","readingPositionChangedAt","favoriteSaved","favoriteChangedAt","favoriteSortOrder","watchLaterSaved","watchLaterChangedAt","watchLaterSortOrder","deleted","deletedAt")+futureArticleFields,{a->"EXISTS(SELECT 1 FROM imported_book_chapters WHERE articleId=$a.articleId)"},
                extra={a->"INSERT INTO book_reading_dirty(articleId,revision) VALUES($a.articleId,1) ON CONFLICT(articleId) DO UPDATE SET revision=revision+1;"})
            trigger("imported_book_chapters","library",extra={a->queue(a)+"DELETE FROM ble_fast_manifest WHERE articleId=$a.articleId;"})
            trigger("imported_book_chapters","books")
            trigger("imported_book_chapters","bookReading")
            trigger("phone_articles","books",listOf("deleted","deletedAt")+futureArticleFields,{a->"EXISTS(SELECT 1 FROM imported_book_chapters WHERE articleId=$a.articleId)"})
            for(table in listOf("reader_presets","reader_font_assets","reader_background_assets","reader_deletions"))trigger(table,"reader")
            for(table in listOf("notes","note_folders","note_assets"))trigger(table,"notes")
            trigger("llm_token_usage","usage")
            // Alias writes change the wire projection. Other app_meta/ACK/cache writes are technical.
            trigger("app_meta","library",guard={a->"$a.key LIKE 'rss_identity_alias:%'"})
            tables.getValue("books").add("book_capture_meta")
            tables.getValue("bookReading").add("book_capture_meta")
            tables.getValue("library").addAll(listOf("ble_fast_manifest","ble_fast_identity"))
            fun normalized(sql:String)=sql.replace("CREATE TRIGGER IF NOT EXISTS ","CREATE TRIGGER ").trim()
            val installed=db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name GLOB 'ble_fast_*'").use { c->buildMap {while(c.moveToNext())put(c.getString(0),c.getString(1))} }
            val changes=linkedSetOf<String>()
            val next=DOMAINS.associateWith { domain->
                val encoder=requireNotNull(encoderVersions[domain]);require(encoder>0)
                val structure=tables.getValue(domain).sorted().joinToString("\n") { table->
                    val sql=db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use {if(it.moveToFirst())it.getString(0) else "missing"}
                    val info=db.query("PRAGMA table_info($table)").use { c->buildList {while(c.moveToNext())add((0 until c.columnCount).joinToString("|") { if(c.isNull(it))"<null>" else c.getString(it) })}.joinToString("\n") }
                    "$table:$sql\n$info"
                }
                val definition=hash("watchrss.ble.definition.v2\n$domain\n$encoder\n${db.version}\n$nativeProtocolVersion\n$structure\n"+triggerSql.getValue(domain).joinToString("\n"))
                val old=db.query("SELECT definition FROM ble_fast_definitions WHERE domain=?",arrayOf(domain)).use {if(it.moveToFirst())it.getString(0) else null}
                val intact=triggerSql.getValue(domain).all { sql->val name=sql.substringAfter("IF NOT EXISTS ").substringBefore(' ');installed[name]?.let { normalized(it)==normalized(sql) }==true }
                if(old!=definition || !intact)changes+=domain
                Projection(encoder,db.version,definition)
            }
            if(changes.isNotEmpty()) {
                // Recreate only our technical triggers atomically; unchanged domains retain epochs/ACKs.
                installed.keys.forEach { db.execSQL("DROP TRIGGER IF EXISTS \"$it\"") }
                triggerSql.values.flatten().forEach(db::execSQL)
                for(domain in changes) {
                    db.execSQL("UPDATE ble_fast_versions SET epoch=lower(hex(randomblob(16))),revision=0 WHERE domain=?",arrayOf(domain))
                    db.execSQL("DELETE FROM ble_fast_receipts WHERE domain=?",arrayOf(domain))
                }
                if("bookReading" in changes) {
                    // Preserve imported_book_reading business states; discard only R46 derived hashes/history.
                    for(table in listOf("book_reading_meta","book_reading_versions","book_reading_journal","book_reading_roots","book_reading_hash_dirty","book_reading_dirty","book_reading_peers"))db.execSQL("DELETE FROM $table")
                }
                if("books" in changes)db.execSQL("UPDATE book_capture_meta SET checkedRevision=-1")
                if("library" in changes) {
                    for(table in listOf("ble_fast_manifest","ble_fast_dirty","ble_fast_manifest_resources","ble_fast_resources","ble_fast_identity","ble_fast_library_baselines"))db.execSQL("DELETE FROM $table")
                    db.execSQL("DELETE FROM app_meta WHERE key='ble_repair_watermark_v2' OR key LIKE 'ble_echo_v2:%'")
                    db.execSQL("UPDATE phone_articles SET syncBodyHash='',syncBodyByteCount=0,syncChunkSize=0,syncChunkHashesJson='',syncMetadataHash=''")
                    db.execSQL("INSERT OR REPLACE INTO ble_fast_identity(articleId,url,rssSourceUrl) SELECT articleId,url,rssSourceUrl FROM phone_articles")
                    db.execSQL("INSERT OR REPLACE INTO ble_fast_dirty(articleId,seq) SELECT a.articleId,0 FROM phone_articles a WHERE (${eligible("a")}) AND NOT EXISTS(SELECT 1 FROM imported_book_chapters b WHERE b.articleId=a.articleId)")
                }
            }
            next.forEach { (domain,p)->db.execSQL("INSERT INTO ble_fast_definitions(domain,definition) VALUES(?,?) ON CONFLICT(domain) DO UPDATE SET definition=excluded.definition WHERE definition IS NOT excluded.definition",arrayOf(domain,p.definition)) }
            projections=next
            installedCookie=db.query("PRAGMA schema_version").use { it.moveToFirst();it.getInt(0) }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        schemaCookie=installedCookie
        userVersion=projections.values.first().schema
        initialized=true
    }
    internal data class FileInventory(val digest:String?,val guards:Map<File,String>)
    private fun stamp(file:File):String = if(!file.exists()) "missing" else
        java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java).let { "${it.isDirectory}:${it.size()}:${it.lastModifiedTime()}:${it.fileKey()}" }
    internal val inventoryTraversalsByDomain=mutableMapOf<String,Int>()
    private fun external(domain:String,cache:MutableMap<String,FileInventory>?=null):String? {
        val folders=when(domain){"library"->listOf("imported_text");"books"->listOf("imported_books");"reader"->listOf("reader_assets");"notes"->listOf("notes/assets");else->return ""}
        cache?.get(domain)?.let {return it.digest}
        inventoryTraversals++;inventoryTraversalsByDomain[domain]=(inventoryTraversalsByDomain[domain]?:0)+1
        val guards=linkedMapOf<File,String>()
        val result=try {
            val digest=MessageDigest.getInstance("SHA-256")
            val observed=linkedMapOf<String,String>()
            for(folder in folders) {
                val root=File(files,folder);guards[root]=stamp(root)
                digest.update("$folder:${root.isDirectory}\n".toByteArray())
                if(root.isDirectory) {
                    val entries=root.walkTopDown().toList()
                    entries.filter {it.isDirectory}.forEach {guards[it]=stamp(it)}
                    entries.filter{it.isFile&&!it.name.startsWith(".")&&!it.name.endsWith(".part")}.sortedBy{it.relativeTo(root).path}.forEach {
                        val path=it.relativeTo(root).path;val value=stamp(it)
                        guards[it]=value;observed[path]=value;digest.update("$path:$value\n".toByteArray())
                    }
                }
            }
            if(domain=="library")observeLibraryFiles(observed)
            digest.digest().joinToString(""){"%02x".format(it)}
        }catch(_:java.io.IOException){null}
        cache?.put(domain,FileInventory(result,guards))
        return result
    }
    /** Frozen roots are computed once. Later checks can only remove proof eligibility. */
    inner class RootWindow internal constructor(accountWitness:String?) {
        private val inventories=linkedMapOf<String,FileInventory>()
        val initial:Map<String,String> = buildRoots(accountWitness,inventories)
        val projectionVersions:Map<String,Projection> = projections.toMap()
        private val tokens=guardTokens(accountWitness)
        private val invalid=linkedSetOf<String>()
        fun current(accountWitness:String?):Map<String,String> {
            initialize()
            val current=guardTokens(accountWitness)
            for((domain,inventory)in inventories) {
                val valid=inventory.digest!=null && try { inventory.guards.all {(file,value)->stamp(file)==value} }catch(_:java.io.IOException){false}
                if(!valid) {invalid+=domain;if(domain=="library")invalid+="books"}
            }
            return initial.filter {(domain,_)->domain !in invalid && tokens[domain]==current[domain]}
        }
        internal fun resourceGuardsValid():Boolean {
            val inventory=inventories["library"]?:return false
            return inventory.digest!=null && try { inventory.guards.all{(file,value)->stamp(file)==value} }catch(_:java.io.IOException){false}
        }
    }
    private fun guardTokens(accountWitness:String?):Map<String,String> {
        val registration=db.query("SELECT registrationRevision FROM book_capture_meta WHERE id=1").use{if(it.moveToFirst())it.getLong(0).toString()else "missing"}
        val pending=pendingBookPaths().joinToString("\n")
        return db.query("SELECT domain,epoch,revision FROM ble_fast_versions").use { c->buildMap {while(c.moveToNext()) {
            val d=c.getString(0);put(d,"${c.getString(1)}:${c.getLong(2)}:${projections[d]}:"+when(d){"books"->"$registration:$pending";"bookReading"->registration;"account"->accountWitness.orEmpty();else->""})
        }} }
    }
    fun beginRootWindow(accountWitness:String?):RootWindow { initialize();return RootWindow(accountWitness) }
    private fun observeLibraryFiles(observed:Map<String,String>) {
        db.beginTransaction()
        try {
            val previous=db.query("SELECT path,stamp FROM ble_fast_resources").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
            for(path in (previous.keys+observed.keys).filter{previous[it]!=observed[it]}) {
                db.execSQL("UPDATE ble_fast_clock SET tick=tick+1 WHERE id=1")
                db.execSQL("INSERT OR REPLACE INTO ble_fast_dirty(articleId,seq) SELECT articleId,(SELECT tick FROM ble_fast_clock WHERE id=1) FROM ble_fast_manifest_resources WHERE path=?",arrayOf(path))
                val stamp=observed[path]
                if(stamp==null)db.execSQL("DELETE FROM ble_fast_resources WHERE path=?",arrayOf(path))
                else db.execSQL("INSERT OR REPLACE INTO ble_fast_resources(path,stamp) VALUES(?,?)",arrayOf(path,stamp))
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    private fun pendingBookPaths():List<String> = db.query("""SELECT DISTINCT r.path FROM ble_fast_manifest_resources r JOIN ble_fast_identity i ON i.articleId=r.articleId
            WHERE (lower(trim(i.url)) LIKE 'https://watchrss.local/import-%' OR lower(trim(i.rssSourceUrl)) LIKE 'https://watchrss.local/import-%')
            AND NOT EXISTS(SELECT 1 FROM imported_book_chapters b WHERE b.articleId=r.articleId) ORDER BY r.path""").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun unregisteredBookFiles():String? = try {
        hash(pendingBookPaths().joinToString("\n") {path-> "$path:${stamp(File(files,"imported_text/$path"))}" })
    }catch(_:java.io.IOException){null}
    private fun readerFilesPresent():Boolean {
        fun present(dir:String,name:String,bytes:Long)=name.isNotBlank()&&File(name).name==name&&File(files,"$dir/$name").let{it.isFile&&it.length()==bytes}
        db.query("SELECT fileName,byteCount FROM reader_font_assets WHERE deleted=0").use{c->while(c.moveToNext())if(!present("reader_assets/fonts",c.getString(0),c.getLong(1)))return false}
        db.query("SELECT masterFileName,byteCount,variantsJson FROM reader_background_assets WHERE deleted=0").use{c->while(c.moveToNext()) {
            if(!present("reader_assets/backgrounds",c.getString(0),c.getLong(1)))return false
            val variants=runCatching{JSONObject(c.getString(2))}.getOrNull()?:return false
            for(key in listOf("watch","watchPoster"))variants.optJSONObject(key)?.let{if(!present("reader_assets/variants",it.optString("fileName"),it.optLong("byteCount",-1)))return false}
        }}
        return true
    }
    fun roots(accountWitness:String?):Map<String,String> = buildRoots(accountWitness,null)
    private fun buildRoots(accountWitness:String?,inventories:MutableMap<String,FileInventory>?):Map<String,String> {
        initialize()
        val rows=db.query("SELECT domain,epoch,revision FROM ble_fast_versions").use{c->buildList{while(c.moveToNext())add(Triple(c.getString(0),c.getString(1),c.getLong(2)))}}
        val corrupt=rows.filter{(domain,epoch,revision)->domain in DOMAINS&&(!epoch.matches(Regex("[0-9a-f]{32}"))||revision !in 0..9_007_199_254_740_991L)}.map{it.first}.toSet()
        if(corrupt.isNotEmpty()) {
            db.beginTransaction();try {
                // Once a counter is untrustworthy, a later writer must not bring it back to an
                // old certified number in the same epoch. Rotate even if that writer just ran.
                for(domain in corrupt) {
                    db.execSQL("UPDATE ble_fast_versions SET epoch=lower(hex(randomblob(16))),revision=0 WHERE domain=?",arrayOf(domain))
                    db.execSQL("DELETE FROM ble_fast_receipts WHERE domain=?",arrayOf(domain))
                }
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
        }
        val registration=db.query("SELECT registrationRevision FROM book_capture_meta WHERE id=1").use{if(it.moveToFirst())it.getLong(0) else null}
        return buildMap {for((domain,epoch,revision) in rows) {
            if(domain !in DOMAINS || !epoch.matches(Regex("[0-9a-f]{32}")) || revision !in 0..9_007_199_254_740_991L)continue
            if(registration==null && domain in setOf("books","bookReading"))continue
            val witness=when(domain){
                "account"->accountWitness
                "books"->{val packages=external(domain,inventories);val pending=unregisteredBookFiles();if(packages==null||pending==null)null else "$packages:$registration:$pending"}
                "bookReading"->registration.toString()
                "reader"->if(readerFilesPresent())external(domain,inventories) else null
                else->external(domain,inventories)
            }
            if(witness!=null)put(domain,versionRoot(domain,hash("$epoch\n$witness").take(32),revision,projections.getValue(domain)))
        }}
    }
    fun scope(backend:String,user:String?)=hash("$backend\n${user.orEmpty()}\n$PROFILE")
    fun receipts(scope:String,peer:String):Map<String,Pair<String,String>> {
        initialize();return db.query("SELECT domain,localRoot,remoteRoot,verifiedAt FROM ble_fast_receipts WHERE scope=? AND peer=?",arrayOf(scope,peer)).use{c->buildMap{while(c.moveToNext()) {
            val domain=c.getString(0);val local=c.getString(1);val remote=c.getString(2)
            val verified=c.getLong(3);val time=now()
            if(receiptFresh(verified,time) && domain in DOMAINS && local.matches(Regex("[0-9a-f]{64}")) && remote.matches(Regex("[0-9a-f]{64}")))put(domain,local to remote)
        }}}
    }
    fun publish(scope:String,peer:String,proofs:Map<String,Pair<String,String>>,refreshDomains:Set<String> = proofs.keys) {
        initialize();db.beginTransaction();try {
            for((domain,pair)in proofs) {
                require(domain in DOMAINS)
                val old=db.query("SELECT localRoot,remoteRoot,verifiedAt FROM ble_fast_receipts WHERE scope=? AND peer=? AND domain=?",arrayOf(scope,peer,domain)).use { if(it.moveToFirst())Triple(it.getString(0),it.getString(1),it.getLong(2))else null }
                val time=if(domain in refreshDomains)now() else old?.takeIf { it.first==pair.first && it.second==pair.second }?.third ?: continue
                if(old!=null && old.first==pair.first && old.second==pair.second && old.third==time)continue
                db.execSQL("INSERT OR REPLACE INTO ble_fast_receipts(scope,peer,domain,localRoot,remoteRoot,verifiedAt) VALUES(?,?,?,?,?,?)",arrayOf<Any>(scope,peer,domain,pair.first,pair.second,time))
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun projectionVersions():Map<String,Projection> {initialize();return projections.toMap()}
    private fun libraryDefinitionLocked():String {
        val definition=db.query("SELECT definition FROM ble_fast_definitions WHERE domain='library'").use {it.moveToFirst();it.getString(0)}
        val epoch=db.query("SELECT epoch FROM ble_fast_versions WHERE domain='library'").use {it.moveToFirst();it.getString(0)}
        return hash("watchrss.ble.library-cache.v1\n$definition\n$epoch")
    }
    fun libraryDefinition():String {initialize();return libraryDefinitionLocked()}
    fun libraryBaselineMatches(peer:String):Boolean {
        val current=libraryDefinition()
        return db.query("SELECT definition FROM ble_fast_library_baselines WHERE peer=?",arrayOf(peer)).use { it.moveToFirst() && it.getString(0)==current }
    }
    fun acknowledgeLibraryDefinition(peer:String,definition:String) {
        if(libraryDefinition()==definition)db.execSQL("INSERT OR REPLACE INTO ble_fast_library_baselines(peer,definition) VALUES(?,?)",arrayOf(peer,definition))
    }
    internal fun refreshBodyInventory(ids:Collection<String>,window:RootWindow?=null) {
        initialize()
        if(if(window==null)external("library")!=null else window.resourceGuardsValid())return
        db.beginTransaction();try {
            db.execSQL("UPDATE ble_fast_clock SET tick=tick+1 WHERE id=1")
            for(id in ids)db.execSQL("INSERT OR REPLACE INTO ble_fast_dirty(articleId,seq,bodyDirty) SELECT ?,tick,1 FROM ble_fast_clock WHERE id=1",arrayOf(id))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    internal suspend fun <T> frozenWindow(block:suspend()->T):T {
        initialize()
        return database.withTransaction(block)
    }
    internal fun pending():List<Triple<String,Long,Boolean>> {initialize();return db.query("SELECT articleId,seq,bodyDirty FROM ble_fast_dirty").use{c->buildList{while(c.moveToNext())add(Triple(c.getString(0),c.getLong(1),c.getInt(2)!=0))}}}
    internal fun mapped(id:String):Boolean=db.query("SELECT 1 FROM imported_book_chapters WHERE articleId=?",arrayOf(id)).use{it.moveToFirst()}
    internal fun publishManifest(id:String,seq:Long,entry:ArticleSyncManifestEntry?,row:PhoneArticleEntity?=null,expectedDefinition:String=libraryDefinition()):Boolean {
        initialize()
        db.beginTransaction();try {
            if(libraryDefinitionLocked()!=expectedDefinition)return false
            val current=db.query("SELECT seq FROM ble_fast_dirty WHERE articleId=?",arrayOf(id)).use{it.moveToFirst()&&it.getLong(0)==seq}
            if(!current)return false
            if(entry==null)db.execSQL("DELETE FROM ble_fast_manifest WHERE articleId=?",arrayOf(id))
            else db.execSQL("INSERT OR REPLACE INTO ble_fast_manifest(articleId,json) VALUES(?,?)",arrayOf(id,LibrarySyncPayload.encodeManifestEntry(entry).toString()))
            db.execSQL("DELETE FROM ble_fast_manifest_resources WHERE articleId=?",arrayOf(id))
            for(marker in listOfNotNull(row?.contentText,row?.contentHtml).filter{it.startsWith("watchrss-local-text:")}) {
                val path=marker.removePrefix("watchrss-local-text:")
                if(File(path).name==path)db.execSQL("INSERT OR IGNORE INTO ble_fast_manifest_resources(articleId,path) VALUES(?,?)",arrayOf(id,path))
            }
            db.execSQL("DELETE FROM ble_fast_dirty WHERE articleId=? AND seq=?",arrayOf<Any>(id,seq));db.setTransactionSuccessful();return true
        }finally{db.endTransaction()}
    }
    internal fun manifests(ids:Collection<String>?=null):List<ArticleSyncManifestEntry> {
        initialize();val json=JSONArray()
        val groups=ids?.chunked(300)?:listOf(null)
        for(group in groups) {
            val sql="SELECT json FROM ble_fast_manifest"+if(group==null)" ORDER BY articleId" else " WHERE articleId IN (${group.joinToString(","){"?"}}) ORDER BY articleId"
            db.query(sql,group?.toTypedArray()?:emptyArray()).use{while(it.moveToNext())json.put(JSONObject(it.getString(0)))}
        }
        return LibrarySyncPayload.parseArticleManifest(json)
    }
    internal data class Identities(val rows:List<PhoneArticleEntity>,val legacy:Map<String,String>)
    internal fun identities():Identities {
        initialize()
        // Hash identity only when its URL changed. The index and hash survive process death.
        db.beginTransaction()
        try {
            val pending=db.query("SELECT articleId,url FROM ble_fast_identity WHERE legacyId=''").use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
            val update=db.compileStatement("UPDATE ble_fast_identity SET legacyId=? WHERE articleId=? AND url=?")
            try {for((id,url)in pending){update.bindString(1,com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter.stableArticleId(url));update.bindString(2,id);update.bindString(3,url);update.executeUpdateDelete()}}finally{update.close()}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        val legacy=linkedMapOf<String,String>()
        val rows=db.query("SELECT articleId,url,rssSourceUrl,legacyId FROM ble_fast_identity").use{c->buildList{while(c.moveToNext()) {
            val id=c.getString(0);legacy[id]=c.getString(3)
            add(PhoneArticleEntity(id,"",c.getString(1),"","","",null,"",null,"",0,0,false,0,0,if(c.isNull(2))null else c.getString(2),null,false,0,0,false,0,0,false,0))
        }}}
        return Identities(rows,legacy)
    }
    internal fun libraryVersion():String {initialize();return db.query("SELECT epoch,revision FROM ble_fast_versions WHERE domain='library'").use{it.moveToFirst();"${it.getString(0)}:${it.getLong(1)}"}}
}
