package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lightningstudio.watchrss.phone.rememberCloudPaneError
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CloudAccountB3Test {
    @get:Rule val compose = createComposeRule()

    @Test
    fun manualButtonConfirmsBeforeStartingV2AndOnlyReportsCompletedRounds() {
        var syncCalls = 0
        var phase = CloudSyncPhase.IDLE
        val completed = mutableListOf<String>()
        compose.setContent {
            WatchRssPhoneTheme {
                Column {
                    SyncV2AccountActions(
                        busy = false,
                        runAction = { runBlocking { it() } },
                        onBusyChange = {},
                        onSync = {
                            syncCalls++
                            PhoneCloudSyncResult(0, 0, 0, false, 0, PhoneCloudSyncState(phase, "云同步完成"))
                        },
                        onRestore = { error("sync must not start restore") },
                        onCompleted = { completed.add(it) },
                        onFailure = { error("an incomplete round is not an exception") }
                    )
                }
            }
        }

        compose.onNodeWithText("立即同步").performClick()
        compose.onNodeWithText("同步两边的资料。本次同步可使用当前网络。").assertExists()
        compose.runOnIdle { assertEquals(0, syncCalls) }
        compose.onNodeWithText("继续").performClick()
        compose.runOnIdle {
            assertEquals(1, syncCalls)
            assertEquals(emptyList<String>(), completed)
            phase = CloudSyncPhase.COMPLETE
        }

        compose.onNodeWithText("立即同步").performClick()
        compose.onNodeWithText("继续").performClick()
        compose.runOnIdle {
            assertEquals(2, syncCalls)
            assertEquals(listOf("云同步完成"), completed)
        }
    }

    @Test
    fun inventoryFeedbackClearsOnNewCompletionAndRejectsAnOlderCallback() {
        var completedAt by mutableLongStateOf(1L)
        lateinit var onError: (String) -> Unit
        lateinit var formerError: (String) -> Unit
        compose.setContent {
            val error = rememberCloudPaneError("account", completedAt)
            SideEffect { onError = { error.value = it } }
            error.value?.let { Text(it) }
        }
        compose.runOnIdle {
            onError("旧通道提示")
            formerError = onError
        }
        compose.onNodeWithText("旧通道提示").assertExists()
        compose.runOnIdle { completedAt++ }
        compose.onNodeWithText("旧通道提示").assertDoesNotExist()
        compose.runOnIdle { formerError("旧通道提示") }
        compose.onNodeWithText("旧通道提示").assertDoesNotExist()
        compose.runOnIdle { onError("本轮错误") }
        compose.onNodeWithText("本轮错误").assertExists()
    }
    @Test
    fun fix1OnlyLatestSameAccountRefreshCanUpdateFeedback() {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val firstStarted = AtomicBoolean()
        val secondStarted = AtomicBoolean()
        val firstFinished = AtomicBoolean()
        val secondFinished = AtomicBoolean()
        lateinit var refresh: CloudPaneRefresh
        var feedback by mutableStateOf("载入中")
        compose.setContent {
            refresh = rememberCloudPaneRefresh("account", true)
            Text(feedback)
        }
        fun show(result: Result<String>) {
            feedback = result.getOrElse { it.message.orEmpty() }
        }
        compose.runOnIdle {
            refresh.replace(load = {
                withContext(NonCancellable) {
                    firstStarted.set(true)
                    first.await()
                    firstFinished.set(true)
                    throw IllegalStateException("过期错误")
                }
            }, complete = ::show)
        }
        compose.waitUntil { firstStarted.get() }
        compose.runOnIdle { refresh.replace(load = { "新刷新成功" }, complete = ::show) }
        compose.onNodeWithText("新刷新成功").assertExists()
        first.complete(Unit)
        compose.waitUntil { firstFinished.get() }
        compose.onNodeWithText("新刷新成功").assertExists()
        compose.onNodeWithText("过期错误").assertDoesNotExist()

        compose.runOnIdle {
            refresh.replace(load = {
                withContext(NonCancellable) {
                    secondStarted.set(true)
                    second.await()
                    secondFinished.set(true)
                    "过期成功"
                }
            }, complete = ::show)
        }
        compose.waitUntil { secondStarted.get() }
        compose.runOnIdle { refresh.replace(load = { error("新刷新错误") }, complete = ::show) }
        compose.onNodeWithText("新刷新错误").assertExists()
        second.complete(Unit)
        compose.waitUntil { secondFinished.get() }
        compose.onNodeWithText("新刷新错误").assertExists()
        compose.onNodeWithText("过期成功").assertDoesNotExist()
    }

    @Test
    fun fix1OpeningInventoryCancelsDetailRefresh() {
        var inventory by mutableStateOf(false)
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        compose.setContent {
            if (inventory) {
                Text("频道云库存")
            } else {
                val refresh = rememberCloudPaneRefresh("account", true)
                LaunchedEffect(refresh) {
                    refresh.replace(load = {
                        started.set(true)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.set(true)
                        }
                    }, complete = { error("The detail page is gone") })
                }
            }
        }
        compose.waitUntil { started.get() }
        compose.runOnIdle { inventory = true }
        compose.onNodeWithText("频道云库存").assertExists()
        compose.waitUntil { cancelled.get() }
    }

    @Test
    fun fix1BlockedReasonsRenderWithoutMembershipOrLocalKey() {
        var state by mutableStateOf(PhoneCloudSyncState(
            CloudSyncPhase.BLOCKED, errorCode = "not_announced"
        ))
        compose.setContent {
            WatchRssPhoneTheme { CloudSyncBlockedNotice(true, state) }
        }
        compose.onNodeWithText(SyncV2View.errorText("not_announced")).assertExists()
        compose.runOnIdle {
            state = state.copy(errorCode = "feature_disabled",
                member = CloudMemberState(plan = "free", active = false, readable = false, writable = false, quotaBytes = 0,
                    usedBytes = 0, reservedBytes = 0, retentionDays = null, readOnlyAt = null, deleteAfter = null))
        }
        compose.onNodeWithText(SyncV2View.errorText("feature_disabled")).assertExists()
    }

}
