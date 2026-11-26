package com.lightningstudio.watchrss.phone.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId

// UIAutomator cannot identify error text by its color.
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.releaseSmokeError(): Modifier = semantics {
    testTagsAsResourceId = true
    testTag = "watchrss_release_smoke_error"
}
