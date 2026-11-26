package com.lightningstudio.watchrss.phone.rollout

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FeatureDebugCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchWritesBothOverridesAndRestoreReturnsToCloud() {
        val feature = FeatureRegistry.cloudSyncEntry
        val override = mutableStateOf<FeatureOverride?>(null)
        compose.setContent {
            val state = ClientFeatureDebugState(
                key = feature.key,
                remote = feature.defaults,
                bucketHit = false,
                remoteEnabled = false,
                override = override.value,
                enabled = override.value == FeatureOverride.ON
            )
            WatchRssPhoneTheme {
                FeatureDebugCard(state, enabled = state.enabled) { override.value = it }
            }
        }
        val toggle = compose.onNodeWithContentDescription(feature.key)
        val restore = compose.onNodeWithText("恢复云控")
        val following = compose.onNodeWithText("云控 · 放量 0% · 未命中")
        val forced = compose.onNodeWithText("本机强制 · 云端为 关（放量 0% · 未命中）")

        toggle.assertIsOff()
        following.assertExists()
        restore.assertDoesNotExist()

        toggle.performClick()
        toggle.assertIsOn()
        assertEquals(FeatureOverride.ON, override.value)
        forced.assertExists()

        toggle.performClick()
        toggle.assertIsOff()
        assertEquals(FeatureOverride.OFF, override.value)
        forced.assertExists()
        following.assertDoesNotExist()

        restore.performClick()
        toggle.assertIsOff()
        assertEquals(null, override.value)
        following.assertExists()
        restore.assertDoesNotExist()
    }

    @Test
    fun missingCloudValueUsesBundledDefaultLabel() {
        val state = ClientFeatureDebugState(
            key = FeatureRegistry.b2.key,
            remote = null,
            bucketHit = null,
            remoteEnabled = null,
            override = null,
            enabled = false
        )
        assertEquals("包内默认", state.status)
        assertEquals("本机强制 · 云端值未取到", state.copy(override = FeatureOverride.OFF).status)
    }
}
