package com.lightningstudio.watchrss.phone

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native AccountActivity + actual SMS UI callbacks, local synthetic issuer and Android Keystore. */
@RunWith(AndroidJUnit4::class)
class Hotfix133LoginUiDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun deviceVerificationReauthenticatesSameOwnerWithoutLosingRoot()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneCompanionApplication
        val account=app.container.accountRepository
        account.initialize()
        PhonePrivacyConsentStore(context).acceptRequiredPolicies()
        val owner="hotfix133-ui-owner"
        account.saveCompletedLogin(LoginProgress("synthetic",1,emptyList(),true,PhoneAccountSession(owner,"synthetic","synthetic-old","",Long.MAX_VALUE)))
        val keys=CloudKeyManager(context)
        val root=keys.getOrCreateAccountKey(owner)
        val server=MockWebServer()
        val verified=java.util.concurrent.atomic.AtomicBoolean(false)
        val authorizationStatus=java.util.concurrent.atomic.AtomicInteger(401)
        val claimed=java.util.concurrent.atomic.AtomicInteger(0)
        val verificationCount=java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response=when {
                    request.path!!.endsWith("/login/start") -> JSONObject().put("transactionId","synthetic").put("complete",false)
                    request.path!!.endsWith("/login/mfa/sms/request") -> JSONObject()
                    request.path!!.endsWith("/login/mfa/sms/verify") -> {
                        verified.set(true);verificationCount.incrementAndGet()
                        JSONObject().put("transactionId","synthetic").put("complete",true).put("session",
                            JSONObject().put("userId",owner).put("accessToken","synthetic-new").put("phoneMasked","synthetic").put("expiresAt",Long.MAX_VALUE).put("activationProof","synthetic-proof"))
                    }
                    request.path!!.startsWith("/functions/v1/account/app-access?") -> {
                        if (authorizationStatus.get()!=200) return MockResponse().setResponseCode(authorizationStatus.get()).setBody("{}")
                        JSONObject().put("deviceStatus","unclaimed").put("accessMode","paid")
                    }
                    request.path!!.endsWith("/phone-authorizations/challenge") -> JSONObject().put("challengeId","synthetic").put("nonce","synthetic").put("expiresAt",System.currentTimeMillis()+60000)
                    request.path!!.endsWith("/phone-authorizations/claim") -> {
                        claimed.incrementAndGet()
                        JSONObject().put("deviceAccessToken","synthetic-device").put("deviceAccessTokenExpiresAt",Long.MAX_VALUE)
                            .put("lease","synthetic-server-lease").put("leaseExpiresAt",Long.MAX_VALUE).put("serverTime",System.currentTimeMillis())
                            .put("access",JSONObject().put("deviceStatus","authorized").put("accessMode","paid"))
                    }
                    request.path!!.endsWith("/logout") -> JSONObject()
                    else -> return MockResponse().setResponseCode(404).setBody("{}")
                }
                return MockResponse().setBody(response.toString())
            }
        }
        server.start()
        val field=PhoneAccountRepository::class.java.getDeclaredField("accountClient").apply {isAccessible=true}
        val previous=field.get(account)
        field.set(account,PhoneAccountClient(AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic"),LicenseDeviceIdentity(context)))
        try {
            val scenario=ActivityScenario.launch<AccountActivity>(AccountActivity.createIntent(context,finishAfterLogin=true,reauthenticate=true))
            scenario.use {
                compose.waitUntil(10000){account.reauthenticationOwner.value==owner}
                assertFalse(account.hasUsableSession)
                assertEquals(Long.MAX_VALUE,account.session.value!!.expiresAtMillis)
                assertArrayEquals(root,keys.getAccountKey(owner))
                compose.onNodeWithText("手机号").performScrollTo().performTextInput("13800000133")
                compose.onNodeWithText("发送验证码").performScrollTo().performClick()
                compose.waitUntil(10000){compose.onAllNodesWithText("验证码已发送").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("验证码",useUnmergedTree=false).performScrollTo().performTextInput("123456")
                compose.onNodeWithText("继续").performScrollTo().performClick()
                compose.waitUntil(10000){verified.get() && account.reauthenticationOwner.value==owner &&
                    compose.onAllNodesWithText("登录已失效，请重新登录").fetchSemanticsNodes().isNotEmpty()}
                assertFalse(account.hasUsableSession)
                assertArrayEquals(root,keys.getAccountKey(owner))
                compose.onNodeWithText("手机号").assertExists()
                authorizationStatus.set(503);verified.set(false)
                compose.onNodeWithText("发送验证码").performScrollTo().performClick()
                compose.waitUntil(10000){compose.onAllNodesWithText("验证码已发送").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("验证码").performScrollTo().performTextInput("123456")
                compose.onNodeWithText("继续").performScrollTo().performClick()
                compose.waitUntil(10000){verified.get() && compose.onAllNodesWithText("重试设备授权").fetchSemanticsNodes().isNotEmpty()}
                assertArrayEquals(root,keys.getAccountKey(owner))
                assertEquals(2,verificationCount.get())
                authorizationStatus.set(200)
                compose.onNodeWithText("重试设备授权").performClick()
                compose.waitUntil(10000){claimed.get()>0 && scenario.state==androidx.lifecycle.Lifecycle.State.DESTROYED}
                assertEquals("Device retry must not repeat MFA",2,verificationCount.get())
                assertTrue(account.hasUsableSession)
                assertEquals(owner,account.session.value?.userId)
                assertNull(account.reauthenticationOwner.value)
                assertArrayEquals(root,keys.getAccountKey(owner))
            }
        } finally {
            runCatching {account.logout()}
            field.set(account,previous)
            server.shutdown()
        }
    }
    @Test fun expiredLoginDialogRendersAndCancelPreservesAccount()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PhonePrivacyConsentStore(context).acceptRequiredPolicies()
        val account=(context.applicationContext as PhoneCompanionApplication).container.accountRepository
        account.initialize()
        account.saveCompletedLogin(LoginProgress("expired",1,emptyList(),true,PhoneAccountSession("hotfix133-expired","synthetic","synthetic-expired","",1)))
        var dismissed=0
        val scenario=ActivityScenario.launch<AccountActivity>(AccountActivity.createIntent(context))
        scenario.use {
            it.onActivity { activity -> activity.setContent { PhoneSyncLoginDialog({dismissed++},{error("cancel must not authenticate")}) } }
            compose.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null),"hotfix133-login-dialog.png").outputStream().use { output ->
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
            }
            compose.onNodeWithText("取消").performClick()
            assertEquals(1,dismissed)
            assertEquals("synthetic-expired",account.session.value?.accessToken)
            assertNull(account.reauthenticationOwner.value)
        }
    }

}
