package com.lightningstudio.watchrss.phone.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ReaderPresetModelsTest {
    @Test
    fun codec_roundTripsAllReaderFields() {
        val source = ReaderPreset(
            id = "stable-id",
            name = " 电影 ",
            body = ReaderTextStyle(
                fontAssetId = "font-hash",
                fontFaceIndex = 2,
                variationSettings = "'wght' 675",
                fontSizeSp = 23f,
                fontWeight = 675,
                italic = true,
                underline = true,
                firstLineIndentEm = 2f,
                renderMode = ReaderRenderMode.LINEAR_SMOOTH,
                fontSynthesis = ReaderFontSynthesis.DISABLED
            ),
            categoryTypographyEnabled = true,
            quote = ReaderTextStyleOverride(strikethrough = true),
            background = ReaderBackground(
                type = ReaderBackgroundType.VIDEO,
                assetId = "video-hash",
                videoTrimStartMs = 20_000,
                videoTrimEndMs = 99_000
            ),
            codeBackgroundColorArgb = 0xFFF1E1B8
        )

        val decoded = ReaderPresetCodec.decode(ReaderPresetCodec.encode(source))

        assertEquals("stable-id", decoded.id)
        assertEquals("电影", decoded.name)
        assertEquals("'wght' 675", decoded.body.variationSettings)
        assertEquals(2, decoded.body.fontFaceIndex)
        assertEquals(ReaderRenderMode.LINEAR_SMOOTH, decoded.body.renderMode)
        assertEquals(ReaderFontSynthesis.DISABLED, decoded.body.fontSynthesis)
        assertTrue(decoded.body.underline)
        assertTrue(decoded.quote.strikethrough == true)
        assertTrue(decoded.categoryTypographyEnabled)
        assertEquals(80_000, decoded.background.videoTrimEndMs)
        assertEquals(0xFFF1E1B8, decoded.codeBackgroundColorArgb)
    }

    @Test
    fun legacyPresetWithoutCodeBackground_usesWarmColorDerivedFromBackground() {
        val backgroundColor = 0xFFF8F3EC
        val json = JSONObject(
            ReaderPresetCodec.encode(
                ReaderPreset.lightDefault(name = "旧浅色预设").copy(
                    background = ReaderBackground(colorArgb = backgroundColor)
                )
            )
        ).apply {
            remove("codeBackgroundColorArgb")
        }

        val decoded = ReaderPresetCodec.decode(json.toString())

        assertEquals(0xFFF2E5C9, decoded.codeBackgroundColorArgb)
        assertEquals(
            defaultReaderCodeBackgroundColorArgb(backgroundColor),
            decoded.codeBackgroundColorArgb
        )
    }

    @Test
    fun categoryTypography_usesDefaultsWhenDisabledAndCustomWhenEnabled() {
        val base = ReaderPreset.darkDefault(name = "测试").copy(
            body = ReaderTextStyle(fontSizeSp = 20f),
            title = ReaderTextStyleOverride(fontScale = 2f, fontWeight = 900)
        )

        val defaultTitle = base.resolvedStyle(ReaderTypographyRole.TITLE)
        val customTitle = base.copy(categoryTypographyEnabled = true)
            .resolvedStyle(ReaderTypographyRole.TITLE)
        val resetTitle = base.copy(
            categoryTypographyEnabled = true,
            title = ReaderTextStyleOverride()
        ).resolvedStyle(ReaderTypographyRole.TITLE)

        assertEquals(31f, defaultTitle.fontSizeSp)
        assertEquals(700, defaultTitle.fontWeight)
        assertEquals(40f, customTitle.fontSizeSp)
        assertEquals(900, customTitle.fontWeight)
        assertEquals(31f, resetTitle.fontSizeSp)
        assertEquals(700, resetTitle.fontWeight)
    }

    @Test
    fun versionOnePresetMigratesWithCategoryTypographyEnabled() {
        val json = JSONObject(
            ReaderPresetCodec.encode(ReaderPreset.darkDefault(name = "旧预设"))
        ).apply {
            put("schemaVersion", 1)
            remove("categoryTypographyEnabled")
        }

        assertTrue(ReaderPresetCodec.decode(json.toString()).categoryTypographyEnabled)
    }

    @Test
    fun roleOverride_inheritsUnspecifiedBodyFields() {
        val body = ReaderTextStyle(fontAssetId = "font", fontSizeSp = 20f, colorArgb = 7)
        val resolved = ReaderTextStyleOverride(fontScale = 1.5f, fontWeight = 700).resolve(body)

        assertEquals("font", resolved.fontAssetId)
        assertEquals(30f, resolved.fontSizeSp)
        assertEquals(7, resolved.colorArgb)
        assertEquals(700, resolved.fontWeight)
    }

    @Test
    fun serializedPresetNeverContainsLocalActivePointer() {
        val raw = ReaderPresetCodec.encode(ReaderPreset.darkDefault(name = "测试"))
        assertFalse(raw.contains("activePresetId"))
        assertFalse(raw.contains("activePresetName"))
    }

    @Test
    fun explicitRoleFontCanClearBodyFont() {
        val resolved = ReaderTextStyleOverride(
            useOwnFont = true,
            fontAssetId = null
        ).resolve(ReaderTextStyle(fontAssetId = "body-font"))

        assertNull(resolved.fontAssetId)
        assertTrue(resolved.fontSizeSp > 0)
    }

    @Test
    fun deletingFontClearsBodyAndRoleReferences() {
        val preset = ReaderPreset.darkDefault(name = "测试").copy(
            body = ReaderTextStyle(
                fontAssetId = "removed-font",
                fontFaceIndex = 3,
                variationSettings = "'wght' 640"
            ),
            title = ReaderTextStyleOverride(
                useOwnFont = true,
                fontAssetId = "removed-font",
                fontFaceIndex = 2
            ),
            code = ReaderTextStyleOverride(
                useOwnFont = true,
                fontAssetId = "kept-font"
            )
        )

        assertTrue(preset.referencesFont("removed-font"))
        val cleared = preset.withoutFont("removed-font")

        assertNull(cleared.body.fontAssetId)
        assertEquals(0, cleared.body.fontFaceIndex)
        assertEquals("", cleared.body.variationSettings)
        assertFalse(cleared.title.useOwnFont)
        assertNull(cleared.title.fontAssetId)
        assertEquals("kept-font", cleared.code.fontAssetId)
    }
}
