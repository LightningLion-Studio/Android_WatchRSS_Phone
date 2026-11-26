package com.lightningstudio.watchrss.phone.cloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

internal fun recoveryImeOptions(options: Int): Int = options or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

/** Input-method policy only; does not introduce or change any layout. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RecoveryInputPrivacy(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = { request, session ->
        session.startInputMethod(object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = recoveryImeOptions(outAttributes.imeOptions)
                outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                return connection
            }
        })
    }, content = content)
}


// Keep the window secure while the sensitive step is present, even between pause and resume.
@Composable
internal fun RecoveryContentPrivacy(active: Boolean = true, content: @Composable () -> Unit) {
    if (!active) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalContext.current.recoveryActivity()?.window
    val visible = remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, window) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val wasSecure = window?.attributes?.flags?.and(flag) == flag
        window?.addFlags(flag)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> visible.value = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> visible.value = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!wasSecure) window?.clearFlags(flag)
        }
    }
    if (visible.value) content()
}

private tailrec fun Context.recoveryActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.recoveryActivity()
    else -> null
}

internal class RecoveryInputDraft {
    val text = mutableStateOf("")

    fun consumeEntropy(): ByteArray {
        val input = text.value
        clear()
        return RecoveryWords.decode(RecoveryWords.parse(input))
    }

    fun clear() {
        text.value = ""
    }
}

@Composable
internal fun RecoveryDraftPrivacy(
    draft: RecoveryInputDraft,
    active: Boolean,
    bluetoothPaused: Boolean = false,
    content: @Composable () -> Unit
) {
    if (!active) return
    DisposableEffect(draft, bluetoothPaused) { onDispose { draft.clear() } }
    RecoveryContentPrivacy(content = content)
}
