package com.lightningstudio.watchrss.phone.data.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTelemetrySnapshotTest {
    @Test
    fun record_builds_cumulative_daily_counters_without_content_properties() {
        val snapshot = DailyTelemetrySnapshot("2026-08-13")
            .record("screen_opened", mapOf("screen" to "reader", "url" to "https://private"))
            .record("screen_duration", mapOf("screen" to "reader", "durationMs" to 1_500L))
            .record("sync_completed", mapOf("success" to true, "message" to "secret"))

        assertEquals(mapOf("screen_opened" to 1L, "screen_duration" to 1L, "sync_completed" to 1L), snapshot.eventCounts)
        assertEquals(mapOf("reader" to 1L), snapshot.screenOpenCounts)
        assertEquals(mapOf("reader" to 1_500L), snapshot.screenDurationMs)
        assertEquals(1_500L, snapshot.appForegroundMs)
        assertEquals(1, snapshot.syncSuccessCount)
        assertEquals(0, snapshot.syncFailureCount)
    }
}
