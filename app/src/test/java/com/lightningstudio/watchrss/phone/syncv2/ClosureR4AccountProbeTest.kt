package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.EncryptedAccountSessionStore
import com.lightningstudio.watchrss.phone.account.LicenseDeviceIdentity
import com.lightningstudio.watchrss.phone.account.PhoneAccountClient
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClosureR4AccountProbeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefsName = "closure-r4-${System.nanoTime()}"
    private val store = EncryptedAccountSessionStore(context, prefsName).also {
        ReflectionHelpers.setField(it, "prefsRef", context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
    }
    private val environment = AccountEnvironment(backendBaseUrl = "http://127.0.0.1", supabaseAnonKey = "test", appAccessPublicKey = "")
    private val account = PhoneAccountRepository(
        environment, store, PhoneInstallationIdentity(context),
        PhoneAccountClient(environment, LicenseDeviceIdentity(context)), "phone"
    )
    private val session = PhoneAccountSession("u", "", "synthetic", "synthetic", Long.MAX_VALUE)

    @Test
    fun reauthenticationPauseBeforeSqliteCommitMustLeaveNoStaging() = withCommitHook(
        beforeCommit = {
            runBlocking { account.beginSameAccountReauthentication() }
            assertTrue(account.session.value!!.authenticationPaused)
        }
    ) { result, fixture ->
        assertEquals("authentication_required", result.exceptionOrNull()?.message)
        assertEquals(0, fixture.publishCalls)
        val counts = listOf("note_assets", "syncv2_p5_objects", "syncv2_p5_rows").map { table ->
            fixture.sql.query("SELECT COUNT(*) FROM $table").use { rows ->
                assertTrue(rows.moveToFirst())
                rows.getInt(0)
            }
        }
        assertEquals("staging after actual same-account authentication pause", listOf(0, 0, 0), counts)
    }

    @Test
    fun aPauseAndResumeDuringCommitStillInvalidatesTheCapturedGeneration() = withCommitHook(
        beforeCommit = {
            runBlocking {
                account.beginSameAccountReauthentication()
                store.finishReauthentication()
            }
            assertFalse(account.session.value!!.authenticationPaused)
        }
    ) { result, fixture ->
        assertEquals("authentication_required",result.exceptionOrNull()?.message)
        assertEquals(0,fixture.publishCalls)
        assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM note_assets"))
        assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_p5_objects"))
        assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_p5_rows"))
    }

    @Test
    fun logoutCanInvalidateTheSessionBeforeSqliteCommitFinishes() {
        val started = CountDownLatch(1)
        val enteredLogout = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val stop = IllegalStateException("probe-stops-before-logout-network")
        val logout = Thread({
            try {
                runBlocking {
                    account.withPurchasesBlockedForLogout {
                        started.countDown()
                        account.logoutWithSessionCheck(session) {
                            enteredLogout.countDown()
                            throw stop
                        }
                    }
                }
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                finished.countDown()
            }
        }, "r4-logout-caller")

        try {
            withCommitHook(beforeCommit = {
                logout.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertTrue("logout waited for SQLite",enteredLogout.await(5, TimeUnit.SECONDS))
            }) { result, fixture ->
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertSame(stop, error.get())
                assertEquals("authentication_required",result.exceptionOrNull()?.message)
                assertEquals(0,fixture.publishCalls)
                assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_unconfirmed_writes"))
                assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_p5_rows"))
            }
        } finally {
            logout.join(5000)
        }
    }

    @Test
    fun restartCompensatesOnlyTheJournaledWriteAfterCompensationFails() {
        val otherAction = "91".repeat(16)
        val cleanupError = android.database.sqlite.SQLiteDiskIOException("injected compensation failure")
        withCommitHook(
            configure = { fixture ->
                val repository = SyncV2BodyRepository(fixture.sql,fixture.root)
                repository.file("asset","also.png").writeBytes(repository.file("asset","legacy.png").readBytes())
                for (name in listOf("kept.png","kept-alias.png")) repository.file("asset",name).writeText("kept bytes")
                SyncV2UnconfirmedWrite.transaction(fixture.sql) {
                    for (name in listOf("kept.png","kept-alias.png")) {
                        repository.registerNoteAsset(repository.prepareNoteAssetRegistration(name),fixture.f.now)
                    }
                    fixture.sql.execSQL("INSERT INTO syncv2_p5_objects VALUES(?,?,?,X'00')",arrayOf(fixture.projection.scope,otherAction,"other"))
                    fixture.sql.execSQL("INSERT INTO syncv2_p5_rows VALUES(?,?,?,X'00')",arrayOf(fixture.projection.scope,otherAction,"other"))
                }
                fixture.edit("![legacy](assets/legacy.png)\n![also](assets/also.png)\n![kept](assets/kept.png)\n![kept alias](assets/kept-alias.png)")
            },
            beforeCommit = { runBlocking { account.beginSameAccountReauthentication() } },
            compensationError = cleanupError
        ) { result, fixture ->
            val failure = requireNotNull(result.exceptionOrNull())
            assertEquals("authentication_required",failure.message)
            assertTrue(failure.suppressed.any { it === cleanupError })
            assertEquals(0,fixture.publishCalls)
            assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_unconfirmed_writes"))
            assertEquals(2,count(fixture.sql,"SELECT COUNT(*) FROM note_assets"))
            assertEquals(2,count(fixture.sql,"SELECT COUNT(*) FROM ${SyncV2NoteAssetNames.aliases}"))
            assertTrue(count(fixture.sql,"SELECT COUNT(*) FROM syncv2_p5_objects") > 1)
            assertTrue(count(fixture.sql,"SELECT COUNT(*) FROM syncv2_p5_rows") > 1)
            runBlocking { store.finishReauthentication() }
            repeat(2) {
                fixture.reopen(lifetime = null)
                assertTrue(fixture.projection.state().body.isNull("pendingAccountAction"))
                assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_unconfirmed_writes"))
                assertEquals(0,count(fixture.sql,"SELECT COUNT(*) FROM syncv2_unconfirmed_note_assets"))
                assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM note_assets WHERE storageKey='kept.png'"))
                assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM note_assets"))
                assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM ${SyncV2NoteAssetNames.aliases} WHERE storageKey='kept-alias.png'"))
                assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM ${SyncV2NoteAssetNames.aliases}"))
                for (table in listOf("syncv2_p5_objects","syncv2_p5_rows")) {
                    assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM $table"))
                    assertEquals(1,count(fixture.sql,"SELECT COUNT(*) FROM $table WHERE action_id='$otherAction'"))
                }
                assertTrue(File(fixture.root,"notes/assets/legacy.png").isFile)
                assertTrue(File(fixture.root,"notes/assets/also.png").isFile)
            }
        }
    }

    private fun count(db: SupportSQLiteDatabase, query: String): Int =
        db.query(query).use { rows -> assertTrue(rows.moveToFirst()); rows.getInt(0) }

    private fun withCommitHook(
        configure: (SyncV2P5Fixture) -> Unit = {},
        beforeCommit: () -> Unit,
        compensationError: Throwable? = null,
        verify: (Result<org.json.JSONObject>, SyncV2P5Fixture) -> Unit
    ) {
        runBlocking { store.save(session) }
        val authenticationGeneration = account.cloudAuthentication().generation
        val generation = SyncV2Lifetime.Generation()
        SyncV2Lifetime(generation) {
            val authentication = account.cloudAuthentication()
            val current = authentication.session
            authentication.generation == authenticationGeneration &&
                current?.userId == "u" && current.isUsableForAuthentication && !account.cloudSessionChanging
        }.use { life ->
            SyncV2P5Fixture(life).use { fixture ->
                fixture.pending()
                File(fixture.root, "notes/assets/legacy.png").apply {
                    parentFile!!.mkdirs()
                    writeBytes("verified synthetic asset".toByteArray())
                }
                fixture.edit("![legacy](assets/legacy.png)")
                configure(fixture)
                val sql = fixture.sql
                var staging = false
                var armed = false
                var hit = false
                val wrapped = Proxy.newProxyInstance(
                    SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)
                ) { _, method, args ->
                    if (hit && compensationError != null && method.name == "execSQL" &&
                        args?.firstOrNull() == "DELETE FROM syncv2_p5_objects WHERE scope=? AND action_id=?") {
                        assertTrue(sql.inTransaction())
                        assertEquals(1,count(sql,"SELECT COUNT(*) FROM note_assets"))
                        assertEquals(1,count(sql,"SELECT COUNT(*) FROM ${SyncV2NoteAssetNames.aliases}"))
                        throw compensationError
                    }
                    if (armed && method.name == "setTransactionSuccessful") {
                        armed = false
                        hit = true
                        assertTrue(sql.inTransaction())
                        beforeCommit()
                    }
                    try {
                        method.invoke(sql, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                } as SupportSQLiteDatabase
                val projection = SyncV2Projection(
                    wrapped, fixture.identity, SyncV2Fixtures.localKey, true,
                    fault = { if (staging && !hit && it == "before-commit") armed = true },
                    lifetime = life
                )
                projection.install()
                SyncV2AccountActions(
                    fixture.f.client(), fixture.identity, projection, fixture.f.signer, { fixture.key },
                    { it == "synthetic-phone" }, fixture.root, true, { fixture.f.now },
                    fault = { if (it == "before-action-stage") staging = true }
                ).use { actions ->
                    val result = runCatching { actions.choose(actions.preview(), "local") }
                    assertTrue("commit hook did not run: $result", hit)
                    verify(result, fixture)
                }
            }
        }
    }
}
