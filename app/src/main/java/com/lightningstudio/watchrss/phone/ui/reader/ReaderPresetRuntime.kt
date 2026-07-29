package com.lightningstudio.watchrss.phone.ui.reader

import android.graphics.Typeface
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundFit
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundType
import com.lightningstudio.watchrss.phone.data.reader.ReaderFontSynthesis
import com.lightningstudio.watchrss.phone.data.reader.ReaderHyphenation
import com.lightningstudio.watchrss.phone.data.reader.ReaderLineBreakMode
import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderRenderMode
import com.lightningstudio.watchrss.phone.data.reader.ReaderTextAlignment
import com.lightningstudio.watchrss.phone.data.reader.ReaderTextStyle
import com.lightningstudio.watchrss.phone.data.reader.ReaderTypographyRole
import java.io.File

enum class ReaderTextRole {
    BODY,
    TITLE,
    SUBTITLE,
    QUOTE,
    CODE,
    LINK
}

data class ReaderPresetRuntime(
    val preset: ReaderPreset,
    val fontFile: (String?) -> File? = { null },
    val backgroundFile: (String?) -> File? = { null }
)

val LocalReaderPresetRuntime = staticCompositionLocalOf {
    ReaderPresetRuntime(ReaderPreset.fallback)
}

@Composable
fun ProvideReaderPreset(
    repository: ReaderPresetRepository,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) {
        repository.setSystemDark(systemDark)
    }
    val preset by repository.activePreset.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalReaderPresetRuntime provides ReaderPresetRuntime(
            preset = preset,
            fontFile = repository::fontFile,
            backgroundFile = repository::backgroundFile
        ),
        content = content
    )
}

@Composable
fun readerTextStyle(role: ReaderTextRole): TextStyle {
    val runtime = LocalReaderPresetRuntime.current
    val preset = runtime.preset
    val spec = when (role) {
        ReaderTextRole.BODY -> preset.body
        ReaderTextRole.TITLE -> preset.resolvedStyle(ReaderTypographyRole.TITLE)
        ReaderTextRole.SUBTITLE -> preset.resolvedStyle(ReaderTypographyRole.SUBTITLE)
        ReaderTextRole.QUOTE -> preset.resolvedStyle(ReaderTypographyRole.QUOTE)
        ReaderTextRole.CODE -> preset.resolvedStyle(ReaderTypographyRole.CODE)
        ReaderTextRole.LINK -> preset.resolvedStyle(ReaderTypographyRole.LINK)
    }
    return spec.toComposeTextStyle(runtime.fontFile)
}

@Composable
fun ReaderBackgroundSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val runtime = LocalReaderPresetRuntime.current
    val background = runtime.preset.background
    val file = runtime.backgroundFile(background.assetId)
    val overlay = Color(background.overlayColorArgb)
        .copy(alpha = background.overlayOpacity)
    Box(
        modifier = modifier
            .background(Color(background.colorArgb))
            .clipToBounds()
    ) {
        when (background.type) {
            ReaderBackgroundType.SOLID -> Unit
            ReaderBackgroundType.IMAGE -> if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = background.fit.contentScale(),
                    alignment = BiasAlignment(
                        horizontalBias = background.focusX * 2f - 1f,
                        verticalBias = background.focusY * 2f - 1f
                    ),
                    colorFilter = ColorFilter.colorMatrix(
                        backgroundColorMatrix(background.brightness, background.saturation)
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = background.zoom
                            scaleY = background.zoom
                            rotationZ = background.rotationDegrees
                        }
                        .blur(background.blurDp.coerceAtLeast(0f).dp)
                )
            }
            ReaderBackgroundType.VIDEO -> if (file != null) {
                ReaderBackgroundVideo(
                    file = file,
                    fit = background.fit,
                    speed = background.videoSpeed,
                    loop = background.loop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = background.zoom
                            scaleY = background.zoom
                            rotationZ = background.rotationDegrees
                            alpha = background.brightness.coerceIn(0f, 1f)
                        }
                        .blur(background.blurDp.coerceAtLeast(0f).dp)
                )
            }
        }
        if (background.overlayOpacity > 0f) {
            Box(Modifier.fillMaxSize().background(overlay))
        }
        content()
    }
}

private fun backgroundColorMatrix(brightness: Float, saturation: Float): ColorMatrix {
    val s = saturation.coerceIn(0f, 2f)
    val b = brightness.coerceIn(0f, 2f)
    val inverse = 1f - s
    val r = 0.213f * inverse
    val g = 0.715f * inverse
    val blue = 0.072f * inverse
    return ColorMatrix(
        floatArrayOf(
            (r + s) * b, g * b, blue * b, 0f, 0f,
            r * b, (g + s) * b, blue * b, 0f, 0f,
            r * b, g * b, (blue + s) * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ReaderBackgroundVideo(
    file: File,
    fit: ReaderBackgroundFit,
    speed: Float,
    loop: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
    val powerSave = remember(context) {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }
    if (reduceMotion || powerSave) return
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = 0f
            playbackParameters = playbackParameters.withSpeed(speed.coerceIn(0.25f, 4f))
            prepare()
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            player.playWhenReady = event == Lifecycle.Event.ON_RESUME
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                resizeMode = fit.resizeMode()
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { view ->
            view.resizeMode = fit.resizeMode()
            view.player = player
        },
        modifier = modifier
    )
}

private fun ReaderTextStyle.toComposeTextStyle(
    fontFile: (String?) -> File?
): TextStyle {
    val family = fontFile(fontAssetId)?.let { file ->
        runCatching {
            val builder = Typeface.Builder(file)
                .setTtcIndex(fontFaceIndex.coerceAtLeast(0))
                .setWeight(fontWeight.coerceIn(1, 1000))
                .setItalic(italic)
            variationSettings.takeIf { it.isNotBlank() }?.let(builder::setFontVariationSettings)
            FontFamily(builder.build())
        }.getOrNull()
    }
    return TextStyle(
        color = Color(colorArgb),
        fontFamily = family,
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight(fontWeight.coerceIn(1, 1000)),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = when {
            underline && strikethrough -> TextDecoration.combine(
                listOf(TextDecoration.Underline, TextDecoration.LineThrough)
            )
            underline -> TextDecoration.Underline
            strikethrough -> TextDecoration.LineThrough
            else -> TextDecoration.None
        },
        fontSynthesis = if (fontSynthesis == ReaderFontSynthesis.DISABLED) {
            FontSynthesis.None
        } else {
            FontSynthesis.All
        },
        lineHeight = lineHeightEm.em,
        letterSpacing = letterSpacingEm.em,
        textAlign = when (alignment) {
            ReaderTextAlignment.START -> TextAlign.Start
            ReaderTextAlignment.CENTER -> TextAlign.Center
            ReaderTextAlignment.JUSTIFY -> TextAlign.Justify
        },
        lineBreak = when (lineBreakMode) {
            ReaderLineBreakMode.SYSTEM -> LineBreak.Unspecified
            ReaderLineBreakMode.SIMPLE -> LineBreak.Simple
            ReaderLineBreakMode.PARAGRAPH -> LineBreak.Paragraph
        },
        hyphens = if (hyphenation == ReaderHyphenation.AUTO) Hyphens.Auto else Hyphens.None,
        textMotion = when (renderMode) {
            ReaderRenderMode.SYSTEM -> null
            ReaderRenderMode.READABILITY -> TextMotion.Static
            ReaderRenderMode.LINEAR_SMOOTH -> TextMotion.Animated
        },
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

private fun ReaderBackgroundFit.contentScale(): ContentScale = when (this) {
    ReaderBackgroundFit.CROP -> ContentScale.Crop
    ReaderBackgroundFit.FIT -> ContentScale.Fit
    ReaderBackgroundFit.FILL -> ContentScale.FillBounds
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ReaderBackgroundFit.resizeMode(): Int = when (this) {
    ReaderBackgroundFit.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    ReaderBackgroundFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    ReaderBackgroundFit.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}
