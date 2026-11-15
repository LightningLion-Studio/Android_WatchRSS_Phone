package com.lightningstudio.watchrss.phone

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,sdk=[35])
class PhoneSyncLoginActionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun loginButtonRunsProductionNonDestructiveReauthenticationNavigation() {
        var dismissed=0
        compose.setContent {
            val context=LocalContext.current
            WatchRssPhoneTheme { PhoneSyncLoginDialog({dismissed++},{openPhoneSyncReauthentication(context){dismissed++}}) }
        }
        while(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity!=null) { /* discard the test host launch */ }
        compose.onNodeWithText("去登录").performClick()
        val intent=Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(AccountActivity::class.java.name,intent.component!!.className)
        assertTrue(intent.getBooleanExtra(AccountActivity.EXTRA_REAUTHENTICATE,false))
        assertEquals(1,dismissed)
    }
    @Test fun cancelOnlyDismissesWithoutStartingAuthentication() {
        var dismissed=0
        compose.setContent { WatchRssPhoneTheme { PhoneSyncLoginDialog({dismissed++},{error("cancel must not navigate")}) } }
        while(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity!=null) { /* discard the test host launch */ }
        compose.onNodeWithText("取消").performClick()
        assertEquals(1,dismissed)
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }
}
