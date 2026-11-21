package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.room.Room
import androidx.room.withTransaction
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.book.ImportedBookStore
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class V1BookCommitTest {
    private val fixture = JSONObject(requireNotNull(javaClass.getResource("/book/cli-single-txt-book.json")).readText())
    private val id = fixture.getString("bookId")
    private val raw = fixture.getString("packageUtf8")
    private val revision = BookPackageFiles.Revision(CloudSnapshotCodec.sha256(raw.toByteArray()), raw.toByteArray().size.toLong())

    @Test fun cancellationWhileWaitingForRoomWithdrawsNewOriginal() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "queued-${System.nanoTime()}").apply { mkdirs() }
        val db = Room.databaseBuilder(context, PhoneCompanionDatabase::class.java, File(root, "room.db").absolutePath)
            .allowMainThreadQueries().build()
        val entered = CountDownLatch(1)
        val permit = CompletableDeferred<Unit>()
        var blocker: Job? = null
        try {
            val stage = File(root, "wire").apply { writeText(raw) }
            val dir = File(root, "imported_books")
            val store = ImportedBookStore(db, dir, null, "phone")
            var checks = 0
            val job = launch(Dispatchers.Default) {
                val owner = currentCoroutineContext()
                store.receiveVerifiedPackage(id, stage, revision) {
                    owner.ensureActive()
                    if (++checks == 3) {
                        blocker = launch {
                            db.withTransaction { entered.countDown(); permit.await() }
                        }
                        check(entered.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            val published = File(dir, "$id.jsonl")
            withTimeout(12000) { while (!published.isFile) { check(!job.isCompleted); delay(10) } }
            job.cancel()
            permit.complete(Unit)
            job.join()
            blocker?.join()
            assertTrue(job.isCancelled)
            assertNull(db.bookDao().get(id))
            assertTrue(db.bookDao().chapters().isEmpty())
            assertFalse(published.exists())
            println("queued cancellation: Room book=0, chapters=0, final file visible=false")
        } finally {
            permit.complete(Unit)
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun sourceReplacementAfterPublishKeepsChaptersFromVerifiedSnapshot() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "replaced-${System.nanoTime()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val stage = File(root, "wire").apply { writeText(raw) }
            val dir = File(root, "imported_books")
            val store = ImportedBookStore(db, dir, null, "phone")
            val lines = raw.lines().filter { it.isNotBlank() }
            val replacement = JSONObject(lines[1]).put("contentText", "replacement text")
            var replaced = false
            store.receiveVerifiedPackage(id, stage, revision) {
                if (!replaced && File(dir, "$id.jsonl").isFile) {
                    stage.writeText("${lines[0]}\n$replacement\n")
                    replaced = true
                }
            }
            assertTrue(replaced)
            assertEquals(raw, store.packageFile(id)!!.readText())
            assertEquals(revision.sha256, db.bookDao().get(id)!!.packageSha256)
            assertEquals(fixture.getJSONObject("chapter").getString("contentText"), db.phoneArticleDao().getAllForSync().single().contentText)
            println("v1 input replacement: registered original matches initial SHA; Room chapter reflects captured bytes")
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun b2OffInputReplacementAfterSnapshotStillCommitsOneChapter() = runBlocking<Unit> {
        assertFalse(com.lightningstudio.watchrss.phone.BuildConfig.B2_ENABLED)
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "off-replacement-${System.nanoTime()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val stage = File(root, "wire").apply { writeText(raw) }
            val dir = File(root, "imported_books")
            val store = ImportedBookStore(db, dir, null, "phone")
            val lines = raw.lines().filter { it.isNotBlank() }
            val changed = JSONObject(lines[1]).put("contentText", "different pre-copy text")
            var checks = 0
            val replace: () -> Unit = {
                if (++checks == 2) stage.writeText("${lines[0]}\n$changed\n")
            }
            assertTrue(store.receiveVerifiedPackage(id, stage, revision, checkOwner = replace) > 0)
            assertTrue(checks >= 2)
            assertEquals(raw, File(dir, "$id.jsonl").readText())
            assertEquals(1, db.bookDao().chapters().size)
            assertEquals(fixture.getJSONObject("chapter").getString("contentText"),
                db.phoneArticleDao().getAllForSync().single().contentText)
            println("B2-off source changed after staging: success=true, visible=true, chapters=1, originalMatchesWire=true")
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
