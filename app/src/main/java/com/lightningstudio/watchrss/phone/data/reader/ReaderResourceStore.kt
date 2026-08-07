package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportedReaderFont(
    val sha256: String,
    val displayName: String,
    val familyName: String,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
    val faceCount: Int,
    val metadataJson: String
)

data class SystemReaderFont(
    val filePath: String,
    val displayName: String,
    val familyName: String,
    val styles: String,
    val fileName: String,
    val byteCount: Long,
    val faceCount: Int,
    val sourceLabel: String = "Android 系统",
    val packageName: String? = null,
    val assetPath: String? = null
)

data class ImportedReaderBackground(
    val sha256: String,
    val displayName: String,
    val kind: ReaderBackgroundType,
    val mimeType: String,
    val fileName: String,
    val byteCount: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int
)

class ReaderResourceStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT_DIRECTORY)
    private val fonts = File(root, FONT_DIRECTORY)
    private val backgrounds = File(root, BACKGROUND_DIRECTORY)
    private val variants = File(root, VARIANT_DIRECTORY)
    private val systemFontCache = File(appContext.cacheDir, SYSTEM_FONT_CACHE_DIRECTORY)

    init {
        fonts.mkdirs()
        backgrounds.mkdirs()
        variants.mkdirs()
        systemFontCache.mkdirs()
    }

    suspend fun importFont(uri: Uri): ImportedReaderFont = withContext(Dispatchers.IO) {
        val displayName = displayName(uri, "字体")
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_FONT_EXTENSIONS) { "仅支持 TTF、OTF、TTC 字体" }
        importFontSource(
            displayName = displayName,
            extension = extension,
            mimeType = appContext.contentResolver.getType(uri)
                ?: fontMimeType(extension)
        ) { temp ->
            copyUri(uri, temp, MAX_FONT_BYTES)
        }
    }

    suspend fun availableSystemFonts(): List<SystemReaderFont> = withContext(Dispatchers.IO) {
        val androidFonts = SystemFonts.getAvailableFonts()
            .asSequence()
            .mapNotNull { it.file }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_FONT_EXTENSIONS }
            .mapNotNull(::systemFontFromFile)
            .toList()
        val samsungFonts = discoverSamsungFlipFonts()
        (androidFonts + samsungFonts)
            .distinctBy {
                listOf(
                    it.packageName.orEmpty(),
                    it.assetPath.orEmpty(),
                    it.filePath
                ).joinToString("#")
            }
            .sortedWith(
                compareBy<SystemReaderFont> { it.displayName.lowercase() }
                    .thenBy { it.fileName.lowercase() }
            )
            .toList()
    }

    suspend fun importSystemFont(font: SystemReaderFont): ImportedReaderFont =
        withContext(Dispatchers.IO) {
            if (font.packageName != null && font.assetPath != null) {
                require(font.packageName.startsWith(SAMSUNG_FLIP_FONT_PACKAGE_PREFIX)) {
                    "字体包来源无效"
                }
                require(
                    font.assetPath.startsWith("fonts/") &&
                        ".." !in font.assetPath &&
                        font.assetPath.substringAfterLast('.').lowercase() in
                        SUPPORTED_FONT_EXTENSIONS
                ) { "字体资源路径无效" }
                val assets = appContext.packageManager
                    .getResourcesForApplication(font.packageName)
                    .assets
                val extension = font.assetPath.substringAfterLast('.').lowercase()
                return@withContext importFontSource(
                    displayName = font.displayName,
                    extension = extension,
                    mimeType = fontMimeType(extension)
                ) { temp ->
                    assets.open(font.assetPath).use { input ->
                        temp.outputStream().buffered().use { output ->
                            input.copyBoundedTo(output, MAX_FONT_BYTES)
                        }
                    }
                }
            }

            val source = File(font.filePath).canonicalFile
            val availablePaths = SystemFonts.getAvailableFonts()
                .mapNotNull { it.file }
                .mapTo(hashSetOf()) {
                    runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
                }
            require(source.path in availablePaths && source.isFile) { "系统字体已不可用" }
            val extension = source.extension.lowercase()
            require(extension in SUPPORTED_FONT_EXTENSIONS) { "该系统字体格式暂不支持" }
            importFontSource(
                displayName = font.displayName.ifBlank { source.nameWithoutExtension },
                extension = extension,
                mimeType = fontMimeType(extension)
            ) { temp ->
                source.inputStream().buffered().use { input ->
                    temp.outputStream().buffered().use { output ->
                        input.copyBoundedTo(output, MAX_FONT_BYTES)
                    }
                }
            }
        }

    private fun discoverSamsungFlipFonts(): List<SystemReaderFont> {
        val packageManager = appContext.packageManager
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName.startsWith(SAMSUNG_FLIP_FONT_PACKAGE_PREFIX) }
            .flatMap { application ->
                runCatching {
                    val resources = packageManager.getResourcesForApplication(application)
                    val assets = resources.assets
                    val displayNames = flipFontDisplayNames(assets)
                    assets.list("fonts").orEmpty()
                        .asSequence()
                        .filter {
                            it.substringAfterLast('.', "").lowercase() in
                                SUPPORTED_FONT_EXTENSIONS
                        }
                        .mapNotNull { fileName ->
                            val assetPath = "fonts/$fileName"
                            val cacheName = UUID.nameUUIDFromBytes(
                                "${application.packageName}:$assetPath".toByteArray()
                            ).toString() + "." + fileName.substringAfterLast('.').lowercase()
                            val cacheFile = File(systemFontCache, cacheName)
                            assets.open(assetPath).use { input ->
                                cacheFile.outputStream().buffered().use { output ->
                                    input.copyBoundedTo(output, MAX_FONT_BYTES)
                                }
                            }
                            val rawDisplayName = displayNames[fileName]
                                ?: packageManager.getApplicationLabel(application).toString()
                            systemFontFromFile(
                                file = cacheFile,
                                displayName = localizedSamsungFontName(rawDisplayName),
                                sourceLabel = "Samsung 字体与样式",
                                packageName = application.packageName,
                                assetPath = assetPath,
                                originalFileName = fileName
                            )
                        }
                        .toList()
                }.getOrDefault(emptyList()).asSequence()
            }
            .toList()
    }

    private fun flipFontDisplayNames(assets: AssetManager): Map<String, String> = buildMap {
        assets.list("xml").orEmpty()
            .filter { it.endsWith(".xml", ignoreCase = true) }
            .forEach { xmlName ->
                runCatching {
                    assets.open("xml/$xmlName").use { input ->
                        val parser = Xml.newPullParser().apply {
                            setInput(input, Charsets.UTF_8.name())
                        }
                        var displayName = ""
                        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "font" -> displayName =
                                        parser.getAttributeValue(null, "displayname").orEmpty()
                                    "filename" -> {
                                        val fileName = parser.nextText().trim()
                                        if (fileName.isNotBlank() && displayName.isNotBlank()) {
                                            put(fileName, displayName)
                                        }
                                    }
                                }
                            }
                            parser.next()
                        }
                    }
                }
            }
    }

    private fun localizedSamsungFontName(rawName: String): String {
        val settingsResourceName = when (rawName.lowercase()) {
            "foundation" -> "sec_monotype_dialog_font_gothicbold"
            "shaonv" -> "sec_monotype_dialog_font_girl"
            "miao" -> "sec_monotype_dialog_font_miao"
            else -> null
        } ?: return rawName
        return runCatching {
            val resources = appContext.packageManager
                .getResourcesForApplication(ANDROID_SETTINGS_PACKAGE)
            val id = resources.getIdentifier(
                settingsResourceName,
                "string",
                ANDROID_SETTINGS_PACKAGE
            )
            resources.getString(id).takeIf(String::isNotBlank)
        }.getOrNull() ?: rawName
    }

    private fun systemFontFromFile(
        file: File,
        displayName: String? = null,
        sourceLabel: String = "Android 系统",
        packageName: String? = null,
        assetPath: String? = null,
        originalFileName: String = file.name
    ): SystemReaderFont? = runCatching {
        val metadata = OpenTypeMetadataParser.parse(file)
        val faces = metadata.optJSONArray("faces")
        val familyNames = buildList {
            repeat(faces?.length() ?: 0) { index ->
                faces?.optJSONObject(index)
                    ?.optString("family")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.distinct()
        val styles = buildList {
            repeat(faces?.length() ?: 0) { index ->
                faces?.optJSONObject(index)
                    ?.optString("subfamily")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.distinct()
        val familyName = familyNames.firstOrNull() ?: file.nameWithoutExtension
        SystemReaderFont(
            filePath = file.canonicalPath,
            displayName = displayName
                ?: familyNames.joinToString(" / ").ifBlank { familyName },
            familyName = familyName,
            styles = styles.joinToString("、").ifBlank { "Regular" },
            fileName = originalFileName,
            byteCount = file.length(),
            faceCount = metadata.optInt("faceCount", 1),
            sourceLabel = sourceLabel,
            packageName = packageName,
            assetPath = assetPath
        )
    }.getOrNull()

    private fun importFontSource(
        displayName: String,
        extension: String,
        mimeType: String,
        copySource: (File) -> Unit
    ): ImportedReaderFont {
        val temp = File(fonts, ".${UUID.randomUUID()}.import")
        return try {
            copySource(temp)
            runCatching { Typeface.createFromFile(temp) }.getOrNull()
                ?: throw IllegalArgumentException("字体文件无法加载")
            val hash = sha256Blocking(temp)
            val finalName = "$hash.$extension"
            val target = File(fonts, finalName)
            if (!target.exists()) {
                check(temp.renameTo(target)) { "字体文件保存失败" }
            }
            val parsedMetadata = OpenTypeMetadataParser.parse(target)
            val faceCount = parsedMetadata.optInt("faceCount", 1)
            val firstFace = parsedMetadata.optJSONArray("faces")?.optJSONObject(0)
            ImportedReaderFont(
                sha256 = hash,
                displayName = displayName.substringBeforeLast('.', displayName)
                    .ifBlank { "自定义字体" },
                familyName = firstFace?.optString("family")
                    ?.takeIf(String::isNotBlank)
                    ?: displayName.substringBeforeLast('.', displayName),
                fileName = finalName,
                mimeType = mimeType,
                byteCount = target.length(),
                faceCount = faceCount,
                metadataJson = parsedMetadata.apply {
                    put("extension", extension)
                }.toString()
            )
        } finally {
            temp.delete()
        }
    }

    private fun fontMimeType(extension: String): String = when (extension) {
        "otf" -> "font/otf"
        "ttc" -> "font/collection"
        else -> "font/ttf"
    }

    suspend fun importBackground(uri: Uri): ImportedReaderBackground =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val displayName = displayName(uri, "阅读背景")
            val mimeType = resolver.getType(uri).orEmpty()
            val kind = when {
                mimeType.startsWith("image/") -> ReaderBackgroundType.IMAGE
                mimeType.startsWith("video/") -> ReaderBackgroundType.VIDEO
                else -> throw IllegalArgumentException("仅支持图片或视频背景")
            }
            val extension = displayName.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: if (kind == ReaderBackgroundType.IMAGE) "img" else "video"
            val temp = File(backgrounds, ".${UUID.randomUUID()}.import")
            try {
                copyUri(uri, temp, MAX_BACKGROUND_BYTES)
                val hash = sha256Blocking(temp)
                val finalName = "$hash.$extension"
                val target = File(backgrounds, finalName)
                if (!target.exists()) {
                    check(temp.renameTo(target)) { "背景资源保存失败" }
                }
                val metadata = if (kind == ReaderBackgroundType.IMAGE) {
                    imageMetadata(target)
                } else {
                    videoMetadata(target)
                }
                require(metadata.first > 0 && metadata.second > 0) { "无法读取背景尺寸" }
                ImportedReaderBackground(
                    sha256 = hash,
                    displayName = displayName.substringBeforeLast('.').ifBlank { "阅读背景" },
                    kind = kind,
                    mimeType = mimeType.ifBlank {
                        if (kind == ReaderBackgroundType.IMAGE) "image/*" else "video/*"
                    },
                    fileName = finalName,
                    byteCount = target.length(),
                    durationMs = metadata.third,
                    width = metadata.first,
                    height = metadata.second
                )
            } finally {
                temp.delete()
            }
        }

    fun fontFile(fileName: String): File? =
        safeChild(fonts, fileName)?.takeIf(File::isFile)

    suspend fun deleteFontFile(fileName: String) = withContext(Dispatchers.IO) {
        safeChild(fonts, fileName)?.let { file ->
            !file.exists() || file.delete()
        } ?: false
    }

    fun backgroundFile(fileName: String): File? =
        safeChild(backgrounds, fileName)?.takeIf(File::isFile)

    fun variantFile(fileName: String): File? =
        safeChild(variants, fileName)?.takeIf(File::isFile)

    fun targetFontFile(fileName: String): File =
        requireNotNull(safeChild(fonts, fileName)) { "字体文件名无效" }

    fun targetBackgroundFile(fileName: String): File =
        requireNotNull(safeChild(backgrounds, fileName)) { "背景文件名无效" }

    fun targetVariantFile(fileName: String): File =
        requireNotNull(safeChild(variants, fileName)) { "背景派生文件名无效" }

    suspend fun fileSha256(file: File): String = withContext(Dispatchers.IO) {
        sha256Blocking(file)
    }

    suspend fun pruneUnreferencedFiles(
        keepFonts: Set<String>,
        keepBackgrounds: Set<String>,
        keepVariants: Set<String>
    ) = withContext(Dispatchers.IO) {
        pruneDirectory(fonts, keepFonts)
        pruneDirectory(backgrounds, keepBackgrounds)
        pruneDirectory(variants, keepVariants)
    }

    private fun pruneDirectory(directory: File, keepNames: Set<String>) {
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .filterNot { it.name.startsWith('.') }
            .filter { it.name !in keepNames }
            .forEach(File::delete)
    }

    private fun copyUri(uri: Uri, target: File, limitBytes: Long) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选文件")
        input.use { source ->
            target.outputStream().buffered().use { output ->
                source.copyBoundedTo(output, limitBytes)
            }
        }
    }

    private fun displayName(uri: Uri, fallback: String): String {
        return appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun imageMetadata(file: File): Triple<Int, Int, Long> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return Triple(options.outWidth, options.outHeight, 0L)
    }

    private fun videoMetadata(file: File): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Triple(width, height, duration)
        } finally {
            retriever.release()
        }
    }

    private fun readTtcFaceCount(file: File): Int = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            require(input.read(header) == header.size)
            require(header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "ttcf")
            ((header[8].toInt() and 0xff) shl 24) or
                ((header[9].toInt() and 0xff) shl 16) or
                ((header[10].toInt() and 0xff) shl 8) or
                (header[11].toInt() and 0xff)
        }
    }.getOrDefault(1).coerceIn(1, 128)

    private fun safeChild(directory: File, fileName: String): File? {
        if (fileName.isBlank() || '/' in fileName || '\\' in fileName || fileName.contains("..")) {
            return null
        }
        val child = File(directory, fileName)
        return child.takeIf { it.parentFile == directory }
    }

    private fun sha256Blocking(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.copyBoundedTo(output: java.io.OutputStream, limitBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limitBytes) { "资源文件过大" }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        const val ROOT_DIRECTORY = "reader_assets"
        const val FONT_DIRECTORY = "fonts"
        const val BACKGROUND_DIRECTORY = "backgrounds"
        const val VARIANT_DIRECTORY = "variants"
        const val SYSTEM_FONT_CACHE_DIRECTORY = "reader_system_fonts"
        const val MAX_FONT_BYTES = 512L * 1024L * 1024L
        const val MAX_BACKGROUND_BYTES = 512L * 1024L * 1024L
        val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
        private const val SAMSUNG_FLIP_FONT_PACKAGE_PREFIX = "com.monotype.android.font."
        private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
    }
}
