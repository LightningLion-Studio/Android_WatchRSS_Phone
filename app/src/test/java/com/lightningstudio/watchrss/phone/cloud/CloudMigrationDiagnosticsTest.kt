package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudMigrationDiagnosticsTest {
    @Test fun diagnosticEventsHaveFixedVocabularyAndNeverContainExceptionPayload()=runBlocking {
        val events=mutableListOf<CloudMigrationEvent>();var clock=0L
        val diagnostics=CloudMigrationDiagnostics(CloudMigrationObserver {events+=it}){clock}
        diagnostics.measure(CloudMigrationOperation.DOWNLOAD,97) {clock=20_000_000;"body not exported"}
        val secret="Bearer secret /article/id?token=123 original body keyBase64"
        val errors=listOf(IOException(secret),CloudHttpException(429,secret),IllegalArgumentException(secret),IllegalStateException("migration_checkpoint_failed"),CancellationException(secret))
        for(error in errors) assertTrue(runCatching{diagnostics.measure(CloudMigrationOperation.VALIDATE){throw error}}.isFailure)
        val serialized=events.joinToString {it.fields().toString()}
        for(value in listOf(secret,"Bearer","original body","keyBase64","/article/id"))assertFalse(serialized.contains(value))
        assertEquals(97L,events.first().downloadedBytes);assertEquals(20L,events.first().elapsedMs)
        assertTrue(events.drop(1).all{it.downloadedBytes==0L})
        assertEquals(listOf("network","network","invalid_result","checkpoint","cancelled"),events.drop(1).map{it.failure!!.name.lowercase()})
    }
    @Test fun formatFloor426IsStableUpgradeStateAndNeverRetryable() {
        val failure=CloudMigrationDiagnostics.classify(CloudHttpException(426,"{\"error\":\"cloud_format_upgrade_required\"}"))
        assertEquals("cloud_format_upgrade_required",failure.name.lowercase());assertFalse(failure.retryable)
        assertFalse(serverRequiresCloudClientUpgrade(CloudHttpException(426,"untrusted response")))
        assertEquals("旧同步通道已关闭，请等待可用的 Sync v2 服务",CloudHttpException(426,"untrusted response").message)
        assertFalse(serverRequiresCloudClientUpgrade(CloudHttpException(426,"{\"error\":\"client_upgrade_required\"}")))
        assertTrue(serverRequiresCloudClientUpgrade(CloudHttpException(426,"{\"error\":\"client_upgrade_required\"}",serverResponse=true)))
        assertTrue(serverRequiresCloudClientUpgrade(CloudHttpException(426,"{\"error\":{\"code\":\"client_upgrade_required\"}}",serverResponse=true)))
        assertFalse(serverRequiresCloudClientUpgrade(CloudHttpException(426,"{\"error\":{\"code\":\"upgrade_required\"}}",serverResponse=true)))
    }
    @Test fun diagnosticsFailureCannotBreakPublicationOrChangeItsException()=runBlocking {
        val diagnostics=CloudMigrationDiagnostics(CloudMigrationObserver {error("synthetic logger full")})
        assertEquals(42,diagnostics.measure(CloudMigrationOperation.CREATE){42})
        val failure=IOException("synthetic transport")
        assertSame(failure,runCatching{diagnostics.measure(CloudMigrationOperation.CREATE){throw failure}}.exceptionOrNull())
    }
}
