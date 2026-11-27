package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CloudLoadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private val words = RecoveryWords.encode(ByteArray(32) { it.toByte() }).joinToString(" ")

    @Test
    fun loadingRowShowsSpinnerAndStatusTogether() {
        compose.setContent { WatchRssPhoneTheme { Column { CloudLoadingRow("正在读取解密状态…") } } }
        compose.onNodeWithText("正在读取解密状态…").assertExists()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun buttonLabelShowsSpinnerOnlyWhileRunning() {
        var running by mutableStateOf<String?>(null)
        compose.setContent { WatchRssPhoneTheme { CloudButtonLabel("刷新", running) } }
        compose.onNodeWithText("刷新").assertExists()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(0)
        running = "刷新中"
        compose.onNodeWithText("刷新中").assertExists()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun unlockShowsDecryptStagesClearsDraftAndRejectsASecondSubmit() {
        val draft = RecoveryInputDraft()
        val decrypt = CompletableDeferred<Unit>()
        val load = CompletableDeferred<Unit>()
        var submits = 0
        var consumed: ByteArray? = null
        compose.setContent {
            WatchRssPhoneTheme {
                var stage by remember { mutableStateOf<String?>(null) }
                Column {
                    RecoveryUnlockForm(draft, busy = stage != null, stage = stage, onSubmit = {
                        submits++
                        stage = RECOVERY_STAGE_DECRYPT
                        scope.launch {
                            try {
                                val entropy = draft.consumeEntropy()
                                consumed = entropy.copyOf()
                                try { decrypt.await() } finally { entropy.fill(0) }
                                stage = RECOVERY_STAGE_LOAD
                                load.await()
                            } finally { stage = null }
                        }
                    })
                }
            }
        }

        val submit = hasClickAction() and hasText("使用恢复词授权此手机")
        compose.onNode(submit).assertIsNotEnabled()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("24词恢复密钥").performTextInput(words)
        compose.onNode(submit).assertIsEnabled().performClick()

        // Waiting for decryption: draft already cleared, button locked with a spinner, stage spelled out.
        compose.runOnIdle {
            assertEquals("", draft.text.value)
            assertTrue(consumed != null)
        }
        compose.onNode(hasClickAction() and hasText("正在解密…")).assertIsNotEnabled().performClick()
        compose.onNodeWithText(RECOVERY_STAGE_DECRYPT).assertExists()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle { assertEquals(1, submits) }

        decrypt.complete(Unit)
        compose.onNodeWithText(RECOVERY_STAGE_LOAD).assertExists()
        compose.onNode(hasClickAction() and hasText("正在解密…")).assertIsNotEnabled()

        load.complete(Unit)
        compose.onNode(submit).assertExists().assertIsNotEnabled()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, submits) }
    }

    @Test
    fun manualSyncShowsSpinnerAndLocksBothActionsUntilTheRoundEnds() {
        val round = CompletableDeferred<PhoneCloudSyncResult>()
        var busy by mutableStateOf(false)
        var syncCalls = 0
        compose.setContent {
            WatchRssPhoneTheme {
                Column {
                    SyncV2AccountActions(
                        busy = busy,
                        runAction = { action -> scope.launch { action() } },
                        onBusyChange = { busy = it },
                        onSync = { syncCalls++; round.await() },
                        onRestore = { error("restore must not start") },
                        onCompleted = {},
                        onFailure = { throw it }
                    )
                }
            }
        }

        compose.onNodeWithText("立即同步").performClick()
        compose.onNodeWithText("继续").performClick()
        compose.onNode(hasClickAction() and hasText("正在同步…")).assertIsNotEnabled()
        compose.onNode(hasClickAction() and hasText("恢复当前云端资料库")).assertIsNotEnabled()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, syncCalls) }

        round.complete(PhoneCloudSyncResult(0, 0, 0, false, 0, PhoneCloudSyncState(CloudSyncPhase.COMPLETE, "云同步完成")))
        compose.onNode(hasClickAction() and hasText("立即同步")).assertIsEnabled()
        compose.onAllNodesWithTag(CLOUD_PROGRESS_TAG, useUnmergedTree = true).assertCountEquals(0)
    }
}
