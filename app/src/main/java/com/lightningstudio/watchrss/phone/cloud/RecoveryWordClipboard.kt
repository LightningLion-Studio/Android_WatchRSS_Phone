package com.lightningstudio.watchrss.phone.cloud

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/** Keeps the expiry alive when the recovery screen is closed. All calls are on the main thread. */
@MainThread
internal class RecoveryWordClipboard(private val clipboard: ClipboardManager) {
    private class PendingCopy(val id: String, val digest: ByteArray, val expiresAt: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var pending: PendingCopy? = null
    private var observedWindow: WeakReference<View>? = null
    private val expire = Runnable { clearExpiredCopy() }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) handler.post(expire)
    }

    fun copy(words: List<String>, window: View) {
        val text = words.joinToString(" ")
        val id = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("恢复词", text)
        clip.description.extras = PersistableBundle().apply {
            putString(COPY_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        pending = PendingCopy(id, digest(text), SystemClock.elapsedRealtime() + EXPIRY_MILLIS)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, EXPIRY_MILLIS)
        observeWindow(window)
    }

    fun observeWindow(window: View) {
        if (pending == null) return
        if (observedWindow?.get() !== window) {
            stopObservingWindow()
            window.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
            observedWindow = WeakReference(window)
        }
        if (window.hasWindowFocus()) handler.post(expire)
    }

    private fun clearExpiredCopy() {
        val copy = pending ?: return
        if (SystemClock.elapsedRealtime() < copy.expiresAt) return
        // Android 10+ restricts clipboard reads to the focused app or the active IME.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            observedWindow?.get()?.hasWindowFocus() != true
        ) return

        pending = null
        handler.removeCallbacks(expire)
        stopObservingWindow()
        if (clipboard.primaryClipDescription?.extras?.getString(COPY_ID) != copy.id) return
        val clip = clipboard.primaryClip ?: return
        if (clip.description.extras?.getString(COPY_ID) != copy.id || clip.itemCount != 1) return
        val text = clip.getItemAt(0).text ?: return
        if (!copy.digest.contentEquals(digest(text))) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun stopObservingWindow() {
        observedWindow?.get()?.viewTreeObserver
            ?.takeIf { it.isAlive }
            ?.removeOnWindowFocusChangeListener(focusListener)
        observedWindow = null
    }

    private fun digest(text: CharSequence): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8))

    private companion object {
        const val COPY_ID = "com.lightningstudio.watchrss.phone.RECOVERY_COPY_ID"
        const val EXPIRY_MILLIS = 60_000L
    }
}
