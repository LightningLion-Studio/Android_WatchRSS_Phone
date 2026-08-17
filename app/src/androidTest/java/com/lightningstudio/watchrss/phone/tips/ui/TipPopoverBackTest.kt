package com.lightningstudio.watchrss.phone.tips.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightningstudio.watchrss.phone.tips.TipDefinition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TipPopoverBackTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backDismissesPopoverWithoutReachingUnderlyingScreen() {
        var underlyingBackCount by mutableIntStateOf(0)
        var popoverVisible by mutableStateOf(true)

        composeTestRule.setContent {
            MaterialTheme {
                BackHandler { underlyingBackCount += 1 }
                if (popoverVisible) {
                    TipPopover(
                        definition = TipDefinition(
                            id = "back-test",
                            title = "返回键测试",
                            message = "提示内容"
                        ),
                        anchorBounds = Rect(100f, 100f, 200f, 160f),
                        onDismiss = { popoverVisible = false }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("返回键测试").assertIsDisplayed()
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("返回键测试").assertDoesNotExist()
        assertEquals(0, underlyingBackCount)
    }
}
