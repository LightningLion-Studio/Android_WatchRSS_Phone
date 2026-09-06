package com.lightningstudio.watchrss.phone.support

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.Gravity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.ui.input.pointer.pointerInteropFilter
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightningstudio.watchrss.phone.*
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

/** Attached to app activities only; no system overlay permission or background window. */
class SupportOverlay {
    private var enabled = false
    private var expanded by mutableStateOf(false)
    private var host: Activity? = null
    private var view: ComposeView? = null
    private var model: SupportViewModel? = null
    private var snapAnimator: ValueAnimator? = null
    private var positionX = 1f
    private var positionY = .5f
    private var layoutHost: ViewGroup? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBubble() }

    private fun placeBubble(dx: Float = 0f, dy: Float = 0f) {
        val bubble = view ?: return
        val root = layoutHost ?: return
        if (bubble.width == 0 || root.width == 0) return
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        // The Compose root includes 16 dp of transparent shadow space on every side.
        val margin = 0f
        val left = (insets?.left ?: 0) + margin
        val top = (insets?.top ?: 0) + margin
        val width = (root.width - (insets?.right ?: 0) - margin - bubble.width - left).coerceAtLeast(0f)
        val height = (root.height - (insets?.bottom ?: 0) - margin - bubble.height - top).coerceAtLeast(0f)
        if (width > 0) positionX = (positionX + dx / width).coerceIn(0f, 1f)
        if (height > 0) positionY = (positionY + dy / height).coerceIn(0f, 1f)
        bubble.translationX = left + width * positionX
        bubble.translationY = top + height * positionY
    }

    private fun snapToEdge() {
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(positionX, if (positionX < .5f) 0f else 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { positionX = it.animatedValue as Float; placeBubble() }
            start()
        }
    }

    fun activate(vm: SupportViewModel) { model = vm; enabled = true; expanded = false }

    @OptIn(ExperimentalComposeUiApi::class)
    fun onResumed(activity: Activity) {
        if (!enabled || activity is SupportActivity || activity !is ComponentActivity) return
        val vm = model ?: return
        detach()
        host = activity
        val content = activity.window.decorView as ViewGroup
        view = ComposeView(activity).apply {
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WatchRssPhoneTheme {
                    val state by vm.state.collectAsState()
                    if (state.user != null && state.accepted) {
                        var touchX by remember { mutableFloatStateOf(0f) }
                        var touchY by remember { mutableFloatStateOf(0f) }
                        var dragging by remember { mutableStateOf(false) }
                        val slop = ViewConfiguration.get(activity).scaledTouchSlop
                        Box(Modifier.padding(16.dp)) {
                            FloatingActionButton(
                                onClick = { expanded = true },
                                shape = CircleShape,
                                modifier = Modifier.size(56.dp).testTag("support_bubble").pointerInteropFilter { event ->
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_DOWN -> {
                                            snapAnimator?.cancel()
                                            touchX = event.rawX; touchY = event.rawY; dragging = false
                                        }
                                        MotionEvent.ACTION_MOVE -> {
                                            val dx = event.rawX - touchX
                                            val dy = event.rawY - touchY
                                            if (dragging || kotlin.math.hypot(dx, dy) > slop) {
                                                dragging = true
                                                touchX = event.rawX; touchY = event.rawY
                                                placeBubble(dx, dy)
                                            }
                                        }
                                        MotionEvent.ACTION_UP -> if (dragging) snapToEdge() else expanded = true
                                        MotionEvent.ACTION_CANCEL -> if (dragging) snapToEdge()
                                    }
                                    true
                                }
                            ) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "打开客服") }
                        }
                        if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                            Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.86f).widthIn(max = 720.dp).testTag("support_overlay"), shape = MaterialTheme.shapes.extraLarge) {
                                SupportScreen(state, vm, { expanded = false }, {
                                    expanded = false
                                    activity.startActivity(Intent(activity, ContactDeveloperActivity::class.java))
                                }, {
                                    expanded = false
                                    activity.startActivity(Intent(activity, AccountActivity::class.java))
                                })
                            }
                        }
                    }
                }
            }
        }
        layoutHost = content
        content.addOnLayoutChangeListener(layoutListener)
        view!!.addOnLayoutChangeListener(layoutListener)
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT or Gravity.TOP))
        placeBubble()
    }

    fun onPaused(activity: Activity) { if (host === activity) detach() }
    private fun detach() {
        snapAnimator?.cancel()
        snapAnimator = null
        positionX = if (positionX < .5f) 0f else 1f
        expanded = false
        layoutHost?.removeOnLayoutChangeListener(layoutListener)
        view?.let { it.removeOnLayoutChangeListener(layoutListener); (it.parent as? ViewGroup)?.removeView(it) }
        layoutHost = null
        view = null
        host = null
    }
}
