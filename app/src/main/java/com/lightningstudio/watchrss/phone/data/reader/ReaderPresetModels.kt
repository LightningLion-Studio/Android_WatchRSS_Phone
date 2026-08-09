package com.lightningstudio.watchrss.phone.data.reader

import org.json.JSONObject
import java.util.UUID

enum class ReaderRenderMode {
    SYSTEM,
    READABILITY,
    LINEAR_SMOOTH
}

enum class ReaderFontSynthesis {
    ENABLED,
    DISABLED
}

enum class ReaderTextAlignment {
    START,
    CENTER,
    JUSTIFY
}

enum class ReaderLineBreakMode {
    SYSTEM,
    SIMPLE,
    PARAGRAPH
}

enum class ReaderHyphenation {
    NONE,
    AUTO
}

enum class ReaderBackgroundType {
    SOLID,
    IMAGE,
    VIDEO
}

enum class ReaderBackgroundFit {
    CROP,
    FIT,
    FILL
}

enum class ReaderTypographyRole {
    TITLE,
    SUBTITLE,
    QUOTE,
    CODE,
    LINK
}

data class ReaderTextStyle(
    val fontAssetId: String? = null,
    val fontFaceIndex: Int = 0,
    val variationSettings: String = "",
    val fontSizeSp: Float = 18f,
    val fontWeight: Int = 400,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val colorArgb: Long = 0xFFF2F2F2,
    val lineHeightEm: Float = 1.45f,
    val letterSpacingEm: Float = 0f,
    val paragraphSpacingDp: Float = 8f,
    val firstLineIndentEm: Float = 0f,
    val horizontalPaddingDp: Float = 20f,
    val alignment: ReaderTextAlignment = ReaderTextAlignment.START,
    val lineBreakMode: ReaderLineBreakMode = ReaderLineBreakMode.PARAGRAPH,
    val hyphenation: ReaderHyphenation = ReaderHyphenation.NONE,
    val renderMode: ReaderRenderMode = ReaderRenderMode.READABILITY,
    val fontSynthesis: ReaderFontSynthesis = ReaderFontSynthesis.ENABLED
)

data class ReaderTextStyleOverride(
    val fontAssetId: String? = null,
    val useOwnFont: Boolean = false,
    val fontFaceIndex: Int? = null,
    val variationSettings: String? = null,
    val fontSizeSp: Float? = null,
    val fontScale: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val colorArgb: Long? = null,
    val lineHeightEm: Float? = null,
    val letterSpacingEm: Float? = null,
    val alignment: ReaderTextAlignment? = null
) {
    fun resolve(base: ReaderTextStyle): ReaderTextStyle = base.copy(
        fontAssetId = if (useOwnFont) fontAssetId else base.fontAssetId,
        fontFaceIndex = fontFaceIndex ?: base.fontFaceIndex,
        variationSettings = variationSettings ?: base.variationSettings,
        fontSizeSp = fontSizeSp ?: (fontScale?.let { base.fontSizeSp * it } ?: base.fontSizeSp),
        fontWeight = fontWeight ?: base.fontWeight,
        italic = italic ?: base.italic,
        underline = underline ?: base.underline,
        strikethrough = strikethrough ?: base.strikethrough,
        colorArgb = colorArgb ?: base.colorArgb,
        lineHeightEm = lineHeightEm ?: base.lineHeightEm,
        letterSpacingEm = letterSpacingEm ?: base.letterSpacingEm,
        alignment = alignment ?: base.alignment
    )

    fun resolve(
        base: ReaderTextStyle,
        categoryDefault: ReaderTextStyleOverride
    ): ReaderTextStyle {
        val categoryStyle = categoryDefault.resolve(base)
        return categoryStyle.copy(
            fontAssetId = if (useOwnFont) fontAssetId else categoryStyle.fontAssetId,
            fontFaceIndex = fontFaceIndex ?: categoryStyle.fontFaceIndex,
            variationSettings = variationSettings ?: categoryStyle.variationSettings,
            fontSizeSp = fontSizeSp
                ?: fontScale?.let { base.fontSizeSp * it }
                ?: categoryStyle.fontSizeSp,
            fontWeight = fontWeight ?: categoryStyle.fontWeight,
            italic = italic ?: categoryStyle.italic,
            underline = underline ?: categoryStyle.underline,
            strikethrough = strikethrough ?: categoryStyle.strikethrough,
            colorArgb = colorArgb ?: categoryStyle.colorArgb,
            lineHeightEm = lineHeightEm ?: categoryStyle.lineHeightEm,
            letterSpacingEm = letterSpacingEm ?: categoryStyle.letterSpacingEm,
            alignment = alignment ?: categoryStyle.alignment
        )
    }

    fun normalized(): ReaderTextStyleOverride = copy(
        fontFaceIndex = fontFaceIndex?.coerceAtLeast(0),
        fontSizeSp = fontSizeSp?.coerceIn(10f, 64f),
        fontScale = fontScale?.coerceIn(0.5f, 2.5f),
        fontWeight = fontWeight?.coerceIn(100, 900),
        lineHeightEm = lineHeightEm?.coerceIn(0.8f, 3f),
        letterSpacingEm = letterSpacingEm?.coerceIn(-0.1f, 0.5f)
    )
}

data class ReaderBackground(
    val type: ReaderBackgroundType = ReaderBackgroundType.SOLID,
    val colorArgb: Long = 0xFF000000,
    val assetId: String? = null,
    val fit: ReaderBackgroundFit = ReaderBackgroundFit.CROP,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
    val rotationDegrees: Float = 0f,
    val blurDp: Float = 0f,
    val brightness: Float = 1f,
    val saturation: Float = 1f,
    val overlayColorArgb: Long = 0xFF000000,
    val overlayOpacity: Float = 0f,
    val videoTrimStartMs: Long = 0L,
    val videoTrimEndMs: Long = 60_000L,
    val videoSpeed: Float = 1f,
    val loop: Boolean = true,
    val posterAssetId: String? = null
)

data class ReaderPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val body: ReaderTextStyle = ReaderTextStyle(),
    val categoryTypographyEnabled: Boolean = false,
    val title: ReaderTextStyleOverride = ReaderTextStyleOverride(),
    val subtitle: ReaderTextStyleOverride = ReaderTextStyleOverride(),
    val quote: ReaderTextStyleOverride = ReaderTextStyleOverride(),
    val code: ReaderTextStyleOverride = ReaderTextStyleOverride(),
    val link: ReaderTextStyleOverride = ReaderTextStyleOverride(),
    val background: ReaderBackground = ReaderBackground(),
    val codeBackgroundColorArgb: Long = defaultReaderCodeBackgroundColorArgb(0xFF000000),
    val accentColorArgb: Long = 0xFFFF5A36,
    val updatedAt: Long = System.currentTimeMillis(),
    val modifiedBy: String = "",
    val deleted: Boolean = false
) {
    fun normalized(): ReaderPreset = copy(
        name = name.trim().take(MAX_PRESET_NAME_LENGTH),
        body = body.copy(
            fontSizeSp = body.fontSizeSp.coerceIn(10f, 64f),
            fontWeight = body.fontWeight.coerceIn(100, 900),
            lineHeightEm = body.lineHeightEm.coerceIn(0.8f, 3f),
            letterSpacingEm = body.letterSpacingEm.coerceIn(-0.1f, 0.5f),
            paragraphSpacingDp = body.paragraphSpacingDp.coerceIn(0f, 64f),
            firstLineIndentEm = body.firstLineIndentEm.coerceIn(0f, 4f),
            horizontalPaddingDp = body.horizontalPaddingDp.coerceIn(0f, 64f)
        ),
        title = title.normalized(),
        subtitle = subtitle.normalized(),
        quote = quote.normalized(),
        code = code.normalized(),
        link = link.normalized(),
        background = background.copy(
            focusX = background.focusX.coerceIn(0f, 1f),
            focusY = background.focusY.coerceIn(0f, 1f),
            zoom = background.zoom.coerceIn(0.25f, 8f),
            rotationDegrees = background.rotationDegrees.coerceIn(-180f, 180f),
            blurDp = background.blurDp.coerceIn(0f, 64f),
            brightness = background.brightness.coerceIn(0f, 2f),
            saturation = background.saturation.coerceIn(0f, 2f),
            overlayOpacity = background.overlayOpacity.coerceIn(0f, 1f),
            videoTrimStartMs = background.videoTrimStartMs.coerceAtLeast(0L),
            videoTrimEndMs = background.videoTrimEndMs
                .coerceAtLeast(background.videoTrimStartMs)
                .coerceAtMost(background.videoTrimStartMs + MAX_VIDEO_SEGMENT_MS),
            videoSpeed = background.videoSpeed.coerceIn(0.25f, 4f)
        )
    )

    fun categoryDefault(role: ReaderTypographyRole): ReaderTextStyleOverride = when (role) {
        ReaderTypographyRole.TITLE -> ReaderTextStyleOverride(
            fontScale = 1.55f,
            fontWeight = 700
        )
        ReaderTypographyRole.SUBTITLE -> ReaderTextStyleOverride(
            fontScale = 1.12f,
            colorArgb = body.colorArgb.withAlpha(0xCB)
        )
        ReaderTypographyRole.QUOTE -> ReaderTextStyleOverride(
            fontScale = 1f,
            colorArgb = body.colorArgb.withAlpha(0xD0)
        )
        ReaderTypographyRole.CODE -> ReaderTextStyleOverride(fontScale = 0.9f)
        ReaderTypographyRole.LINK -> ReaderTextStyleOverride(
            fontScale = 1f,
            colorArgb = accentColorArgb
        )
    }

    fun resolvedStyle(role: ReaderTypographyRole): ReaderTextStyle {
        val defaults = categoryDefault(role)
        val custom = when (role) {
            ReaderTypographyRole.TITLE -> title
            ReaderTypographyRole.SUBTITLE -> subtitle
            ReaderTypographyRole.QUOTE -> quote
            ReaderTypographyRole.CODE -> code
            ReaderTypographyRole.LINK -> link
        }
        return if (categoryTypographyEnabled) {
            custom.resolve(body, defaults)
        } else {
            defaults.resolve(body)
        }
    }

    companion object {
        const val MAX_PRESET_NAME_LENGTH = 40
        const val MAX_VIDEO_SEGMENT_MS = 60_000L
        const val FALLBACK_ID = "reader-system-fallback"

        fun darkDefault(
            id: String = UUID.randomUUID().toString(),
            name: String = "默认深色",
            fontSizeSp: Float = 18f
        ) = ReaderPreset(
            id = id,
            name = name,
            body = ReaderTextStyle(fontSizeSp = fontSizeSp),
            background = ReaderBackground(colorArgb = 0xFF000000)
        )

        fun lightDefault(
            id: String = UUID.randomUUID().toString(),
            name: String = "默认浅色",
            fontSizeSp: Float = 18f
        ) = ReaderPreset(
            id = id,
            name = name,
            body = ReaderTextStyle(
                fontSizeSp = fontSizeSp,
                colorArgb = 0xFF221F1B
            ),
            background = ReaderBackground(colorArgb = 0xFFF8F3EC),
            codeBackgroundColorArgb = defaultReaderCodeBackgroundColorArgb(0xFFF8F3EC),
            accentColorArgb = 0xFFD94720
        )

        val fallback: ReaderPreset = darkDefault(
            id = FALLBACK_ID,
            name = "安全默认"
        )
    }
}

/**
 * Keeps code surfaces close to the reader background while adding a restrained
 * warm-yellow cast. The result is opaque so code blocks remain solid even over
 * image and video reader backgrounds.
 */
fun defaultReaderCodeBackgroundColorArgb(backgroundColorArgb: Long): Long {
    val warmYellow = 0xFFD8A52BL
    fun blendChannel(shift: Int): Long {
        val background = (backgroundColorArgb shr shift) and 0xFF
        val warm = (warmYellow shr shift) and 0xFF
        return (background * 82 + warm * 18 + 50) / 100
    }
    return 0xFF000000L or
        (blendChannel(16) shl 16) or
        (blendChannel(8) shl 8) or
        blendChannel(0)
}

object ReaderPresetCodec {
    const val SCHEMA_VERSION = 2

    fun encode(preset: ReaderPreset): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", preset.id)
        put("name", preset.name)
        put("body", preset.body.toJson())
        put("categoryTypographyEnabled", preset.categoryTypographyEnabled)
        put("title", preset.title.toJson())
        put("subtitle", preset.subtitle.toJson())
        put("quote", preset.quote.toJson())
        put("code", preset.code.toJson())
        put("link", preset.link.toJson())
        put("background", preset.background.toJson())
        put("codeBackgroundColorArgb", preset.codeBackgroundColorArgb)
        put("accentColorArgb", preset.accentColorArgb)
        put("updatedAt", preset.updatedAt)
        put("modifiedBy", preset.modifiedBy)
        put("deleted", preset.deleted)
    }.toString()

    fun decode(raw: String): ReaderPreset {
        val json = JSONObject(raw)
        val schemaVersion = json.optInt("schemaVersion", 1)
        require(schemaVersion <= SCHEMA_VERSION) {
            "不支持的阅读器预设版本"
        }
        val background = json.optJSONObject("background").toBackground()
        return ReaderPreset(
            id = json.getString("id"),
            name = json.getString("name"),
            body = json.optJSONObject("body").toTextStyle(),
            categoryTypographyEnabled = if (schemaVersion < 2) {
                true
            } else {
                json.optBoolean("categoryTypographyEnabled")
            },
            title = json.optJSONObject("title").toOverride(),
            subtitle = json.optJSONObject("subtitle").toOverride(),
            quote = json.optJSONObject("quote").toOverride(),
            code = json.optJSONObject("code").toOverride(),
            link = json.optJSONObject("link").toOverride(),
            background = background,
            codeBackgroundColorArgb = json.optLong(
                "codeBackgroundColorArgb",
                defaultReaderCodeBackgroundColorArgb(background.colorArgb)
            ),
            accentColorArgb = json.optLong("accentColorArgb", 0xFFFF5A36),
            updatedAt = json.optLong("updatedAt"),
            modifiedBy = json.optString("modifiedBy"),
            deleted = json.optBoolean("deleted")
        ).normalized()
    }
}

private fun Long.withAlpha(alpha: Int): Long =
    (this and 0x00FFFFFFL) or ((alpha.coerceIn(0, 255).toLong()) shl 24)

private fun ReaderTextStyle.toJson() = JSONObject().apply {
    putNullable("fontAssetId", fontAssetId)
    put("fontFaceIndex", fontFaceIndex)
    put("variationSettings", variationSettings)
    put("fontSizeSp", fontSizeSp.toDouble())
    put("fontWeight", fontWeight)
    put("italic", italic)
    put("underline", underline)
    put("strikethrough", strikethrough)
    put("colorArgb", colorArgb)
    put("lineHeightEm", lineHeightEm.toDouble())
    put("letterSpacingEm", letterSpacingEm.toDouble())
    put("paragraphSpacingDp", paragraphSpacingDp.toDouble())
    put("firstLineIndentEm", firstLineIndentEm.toDouble())
    put("horizontalPaddingDp", horizontalPaddingDp.toDouble())
    put("alignment", alignment.name)
    put("lineBreakMode", lineBreakMode.name)
    put("hyphenation", hyphenation.name)
    put("renderMode", renderMode.name)
    put("fontSynthesis", fontSynthesis.name)
}

private fun JSONObject?.toTextStyle(): ReaderTextStyle {
    val json = this ?: return ReaderTextStyle()
    return ReaderTextStyle(
        fontAssetId = json.nullableString("fontAssetId"),
        fontFaceIndex = json.optInt("fontFaceIndex"),
        variationSettings = json.optString("variationSettings"),
        fontSizeSp = json.optDouble("fontSizeSp", 18.0).toFloat(),
        fontWeight = json.optInt("fontWeight", 400),
        italic = json.optBoolean("italic"),
        underline = json.optBoolean("underline"),
        strikethrough = json.optBoolean("strikethrough"),
        colorArgb = json.optLong("colorArgb", 0xFFF2F2F2),
        lineHeightEm = json.optDouble("lineHeightEm", 1.45).toFloat(),
        letterSpacingEm = json.optDouble("letterSpacingEm", 0.0).toFloat(),
        paragraphSpacingDp = json.optDouble("paragraphSpacingDp", 8.0).toFloat(),
        firstLineIndentEm = json.optDouble("firstLineIndentEm", 0.0).toFloat(),
        horizontalPaddingDp = json.optDouble("horizontalPaddingDp", 20.0).toFloat(),
        alignment = json.enumValue("alignment", ReaderTextAlignment.START),
        lineBreakMode = json.enumValue("lineBreakMode", ReaderLineBreakMode.PARAGRAPH),
        hyphenation = json.enumValue("hyphenation", ReaderHyphenation.NONE),
        renderMode = json.enumValue("renderMode", ReaderRenderMode.READABILITY),
        fontSynthesis = json.enumValue("fontSynthesis", ReaderFontSynthesis.ENABLED)
    )
}

private fun ReaderTextStyleOverride.toJson() = JSONObject().apply {
    putNullable("fontAssetId", fontAssetId)
    put("useOwnFont", useOwnFont)
    putNullable("fontFaceIndex", fontFaceIndex)
    putNullable("variationSettings", variationSettings)
    putNullable("fontSizeSp", fontSizeSp?.toDouble())
    putNullable("fontScale", fontScale?.toDouble())
    putNullable("fontWeight", fontWeight)
    putNullable("italic", italic)
    putNullable("underline", underline)
    putNullable("strikethrough", strikethrough)
    putNullable("colorArgb", colorArgb)
    putNullable("lineHeightEm", lineHeightEm?.toDouble())
    putNullable("letterSpacingEm", letterSpacingEm?.toDouble())
    putNullable("alignment", alignment?.name)
}

private fun JSONObject?.toOverride(): ReaderTextStyleOverride {
    val json = this ?: return ReaderTextStyleOverride()
    return ReaderTextStyleOverride(
        fontAssetId = json.nullableString("fontAssetId"),
        useOwnFont = json.optBoolean("useOwnFont"),
        fontFaceIndex = json.nullableInt("fontFaceIndex"),
        variationSettings = json.nullableString("variationSettings"),
        fontSizeSp = json.nullableDouble("fontSizeSp")?.toFloat(),
        fontScale = json.nullableDouble("fontScale")?.toFloat(),
        fontWeight = json.nullableInt("fontWeight"),
        italic = json.nullableBoolean("italic"),
        underline = json.nullableBoolean("underline"),
        strikethrough = json.nullableBoolean("strikethrough"),
        colorArgb = json.nullableLong("colorArgb"),
        lineHeightEm = json.nullableDouble("lineHeightEm")?.toFloat(),
        letterSpacingEm = json.nullableDouble("letterSpacingEm")?.toFloat(),
        alignment = json.nullableString("alignment")?.let {
            runCatching { ReaderTextAlignment.valueOf(it) }.getOrNull()
        }
    )
}

private fun ReaderBackground.toJson() = JSONObject().apply {
    put("type", type.name)
    put("colorArgb", colorArgb)
    putNullable("assetId", assetId)
    put("fit", fit.name)
    put("focusX", focusX.toDouble())
    put("focusY", focusY.toDouble())
    put("zoom", zoom.toDouble())
    put("rotationDegrees", rotationDegrees.toDouble())
    put("blurDp", blurDp.toDouble())
    put("brightness", brightness.toDouble())
    put("saturation", saturation.toDouble())
    put("overlayColorArgb", overlayColorArgb)
    put("overlayOpacity", overlayOpacity.toDouble())
    put("videoTrimStartMs", videoTrimStartMs)
    put("videoTrimEndMs", videoTrimEndMs)
    put("videoSpeed", videoSpeed.toDouble())
    put("loop", loop)
    putNullable("posterAssetId", posterAssetId)
}

private fun JSONObject?.toBackground(): ReaderBackground {
    val json = this ?: return ReaderBackground()
    return ReaderBackground(
        type = json.enumValue("type", ReaderBackgroundType.SOLID),
        colorArgb = json.optLong("colorArgb", 0xFF000000),
        assetId = json.nullableString("assetId"),
        fit = json.enumValue("fit", ReaderBackgroundFit.CROP),
        focusX = json.optDouble("focusX", 0.5).toFloat(),
        focusY = json.optDouble("focusY", 0.5).toFloat(),
        zoom = json.optDouble("zoom", 1.0).toFloat(),
        rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat(),
        blurDp = json.optDouble("blurDp", 0.0).toFloat(),
        brightness = json.optDouble("brightness", 1.0).toFloat(),
        saturation = json.optDouble("saturation", 1.0).toFloat(),
        overlayColorArgb = json.optLong("overlayColorArgb", 0xFF000000),
        overlayOpacity = json.optDouble("overlayOpacity", 0.0).toFloat(),
        videoTrimStartMs = json.optLong("videoTrimStartMs"),
        videoTrimEndMs = json.optLong("videoTrimEndMs", 60_000L),
        videoSpeed = json.optDouble("videoSpeed", 1.0).toFloat(),
        loop = json.optBoolean("loop", true),
        posterAssetId = json.nullableString("posterAssetId")
    )
}

private fun JSONObject.putNullable(name: String, value: Any?) {
    put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else getLong(name)

private fun JSONObject.nullableDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else getDouble(name)

private fun JSONObject.nullableBoolean(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else getBoolean(name)

private inline fun <reified T : Enum<T>> JSONObject.enumValue(name: String, fallback: T): T =
    runCatching { enumValueOf<T>(optString(name)) }.getOrDefault(fallback)
