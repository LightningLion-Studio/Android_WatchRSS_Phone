package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class RecoveryWordClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val recoveryClipboard = RecoveryWordClipboard(clipboard)
    private val window = TestWindow(context)
    private val mainLooper = shadowOf(Looper.getMainLooper())
    // These tokens are not valid recovery words.
    private val words = List(24) { "clipboard-fixture-${it + 1}" }

    @Test
    fun copiesAllWordsInOrderAsSensitivePlainTextAndClearsAtSixtySeconds() {
        recoveryClipboard.copy(words, window)

        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(1, clip.itemCount)
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertTrue(clip.getItemAt(0).text.toString() == words.joinToString(" "))

        mainLooper.idleFor(Duration.ofMillis(59_999))
        assertTrue(clipboard.hasPrimaryClip())
        mainLooper.idleFor(Duration.ofMillis(1))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun expiryLeavesAReplacementClipWithTheSameTextAlone() {
        recoveryClipboard.copy(words, window)
        val replacement = ClipData.newPlainText("user copy", words.joinToString(" "))
        clipboard.setPrimaryClip(replacement)

        mainLooper.idleFor(Duration.ofSeconds(60))

        assertEquals("user copy", clipboard.primaryClipDescription?.label?.toString())
    }

    @Test
    fun expiryLeavesEditedTextAloneEvenWhenItsMetadataWasPreserved() {
        recoveryClipboard.copy(words, window)
        val edited = ClipData.newPlainText("edited", "replacement")
        edited.description.extras = clipboard.primaryClipDescription?.extras
        clipboard.setPrimaryClip(edited)

        mainLooper.idleFor(Duration.ofSeconds(60))

        assertEquals("edited", clipboard.primaryClipDescription?.label?.toString())
    }

    @Test
    fun secondCopyRetainsItsOwnFullSixtySeconds() {
        recoveryClipboard.copy(words, window)
        mainLooper.idleFor(Duration.ofSeconds(30))
        recoveryClipboard.copy(words, window)
        mainLooper.idleFor(Duration.ofSeconds(30))
        assertTrue(clipboard.hasPrimaryClip())

        mainLooper.idleFor(Duration.ofSeconds(30))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun anExpiredCopyIsCheckedWhenTheAppReturnsToFocus() {
        recoveryClipboard.copy(words, window)
        window.focused = false

        mainLooper.idleFor(Duration.ofSeconds(60))
        assertTrue(clipboard.hasPrimaryClip())

        recoveryClipboard.observeWindow(TestWindow(context))
        mainLooper.idle()
        assertFalse(clipboard.hasPrimaryClip())
    }

    private class TestWindow(context: Context) : View(context) {
        var focused = true

        override fun hasWindowFocus(): Boolean = focused
    }
}
