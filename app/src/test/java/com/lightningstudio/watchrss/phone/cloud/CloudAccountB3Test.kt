package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lightningstudio.watchrss.phone.rememberCloudPaneError
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.runBlocking
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
}
