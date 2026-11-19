package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.db.*
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.File

/** Main-only book domains must share the same confirmed-progress budget as articles. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MainDomainRecoveryProgressTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
    private val root = File(context.cacheDir, "domain-progress-${System.nanoTime()}").apply { mkdirs() }
    private val store = ImportedBookStore(db, root, null, "phone")
    @After fun close() { db.close(); root.deleteRecursively() }
    private suspend fun seed(count: Int) {
        repeat(count) { n ->
            val key = "progress-$n"; val source = ImportedContentIds.txtNovelSourceUrl(key)
            val text = "Synthetic book $n body"
            db.phoneRssSourceDao().upsert(PhoneRssSourceEntity(source, "phone", key, "", null, null, 1, 10, 1, false, false, 0))
            db.phoneArticleDao().upsert(PhoneArticleEntity(key, "phone", ImportedContentIds.txtNovelChapterUrl(key, 0, "hash"),
                key, key, "", null, text, null, CloudSnapshotCodec.sha256(text.toByteArray()), 1, 10,
                false, 0, 0, source, key, false, 0, 0, false, 0, 0, false, 0))
        }
        store.ensureRegistered()
    }
    private fun reply(phase: String) = JSONObject().put("success", true).put("action", "syncBooks").put("version", 1).put("phase", phase)

    private suspend fun upload(repeatCompletedBook: Boolean): Pair<Throwable?, Int> {
        seed(5)
        var now = 0L; var generation = 1L; var reconnects = 0; var completedThisConnection = false
        val completed = mutableSetOf<String>()
        val p = LibraryTransferRecovery { now }.apply { observe(generation) }
        val recovery = LibraryRecoveryContext(p, LibrarySyncMode.MERGE, { true },
            { reconnects++; generation++; p.observe(generation); completedThisConnection = false }, {}, {}, { _, _ -> }, {})
        val failure = runCatching { withContext(recovery) { recovery.unit("books") {
            BookBluetoothSync(store, "phone", root) { q ->
                when (q.getString("phase")) {
                    "manifest" -> {
                        val ids = store.manifests().map { it.getString("bookId") }
                        val missing = if (repeatCompletedBook && reconnects < 4) ids.take(2) else ids.filterNot { it in completed }
                        reply("manifest").put("books", JSONArray()).put("missingBookIds", JSONArray(missing))
                    }
                    "body" -> {
                        if (completedThisConnection) { now += 6000; throw LibraryTransportFailure(generation, EOFException()) }
                        assertEquals(1, q.getInt("chunkCount"))
                        val bytes=java.util.Base64.getDecoder().decode(q.getString("dataBase64"))
                        assertEquals(q.getString("packageSha256"),CloudSnapshotCodec.sha256(bytes))
                        completed += q.getString("bookId"); completedThisConnection = true
                        reply("body").put("received", true).put("complete", true)
                    }
                    else -> error("unexpected ${q.getString("phase")}")
                }
            }.sync(syncReading = false)
        } } }.exceptionOrNull()
        if (failure == null) assertEquals(5, completed.size)
        if (repeatCompletedBook && failure is LibraryNoProgressLimit) assertEquals(1, completed.size)
        return failure to reconnects
    }
    @Test fun newCompletedBooksKeepFourReadyIntervalsAlive() = runBlocking<Unit> {
        val (failure, reconnects) = upload(false)
        assertNull("new peer-confirmed books must reset the no-progress budget: $failure", failure)
        assertEquals(4, reconnects)
    }
    @Test fun duplicateCompletedBookReceiptsCannotRenewTheRecoveryBudget() = runBlocking<Unit> {
        val (failure, reconnects) = upload(true)
        assertTrue(failure is LibraryNoProgressLimit)
        assertEquals(3, reconnects)
    }
    @Test fun newlyAppliedBookMetadataKeepsFourReadyIntervalsAlive() = runBlocking<Unit> {
        seed(1)
        val state = store.manifests().single()
        var now = 0L; var generation = 1L; var attempt = 1
        val p = LibraryTransferRecovery { now }.apply { observe(generation) }
        val recovery = LibraryRecoveryContext(p, LibrarySyncMode.MERGE, { true },
            { attempt++; generation++; p.observe(generation) }, {}, {}, { _, _ -> }, {})
        val failure = runCatching { withContext(recovery) { recovery.unit("books") {
            BookBluetoothSync(store, "phone", root) { q ->
                when (q.getString("phase")) {
                    "manifest" -> reply("manifest").put("books", JSONArray().put(JSONObject(state.toString())
                        .put("title", "confirmed title $attempt").put("updatedAt", 100 + attempt).put("sourceDeviceId", "watch")))
                        .put("missingBookIds", JSONArray())
                    "reading" -> {
                        if (attempt < 5) { now += 6000; throw LibraryTransportFailure(generation, EOFException()) }
                        reply("reading").put("states", JSONArray()).put("hasMore", false).put("nextAfter", 0)
                    }
                    else -> error("unexpected phase")
                }
            }.sync()
        } } }.exceptionOrNull()
        assertNull("actual Room changes must reset the no-progress budget: $failure", failure)
        assertEquals(5, attempt)
        assertEquals("confirmed title 5", store.manifests().single().getString("title"))
    }
    private suspend fun metadataUpload(peerConfirms: Boolean): Pair<Throwable?, Int> {
        seed(1)
        val original=store.manifests().single()
        fun revision(n:Int)=JSONObject(original.toString()).put("title","outgoing title $n")
            .put("updatedAt",100+n).put("sourceDeviceId","phone")
        store.merge(revision(1))
        val oldPeer=JSONObject(store.manifests().single().toString())
        var now=0L;var generation=1L;var attempt=1
        val p=LibraryTransferRecovery { now }.apply { observe(generation) }
        val recovery=LibraryRecoveryContext(p,LibrarySyncMode.MERGE,{true},
            {attempt++;generation++;p.observe(generation);store.merge(revision(attempt))},{},{},{_,_->},{})
        val failure=runCatching { withContext(recovery) { recovery.unit("books") {
            BookBluetoothSync(store,"phone",root) { q ->
                when(q.getString("phase")) {
                    "manifest" -> reply("manifest").put("books",if(peerConfirms) JSONArray(q.getJSONArray("books").toString()) else JSONArray().put(oldPeer))
                        .put("missingBookIds",JSONArray())
                    "reading" -> {
                        if(attempt<5) {now+=6000;throw LibraryTransportFailure(generation,EOFException())}
                        reply("reading").put("states",JSONArray()).put("hasMore",false).put("nextAfter",0)
                    }
                    else -> error("unexpected phase")
                }
            }.sync()
        } } }.exceptionOrNull()
        return failure to attempt
    }
    @Test fun exactPeerMetadataConfirmationsKeepFourReadyIntervalsAlive() = runBlocking<Unit> {
        val (failure,attempt)=metadataUpload(true)
        assertNull("peer-confirmed new book metadata must reset the budget: $failure",failure)
        assertEquals(5,attempt)
    }
    @Test fun stalePeerMetadataCannotRenewTheRecoveryBudget() = runBlocking<Unit> {
        val (failure,attempt)=metadataUpload(false)
        assertTrue(failure is LibraryNoProgressLimit)
        assertEquals(4,attempt)
    }

}
