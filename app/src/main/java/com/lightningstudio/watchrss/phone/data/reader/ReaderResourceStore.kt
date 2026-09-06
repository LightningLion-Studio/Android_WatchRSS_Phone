package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

enum class ReaderBackgroundImportMode {
    KEEP_ORIGINAL,
    COMPATIBLE_STATIC
}

data class ReaderBackgroundImportInspection(
    val mimeType: String,
    val kind: ReaderBackgroundType,
    val formatLabel: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val animated: Boolean,
    val hdr: Boolean,
    val wideColor: Boolean
) {
    val requiresChoice: Boolean
        get() = animated || hdr || formatLabel in setOf("HEIC", "HEIF", "GIF")
}

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
    val id: String,
    val sha256: String,
    val displayName: String,
    val kind: ReaderBackgroundType,
    val mimeType: String,
    val fileName: String,
    val byteCount: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val variantsJson: String = "{}"
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

    suspend fun inspectBackground(uri: Uri): ReaderBackgroundImportInspection =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(uri).orEmpty()
            val kind = when {
                mimeType.startsWith("image/") -> ReaderBackgroundType.IMAGE
                mimeType.startsWith("video/") -> ReaderBackgroundType.VIDEO
                else -> throw IllegalArgumentException("仅支持图片或视频背景")
            }
            if (kind == ReaderBackgroundType.VIDEO) {
                val temp = File(appContext.cacheDir, ".${UUID.randomUUID()}.inspect-video")
                try {
                    copyUri(uri, temp, MAX_BACKGROUND_BYTES)
                    val metadata = videoMetadata(temp)
                    require(metadata.first > 0 && metadata.second > 0) { "无法读取背景尺寸" }
                    ReaderBackgroundImportInspection(
                        mimeType = mimeType,
                        kind = kind,
                        formatLabel = safeBackgroundExtension(kind, mimeType, false).uppercase(),
                        width = metadata.first,
                        height = metadata.second,
                        durationMs = metadata.third,
                        animated = false,
                        hdr = false,
                        wideColor = false
                    )
                } finally {
                    temp.delete()
                }
            } else {
                inspectImage(uri, mimeType)
            }
        }

    suspend fun importBackground(
        uri: Uri,
        mode: ReaderBackgroundImportMode = ReaderBackgroundImportMode.KEEP_ORIGINAL,
        inspection: ReaderBackgroundImportInspection? = null
    ): ImportedReaderBackground =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(uri).orEmpty()
            val kind = when {
                mimeType.startsWith("image/") -> ReaderBackgroundType.IMAGE
                mimeType.startsWith("video/") -> ReaderBackgroundType.VIDEO
                else -> throw IllegalArgumentException("仅支持图片或视频背景")
            }
            val convertToStatic = mode == ReaderBackgroundImportMode.COMPATIBLE_STATIC &&
                kind == ReaderBackgroundType.IMAGE
            val extension = safeBackgroundExtension(kind, mimeType, convertToStatic)
            val temp = File(backgrounds, ".${UUID.randomUUID()}.import")
            val converted = File(backgrounds, ".${UUID.randomUUID()}.compatible.png")
            try {
                copyUri(uri, temp, MAX_BACKGROUND_BYTES)
                val source = if (convertToStatic) {
                    convertToCompatibleStatic(temp, converted)
                    converted
                } else {
                    temp
                }
                val hash = sha256Blocking(source)
                val assetId = UUID.randomUUID().toString()
                val finalName = "$assetId.$extension"
                val target = File(backgrounds, finalName)
                check(source.renameTo(target)) { "背景资源保存失败" }
                val metadata = if (kind == ReaderBackgroundType.IMAGE) {
                    imageMetadata(target)
                } else {
                    videoMetadata(target)
                }
                require(metadata.first > 0 && metadata.second > 0) { "无法读取背景尺寸" }
                val mediaInfo = inspection ?: if (kind == ReaderBackgroundType.IMAGE) {
                    inspectImage(uri, mimeType)
                } else {
                    null
                }
                val sourceMetadata = JSONObject().apply {
                    put("format", if (convertToStatic) "PNG" else mediaInfo?.formatLabel.orEmpty())
                    put("animated", if (convertToStatic) false else mediaInfo?.animated ?: false)
                    put("hdr", if (convertToStatic) false else mediaInfo?.hdr ?: false)
                    put("wideColor", if (convertToStatic) false else mediaInfo?.wideColor ?: false)
                    put("compatibilityConverted", convertToStatic)
                }
                ImportedReaderBackground(
                    id = assetId,
                    sha256 = hash,
                    displayName = when {
                        convertToStatic -> "照片（兼容静态图）"
                        kind == ReaderBackgroundType.IMAGE -> "照片"
                        else -> "视频"
                    },
                    kind = kind,
                    mimeType = if (convertToStatic) "image/png" else mimeType.ifBlank {
                        if (kind == ReaderBackgroundType.IMAGE) "image/*" else "video/*"
                    },
                    fileName = finalName,
                    byteCount = target.length(),
                    durationMs = metadata.third,
                    width = metadata.first,
                    height = metadata.second,
                    variantsJson = JSONObject().put("source", sourceMetadata).toString()
                )
            } finally {
                temp.delete()
                converted.delete()
            }
        }

    suspend fun extractVideoFrame(
        source: File,
        sourceAssetId: String,
        timeMs: Long,
        cropX: Float = 0f,
        cropY: Float = 0f
    ): ImportedReaderBackground = withContext(Dispatchers.IO) {
        require(source.isFile) { "背景原视频不存在" }
        val retriever = MediaMetadataRetriever()
        val bitmap = try {
            retriever.setDataSource(source.absolutePath)
            retriever.getFrameAtTime(
                timeMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: error("无法截取所选视频画面")
        } finally {
            retriever.release()
        }
        val square = cropSquareBitmap(bitmap, cropX, cropY)
        if (square !== bitmap) bitmap.recycle()
        val scaled = if (square.width == WATCH_BACKGROUND_SIZE && square.height == WATCH_BACKGROUND_SIZE) {
            square
        } else {
            Bitmap.createScaledBitmap(
                square,
                WATCH_BACKGROUND_SIZE,
                WATCH_BACKGROUND_SIZE,
                true
            ).also { square.recycle() }
        }
        val assetId = UUID.randomUUID().toString()
        val target = File(backgrounds, "$assetId.jpg")
        try {
            target.outputStream().buffered().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "视频画面保存失败"
                }
            }
        } finally {
            scaled.recycle()
        }
        val hash = sha256Blocking(target)
        val sourceMetadata = JSONObject().apply {
            put("format", "JPEG")
            put("animated", false)
            put("hdr", false)
            put("wideColor", false)
            put("extractedFromVideo", sourceAssetId)
            put("frameTimeMs", timeMs.coerceAtLeast(0L))
            put("cropX", cropX.coerceIn(-1f, 1f).toDouble())
            put("cropY", cropY.coerceIn(-1f, 1f).toDouble())
        }
        ImportedReaderBackground(
            id = assetId,
            sha256 = hash,
            displayName = "视频画面",
            kind = ReaderBackgroundType.IMAGE,
            mimeType = "image/jpeg",
            fileName = target.name,
            byteCount = target.length(),
            durationMs = 0L,
            width = WATCH_BACKGROUND_SIZE,
            height = WATCH_BACKGROUND_SIZE,
            variantsJson = JSONObject().put("source", sourceMetadata).toString()
        )
    }

    private fun cropSquareBitmap(source: Bitmap, cropX: Float, cropY: Float): Bitmap {
        val side = minOf(source.width, source.height)
        val maxLeft = (source.width - side).coerceAtLeast(0)
        val maxTop = (source.height - side).coerceAtLeast(0)
        val left = (((cropX.coerceIn(-1f, 1f) + 1f) / 2f) * maxLeft).toInt()
            .coerceIn(0, maxLeft)
        val top = (((cropY.coerceIn(-1f, 1f) + 1f) / 2f) * maxTop).toInt()
            .coerceIn(0, maxTop)
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    private fun safeBackgroundExtension(
        kind: ReaderBackgroundType,
        mimeType: String,
        convertedToStatic: Boolean
    ): String {
        if (convertedToStatic) return "png"
        return when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heic-sequence" -> "heic"
            "image/heif", "image/heif-sequence" -> "heif"
            "image/avif" -> "avif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            else -> if (kind == ReaderBackgroundType.IMAGE) "img" else "video"
        }
    }

    private fun inspectImage(
        uri: Uri,
        mimeType: String
    ): ReaderBackgroundImportInspection {
        val resolver = appContext.contentResolver
        var width = 0
        var height = 0
        var animated = false
        var hdr = false
        var wideColor = false
        val format = imageFormatLabel(mimeType)
        resolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            width = options.outWidth
            height = options.outHeight
            wideColor = options.outColorSpace?.isWideGamut == true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri)) {
                        decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    val colorSpace = info.colorSpace
                    wideColor = wideColor || colorSpace?.isWideGamut == true
                    hdr = colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_HLG) ||
                        colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_PQ)
                    val longest = maxOf(width, height).coerceAtLeast(1)
                    if (longest > INSPECTION_MAX_DIMENSION) {
                        val scale = INSPECTION_MAX_DIMENSION.toFloat() / longest
                        decoder.setTargetSize(
                            (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }.onSuccess { drawable ->
                animated = drawable is AnimatedImageDrawable
            }
            if (Build.VERSION.SDK_INT >= 34 && !animated) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) {
                            decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                        if (longest > INSPECTION_MAX_DIMENSION) {
                            val scale = INSPECTION_MAX_DIMENSION.toFloat() / longest
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                }.onSuccess { bitmap ->
                    hdr = hdr || bitmap.hasGainmap()
                    bitmap.recycle()
                }
            }
        }
        require(width > 0 && height > 0) { "无法读取背景尺寸" }
        return ReaderBackgroundImportInspection(
            mimeType = mimeType,
            kind = ReaderBackgroundType.IMAGE,
            formatLabel = format,
            width = width,
            height = height,
            durationMs = 0L,
            animated = animated,
            hdr = hdr,
            wideColor = wideColor
        )
    }

    private fun convertToCompatibleStatic(source: File, target: File) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "当前系统无法转换该图片" }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            if (longest > COMPATIBLE_MAX_DIMENSION) {
                val scale = COMPATIBLE_MAX_DIMENSION.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
        target.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "兼容图片转换失败" }
        }
        bitmap.recycle()
    }

    private fun imageFormatLabel(mimeType: String): String = when {
        mimeType.contains("heic", ignoreCase = true) -> "HEIC"
        mimeType.contains("heif", ignoreCase = true) -> "HEIF"
        mimeType.contains("webp", ignoreCase = true) -> "WebP"
        mimeType.contains("gif", ignoreCase = true) -> "GIF"
        mimeType.contains("avif", ignoreCase = true) -> "AVIF"
        mimeType.contains("png", ignoreCase = true) -> "PNG"
        mimeType.contains("jpeg", ignoreCase = true) -> "JPEG"
        else -> "图片"
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
        requireNotNull(safeChild(variants, fileName)) { "背景适配文件名无效" }

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
            val encodedWidth = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val encodedHeight = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val width = if (rotation % 180 != 0) encodedHeight else encodedWidth
            val height = if (rotation % 180 != 0) encodedWidth else encodedHeight
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
        private const val INSPECTION_MAX_DIMENSION = 512
        private const val COMPATIBLE_MAX_DIMENSION = 4096
        const val WATCH_BACKGROUND_SIZE = 466
        val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
        private const val SAMSUNG_FLIP_FONT_PACKAGE_PREFIX = "com.monotype.android.font."
        private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
    }
}
