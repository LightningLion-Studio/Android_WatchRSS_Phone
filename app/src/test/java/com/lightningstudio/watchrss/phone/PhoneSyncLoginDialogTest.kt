package com.lightningstudio.watchrss.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Renders the production Compose dialog on the host; no emulator or device. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35],qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSyncLoginDialogTest {
    @Test fun rendersProductionLoginPrompt() = capture("login-required-host.png") { PhoneSyncLoginDialog({}, {}) }
    @Test fun rendersDeviceVerificationPrompt() = capture("device-verification-host.png") { PhoneDeviceVerificationDialog({}, {}) }

    private fun capture(fileName: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { WatchRssPhoneTheme { content() } }
            ShadowLooper.idleMainLooper(500L)
            val global=ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"),"getInstance")
            val views=ReflectionHelpers.getField<List<View>>(global,"mViews")
            val dialog=views.last()
            assertTrue("dialog must be laid out",dialog.width>0 && dialog.height>0)
            val bitmap=Bitmap.createBitmap(dialog.width,dialog.height,Bitmap.Config.ARGB_8888)
            dialog.draw(Canvas(bitmap))
            System.getenv("WRSS_ACCTGATE_EVIDENCE")?.let { path ->
                File(path,fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            assertTrue(bitmap.width>100)
        } finally { controller.pause().stop().destroy() }
    }
}
