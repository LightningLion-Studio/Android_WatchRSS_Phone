package com.lightningstudio.watchrss.phone.data.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ReaderPresetPackageArchiveTest {
    @Test
    fun singlePackage_roundTripsPresetAndReferencedResources() = withTemporaryDirectory { root ->
        val fontBytes = "font-data".toByteArray()
        val titleFontBytes = "title-font-data".toByteArray()
        val backgroundBytes = "background-data".toByteArray()
        val variantBytes = "watch-variant".toByteArray()
        val fontHash = sha256(fontBytes)
        val titleFontHash = sha256(titleFontBytes)
        val backgroundHash = sha256(backgroundBytes)
        val variantHash = sha256(variantBytes)
        val fontFile = root.write("$fontHash.ttf", fontBytes)
        val titleFontFile = root.write("$titleFontHash.otf", titleFontBytes)
        val backgroundFile = root.write("$backgroundHash.png", backgroundBytes)
        val variantFile = root.write("$variantHash.webp", variantBytes)
        val preset = ReaderPreset.darkDefault(id = "preset-one", name = "便携预设").copy(
            body = ReaderTextStyle(fontAssetId = fontHash),
            title = ReaderTextStyleOverride(
                fontAssetId = titleFontHash,
                useOwnFont = true
            ),
            background = ReaderBackground(
                type = ReaderBackgroundType.IMAGE,
                assetId = backgroundHash
            )
        )
        val font = ReaderFontAssetEntity(
            id = fontHash,
            sha256 = fontHash,
            displayName = "测试字体",
            familyName = "Test",
            fileName = fontFile.name,
            mimeType = "font/ttf",
            byteCount = fontFile.length(),
            faceCount = 1,
            metadataJson = "{}",
            updatedAt = 1L,
            modifiedBy = "source",
            deleted = false
        )
        val titleFont = font.copy(
            id = titleFontHash,
            sha256 = titleFontHash,
            displayName = "测试标题字体",
            familyName = "Title Test",
            fileName = titleFontFile.name,
            byteCount = titleFontFile.length()
        )
        val background = ReaderBackgroundAssetEntity(
            id = backgroundHash,
            sha256 = backgroundHash,
            displayName = "测试背景",
            kind = ReaderBackgroundType.IMAGE.name,
            mimeType = "image/png",
            masterFileName = backgroundFile.name,
            byteCount = backgroundFile.length(),
            durationMs = 0L,
            width = 100,
            height = 100,
            posterAssetId = null,
            variantsJson = JSONObject().put(
                "watch",
                JSONObject()
                    .put("fileName", variantFile.name)
                    .put("sha256", variantHash)
                    .put("byteCount", variantFile.length())
            ).toString(),
            updatedAt = 2L,
            modifiedBy = "source",
            deleted = false
        )
        val payload = ReaderPresetPackagePayload(
            scope = ReaderPresetPackageScope.SINGLE,
            snapshot = ReaderPresetSnapshot(
                presets = listOf(preset.toEntity()),
                fonts = listOf(font, titleFont),
                backgrounds = listOf(background),
                deletions = emptyList()
            ),
            resources = listOf(
                ReaderPresetPackageResource("font", font.fileName, fontHash, font.byteCount, fontFile),
                // A repeated descriptor must still be emitted only once by content hash.
                ReaderPresetPackageResource("font", font.fileName, fontHash, font.byteCount, fontFile),
                ReaderPresetPackageResource(
                    "font",
                    titleFont.fileName,
                    titleFontHash,
                    titleFont.byteCount,
                    titleFontFile
                ),
                ReaderPresetPackageResource(
                    "background",
                    background.masterFileName,
                    backgroundHash,
                    background.byteCount,
                    backgroundFile
                ),
                ReaderPresetPackageResource(
                    "variant",
                    variantFile.name,
                    variantHash,
                    variantFile.length(),
                    variantFile
                )
            ),
            exportedAt = 10L,
            appVersion = "test"
        )

        val bytes = write(payload)
        val restored = ReaderPresetPackageArchive.read(
            ByteArrayInputStream(bytes),
            File(root, "staging")
        )

        assertEquals(ReaderPresetPackageScope.SINGLE, restored.scope)
        assertEquals(listOf("preset-one"), restored.snapshot.presets.map { it.id })
        assertEquals(listOf(fontHash, titleFontHash), restored.snapshot.fonts.map { it.id })
        assertEquals(listOf(backgroundHash), restored.snapshot.backgrounds.map { it.id })
        assertEquals(4, restored.resources.size)
        assertTrue(restored.resources.all { it.sourceFile.isFile })
    }

    @Test
    fun libraryPackage_roundTripsMultiplePresets() = withTemporaryDirectory { root ->
        val payload = ReaderPresetPackagePayload(
            scope = ReaderPresetPackageScope.LIBRARY,
            snapshot = ReaderPresetSnapshot(
                presets = listOf(
                    ReaderPreset.darkDefault(id = "dark", name = "深色").toEntity(),
                    ReaderPreset.lightDefault(id = "light", name = "浅色").toEntity()
                ),
                fonts = emptyList(),
                backgrounds = emptyList(),
                deletions = emptyList()
            ),
            resources = emptyList(),
            exportedAt = 1L,
            appVersion = "test"
        )

        val restored = ReaderPresetPackageArchive.read(
            ByteArrayInputStream(write(payload)),
            File(root, "library")
        )

        assertEquals(ReaderPresetPackageScope.LIBRARY, restored.scope)
        assertEquals(setOf("dark", "light"), restored.snapshot.presets.mapTo(hashSetOf()) { it.id })
    }

    @Test
    fun legacyJson_isAcceptedWithoutEmbeddedResources() {
        val preset = ReaderPreset.darkDefault(id = "legacy-id", name = "旧预设").copy(
            body = ReaderTextStyle(fontAssetId = "missing-font")
        )

        val payload = ReaderPresetPackageArchive.readLegacyJson(ReaderPresetCodec.encode(preset))

        assertTrue(payload.legacyJson)
        assertEquals(ReaderPresetPackageScope.SINGLE, payload.scope)
        assertEquals("legacy-id", payload.snapshot.presets.single().id)
        assertTrue(payload.resources.isEmpty())
        assertTrue(payload.warnings.isNotEmpty())
    }

    @Test
    fun archive_rejectsFutureVersion() = withTemporaryDirectory { root ->
        val bytes = minimalPackage(root)
        val changed = rewriteZip(bytes) { name, content ->
            if (name == "manifest.json") {
                JSONObject(content.toString(Charsets.UTF_8))
                    .put("version", ReaderPresetPackageArchive.CURRENT_VERSION + 1)
                    .toString()
                    .toByteArray()
            } else {
                content
            }
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(ByteArrayInputStream(changed), File(root, "future"))
        }
        assertTrue(error.message.orEmpty().contains("版本过高"))
    }

    @Test
    fun archive_rejectsUnknownOrUnsafeEntry() = withTemporaryDirectory { root ->
        val bytes = minimalPackage(root)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("../outside"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(
                ByteArrayInputStream(output.toByteArray()),
                File(root, "unsafe")
            )
        }
        assertFalse(File(root.parentFile, "outside").exists())
    }

    @Test
    fun archive_rejectsDuplicateEntry() = withTemporaryDirectory { root ->
        val bytes = rawStoredZip(
            listOf(
                "manifest.json" to "{}".toByteArray(),
                "manifest.json" to "{}".toByteArray()
            )
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(ByteArrayInputStream(bytes), File(root, "duplicate"))
        }
        assertTrue(error.message.orEmpty().contains("重复条目"))
    }

    @Test
    fun archive_rejectsResourceDeclaredOverLimit() = withTemporaryDirectory { root ->
        val bytes = minimalPackage(root, withFont = true)
        val changed = rewriteZip(bytes) { name, content ->
            if (name == "manifest.json") {
                JSONObject(content.toString(Charsets.UTF_8)).also { manifest ->
                    manifest.getJSONArray("resources").getJSONObject(0)
                        .put("byteCount", 512L * 1024L * 1024L + 1L)
                }.toString().toByteArray()
            } else {
                content
            }
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(ByteArrayInputStream(changed), File(root, "oversize"))
        }
        assertTrue(error.message.orEmpty().contains("资源大小无效"))
    }

    @Test
    fun archive_rejectsTamperedResource() = withTemporaryDirectory { root ->
        val bytes = minimalPackage(root, withFont = true)
        val changed = rewriteZip(bytes) { name, content ->
            if (name.startsWith("resources/")) "tampered".toByteArray() else content
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(ByteArrayInputStream(changed), File(root, "tampered"))
        }
        assertTrue(error.message.orEmpty().contains("哈希不匹配"))
    }

    @Test
    fun archive_rejectsTamperedReaderMetadata() = withTemporaryDirectory { root ->
        val bytes = minimalPackage(root)
        val changed = rewriteZip(bytes) { name, content ->
            if (name == "reader.json") {
                content.copyOf().also { tampered ->
                    tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
                }
            } else {
                content
            }
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(
                ByteArrayInputStream(changed),
                File(root, "tampered-reader")
            )
        }
        assertTrue(error.message.orEmpty().contains("reader.json 哈希不匹配"))
    }

    @Test
    fun archive_rejectsMissingReferencedResource() = withTemporaryDirectory { root ->
        val bytes = minimalPackage(root, withFont = true)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.name.startsWith("resources/")) {
                        zip.putNextEntry(ZipEntry(entry.name))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            ReaderPresetPackageArchive.read(
                ByteArrayInputStream(output.toByteArray()),
                File(root, "missing")
            )
        }
        assertTrue(error.message.orEmpty().contains("缺少资源"))
    }

    @Test
    fun fingerprint_includesSelectionButIgnoresListOrder() {
        val first = ReaderPreset.darkDefault(id = "a", name = "A").toEntity()
        val second = ReaderPreset.lightDefault(id = "b", name = "B").toEntity()
        val selection = ReaderPresetSelection(ReaderThemeMode.SYSTEM, "b", "a", false)
        val forward = ReaderPresetSnapshot(listOf(first, second), emptyList(), emptyList(), emptyList())
        val reverse = ReaderPresetSnapshot(listOf(second, first), emptyList(), emptyList(), emptyList())

        assertEquals(
            ReaderPresetSnapshotCodec.fingerprint(forward, selection),
            ReaderPresetSnapshotCodec.fingerprint(reverse, selection)
        )
        assertFalse(
            ReaderPresetSnapshotCodec.fingerprint(forward, selection) ==
                ReaderPresetSnapshotCodec.fingerprint(
                    forward,
                    selection.copy(lightPresetId = "a")
                )
        )
    }

    private fun minimalPackage(root: File, withFont: Boolean = false): ByteArray {
        val fontBytes = "font".toByteArray()
        val fontHash = sha256(fontBytes)
        val fontFile = root.write("$fontHash.ttf", fontBytes)
        val preset = ReaderPreset.darkDefault(id = "preset", name = "测试").copy(
            body = ReaderTextStyle(fontAssetId = fontHash.takeIf { withFont })
        )
        val fonts = if (withFont) {
            listOf(
                ReaderFontAssetEntity(
                    fontHash,
                    fontHash,
                    "字体",
                    "Font",
                    fontFile.name,
                    "font/ttf",
                    fontFile.length(),
                    1,
                    "{}",
                    1L,
                    "source",
                    false
                )
            )
        } else {
            emptyList()
        }
        val resources = if (withFont) {
            listOf(
                ReaderPresetPackageResource(
                    "font",
                    fontFile.name,
                    fontHash,
                    fontFile.length(),
                    fontFile
                )
            )
        } else {
            emptyList()
        }
        return write(
            ReaderPresetPackagePayload(
                ReaderPresetPackageScope.SINGLE,
                ReaderPresetSnapshot(listOf(preset.toEntity()), fonts, emptyList(), emptyList()),
                resources,
                1L,
                "test"
            )
        )
    }

    private fun write(payload: ReaderPresetPackagePayload): ByteArray =
        ByteArrayOutputStream().also { ReaderPresetPackageArchive.write(payload, it) }.toByteArray()

    private fun rewriteZip(
        source: ByteArray,
        transform: (String, ByteArray) -> ByteArray
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(ByteArrayInputStream(source)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val content = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(transform(entry.name, content))
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    /** Minimal stored ZIP stream; a central directory is unnecessary for ZipInputStream. */
    private fun rawStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            entries.forEach { (name, content) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val crc = CRC32().also { it.update(content) }.value
                output.writeLeInt(0x04034b50)
                output.writeLeShort(20)
                output.writeLeShort(0)
                output.writeLeShort(0)
                output.writeLeShort(0)
                output.writeLeShort(0)
                output.writeLeInt(crc)
                output.writeLeInt(content.size.toLong())
                output.writeLeInt(content.size.toLong())
                output.writeLeShort(nameBytes.size)
                output.writeLeShort(0)
                output.write(nameBytes)
                output.write(content)
            }
        }.toByteArray()

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Long) {
        repeat(4) { byte -> write((value ushr (byte * 8)).toInt() and 0xff) }
    }

    private inline fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val directory = Files.createTempDirectory("reader-preset-package-test").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun File.write(name: String, bytes: ByteArray): File =
        resolve(name).also { it.writeBytes(bytes) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
