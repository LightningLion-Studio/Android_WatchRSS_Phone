package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RecoveryWordsDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun copyButtonShowsUpdatedInstructionsAndCopiesTheDisplayedWords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val recoveryClipboard = RecoveryWordClipboard(clipboard)
        val words = List(24) { "display-fixture-${it + 1}" }
        compose.setContent {
            WatchRssPhoneTheme {
                Column {
                    RecoveryWordsDisplay(words, recoveryClipboard, enabled = true)
                }
            }
        }

        compose.onNodeWithText("请妥善保存这 24 个恢复词").assertExists()
        assertTrue(!clipboard.hasPrimaryClip())
        compose.onNodeWithText("复制恢复词").performClick()
        assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == words.joinToString(" "))
        assertEquals("已复制，请保存到安全的地方", ShadowToast.getTextOfLatestToast())
    }
}
