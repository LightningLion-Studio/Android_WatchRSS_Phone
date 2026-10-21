package com.lightningstudio.watchrss.phone.cloud

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
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
