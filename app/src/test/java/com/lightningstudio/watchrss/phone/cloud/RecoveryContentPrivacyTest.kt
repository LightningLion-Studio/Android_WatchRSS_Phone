package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RecoveryContentPrivacyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun wordDisplayHidesOnPauseAndStopAndRestoresWithSecureWindow() {
        val showStep = mutableStateOf(true)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RecoveryContentPrivacy(showStep.value) {
                    Text("fixture", Modifier.testTag("visible-word"))
                }
            }
        }
        compose.onNodeWithTag("visible-word").assertExists()
        assertTrue(isSecure())

        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.onNodeWithTag("visible-word").assertDoesNotExist()
        assertTrue(isSecure())
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        compose.onNodeWithTag("visible-word").assertDoesNotExist()
        assertTrue(isSecure())

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("visible-word").assertExists()
        compose.runOnIdle { showStep.value = false }
        compose.onNodeWithTag("visible-word").assertDoesNotExist()
        assertFalse(isSecure())
    }

    @Test fun bluetoothPauseClearsDraftAndRestoresPreexistingWindowFlag() {
        val paused = mutableStateOf(false)
        val active = mutableStateOf(true)
        val draft = RecoveryInputDraft()
        compose.runOnUiThread {
            draft.text.value = RecoveryWords.encode(ByteArray(32)).joinToString(" ")
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        compose.setContent {
            RecoveryDraftPrivacy(draft, active.value, paused.value) {
                Text("fixture", Modifier.testTag("recovery-input"))
            }
        }
        compose.onNodeWithTag("recovery-input").assertExists()
        assertEquals(24, RecoveryWords.parse(draft.text.value).size)
        compose.runOnIdle { paused.value = true }
        compose.onNodeWithTag("recovery-input").assertExists()
        assertTrue(draft.text.value.isEmpty())
        assertTrue(isSecure())

        compose.runOnIdle {
            draft.text.value = "new draft"
            active.value = false
        }
        compose.onNodeWithTag("recovery-input").assertDoesNotExist()
        assertTrue(draft.text.value.isEmpty())
        assertTrue(isSecure())
    }

    private fun isSecure() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
