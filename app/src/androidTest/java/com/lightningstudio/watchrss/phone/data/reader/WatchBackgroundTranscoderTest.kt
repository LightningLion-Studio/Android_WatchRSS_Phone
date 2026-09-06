package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneWatchCapabilities
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneWatchVideoDecoder
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatchBackgroundTranscoderTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: PhoneCompanionDatabase
    private lateinit var repository: ReaderPresetRepository
    private lateinit var transcoder: WatchBackgroundTranscoder
    private lateinit var scope: CoroutineScope
    private val capabilities = PhoneWatchCapabilities(200, 200, 60.0, 100_000_000L,
        listOf(PhoneWatchVideoDecoder("test-avc", "video/avc", true, 1920, 1080, 60.0, emptyList())))

    @Before fun setUp() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "watch-background-tests").apply { deleteRecursively(); mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("watch-background-test-$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(context, PhoneCompanionDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = ReaderPresetRepository(context, database, database.readerPresetDao(), "transcoder-test", scope)
        transcoder = WatchBackgroundTranscoder(context, repository)
    }
    @After fun tearDown() { scope.cancel(); database.close(); root.deleteRecursively() }

    @Test fun imageRetainsWholeFrameAlphaAndCacheInvalidatesOldCrop() = runBlocking {
        val source = repository.resourceStore.targetBackgroundFile("corners.png")
        val bitmap = cornerBitmap()
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val asset = insert(source, "IMAGE")
        val prepared = transcoder.prepare(asset, capabilities)
        val variant = JSONObject(prepared.variantsJson).getJSONObject("watch")
        val output = repository.resourceStore.variantFile(variant.getString("fileName"))!!
        val decoded = BitmapFactory.decodeFile(output.path)
        assertEquals(400, decoded.width); assertEquals(200, decoded.height)
        assertEquals(Color.RED, decoded.getPixel(10, 10)); assertEquals(Color.GREEN, decoded.getPixel(390, 10))
        assertEquals(Color.BLUE, decoded.getPixel(10, 190)); assertEquals(Color.YELLOW, decoded.getPixel(390, 190))
        assertEquals(0, Color.alpha(decoded.getPixel(200, 100)))
        assertTrue(decoded.colorSpace!!.isSrgb)
        decoded.recycle()
        val saved = database.readerPresetDao().backgroundById(asset.id)!!
        transcoder.prepare(saved, capabilities)
        assertEquals(saved.updatedAt, database.readerPresetDao().backgroundById(asset.id)!!.updatedAt)
        variant.remove("processingVersion")
        repository.updateBackgroundAsset(saved.copy(variantsJson = JSONObject().put("watch", variant).toString()))
        val repaired = transcoder.prepare(asset, capabilities)
        assertEquals(WatchBackgroundTranscoder.PROCESSING_VERSION,
            JSONObject(repaired.variantsJson).getJSONObject("watch").getInt("processingVersion"))
        assertTrue(source.exists())
    }

    @Test fun exifOrientationIsAppliedBeforeSizing() = runBlocking {
        val source = repository.resourceStore.targetBackgroundFile("rotated.jpg")
        val bitmap = cornerBitmap()
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val prepared = transcoder.prepare(insert(source, "IMAGE"), capabilities)
        val output = variantFile(prepared, "watch")
        val decoded = BitmapFactory.decodeFile(output.path)
        assertEquals(200, decoded.width); assertEquals(400, decoded.height)
        val topLeft = decoded.getPixel(10, 10)
        assertTrue(Color.blue(topLeft) > 200 && Color.red(topLeft) < 40)
        decoded.recycle()
    }

    @Test fun ultraHdrImageProducesSdrBaseWithoutGainmap() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 34)
        val source = repository.resourceStore.targetBackgroundFile("ultrahdr.jpg")
        val bitmap = cornerBitmap()
        val gain = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        bitmap.setGainmap(Gainmap(gain).apply { setRatioMax(4f, 4f, 4f); displayRatioForFullHdr = 4f })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle(); gain.recycle()
        val original = BitmapFactory.decodeFile(source.path)
        assertTrue("Fixture must really contain Ultra HDR", original.hasGainmap()); original.recycle()
        val prepared = transcoder.prepare(insert(source, "IMAGE"), capabilities)
        val decoded = BitmapFactory.decodeFile(variantFile(prepared, "watch").path)
        assertFalse(decoded.hasGainmap()); assertTrue(decoded.colorSpace!!.isSrgb)
        assertTrue(Color.red(decoded.getPixel(10, 10)) > 200)
        decoded.recycle()
    }

    @Test fun sdrVideoAndPosterPreserveFullFrameAtDoubleSize() = runBlocking {
        verifyVideo("sdr.mp4")
    }
    @Test fun pqHdrVideoIsSdrOrExplicitlyRejectedWhenToneMappingUnsupported() = runBlocking<Unit> {
        try {
            verifyVideo("hdr-pq.mp4")
        } catch (failure: WatchBackgroundPreparationException) {
            // Emulators may expose neither GL_EXT_YUV_target nor codec tone mapping.
            // A real unsupported device must fail closed, never send HDR as a fallback.
            val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(causes.any { it.message?.contains("Tone-mapping requested but not supported") == true ||
                it.message?.contains("GL_EXT_YUV_target") == true })
            assertTrue(repository.exportSnapshot().backgrounds.all { it.variantsJson == "{}" })
            assertFalse(root.walk().any { it.name.contains(".prepare") || it.name.contains(".poster") })
            android.util.Log.w("WatchBackgroundTranscoderTest", "HDR success path requires a tone-mapping-capable phone; unsupported-device rejection verified")
        }
    }
    @Test fun rotatedVideoUsesDisplayOrientationAndAnOrientedSdrPoster() = runBlocking {
        val source = copyFixture("rotated.mp4")
        val metadata = android.media.MediaMetadataRetriever()
        try {
            metadata.setDataSource(source.path)
            assertTrue(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in listOf("90", "270"))
        } finally { metadata.release() }
        val prepared = withTimeout(120_000) { transcoder.prepare(insert(source, "VIDEO"), capabilities) }
        val watch = JSONObject(prepared.variantsJson).getJSONObject("watch")
        assertEquals(224, watch.getInt("width")); assertEquals(400, watch.getInt("height"))
        val poster = BitmapFactory.decodeFile(variantFile(prepared, "watchPoster").path)
        assertEquals(224, poster.width); assertEquals(400, poster.height)
        poster.recycle()
    }

    @Test fun decoderSelectionUsesDoubleSizeNotTheDisplaySize() = runBlocking {
        val source = copyFixture("sdr.mp4")
        val caps = capabilities.copy(videoDecoders = listOf(
            PhoneWatchVideoDecoder("too-small-hevc", "video/hevc", true, 300, 300, 60.0, emptyList())
        ) + capabilities.videoDecoders)
        val prepared = withTimeout(120_000) { transcoder.prepare(insert(source, "VIDEO"), caps) }
        assertEquals("video/avc", JSONObject(prepared.variantsJson).getJSONObject("watch").getString("mime"))
    }

    @Test fun libraryPreparationLeavesMissingMastersForSyncToDownload() = runBlocking {
        val source = copyFixture("sdr.mp4")
        val asset = insert(source, "VIDEO")
        assertTrue(source.delete())
        transcoder.prepareAll(capabilities)
        assertEquals("{}", database.readerPresetDao().backgroundById(asset.id)!!.variantsJson)
        try { transcoder.prepare(asset, capabilities); fail("Preview must reject a missing source") }
        catch (_: WatchBackgroundPreparationException) { }
    }

    @Test fun missingDecoderFailsWithoutPublishingOrLeakingTemps() = runBlocking {
        val source = copyFixture("sdr.mp4")
        val asset = insert(source, "VIDEO")
        try { transcoder.prepare(asset, capabilities.copy(videoDecoders = emptyList())); fail("Must fail") }
        catch (_: WatchBackgroundPreparationException) { }
        assertEquals("{}", database.readerPresetDao().backgroundById(asset.id)!!.variantsJson)
        assertFalse(root.walk().any { it.name.contains(".prepare") || it.name.contains(".poster") })
    }
    @Test fun cancellationDoesNotPublishAnUnfinishedVariant() = runBlocking {
        val source = copyFixture("sdr.mp4")
        val asset = insert(source, "VIDEO")
        val job = launch(Dispatchers.Default) { transcoder.prepare(asset, capabilities) }
        delay(100)
        job.cancelAndJoin()
        delay(200) // Main-thread Transformer cancellation owns its codec and output handle.
        assertEquals("{}", database.readerPresetDao().backgroundById(asset.id)!!.variantsJson)
        assertFalse(root.walk().any { it.name.contains(".prepare") || it.name.contains(".poster") })
    }

    private suspend fun verifyVideo(name: String) {
        val source = copyFixture(name)
        val prepared = withTimeout(120_000) { transcoder.prepare(insert(source, "VIDEO"), capabilities) }
        val video = variantFile(prepared, "watch")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            assertEquals(1, tracks.size)
            val format = tracks.single()
            assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
            val watch = JSONObject(prepared.variantsJson).getJSONObject("watch")
            assertEquals(400, watch.getInt("width")); assertEquals(224, watch.getInt("height"))
            assertEquals(24, watch.getInt("fps"))
        } finally { extractor.release() }
        val poster = BitmapFactory.decodeFile(variantFile(prepared, "watchPoster").path)
        assertEquals(400, poster.width); assertEquals(224, poster.height)
        assertTrue(poster.colorSpace!!.isSrgb); poster.recycle()
        // Keep an inspectable copy outside the isolated repository until the next test run.
        val artifacts = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "background-test-output").apply { mkdirs() }
        video.copyTo(File(artifacts, name), overwrite = true)
        variantFile(prepared, "watchPoster").copyTo(File(artifacts, "$name.png"), overwrite = true)
    }

    private fun cornerBitmap() = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
            paint.color = color
            val x = (index % 2) * 400f; val y = (index / 2) * 200f
            canvas.drawRect(x, y, x + 400f, y + 200f, paint)
        }
        setPixel(400, 200, Color.TRANSPARENT)
        val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        canvas.drawRect(350f, 150f, 450f, 250f, clear)
    }
    private fun copyFixture(name: String): File = repository.resourceStore.targetBackgroundFile(name).also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("reader-backgrounds/$name").use { input ->
            file.outputStream().use(input::copyTo)
        }
    }
    private suspend fun insert(source: File, kind: String): ReaderBackgroundAssetEntity {
        val asset = ReaderBackgroundAssetEntity(source.name, repository.resourceStore.fileSha256(source), source.name,
            kind, if (kind == "VIDEO") "video/mp4" else "image/png", source.name, source.length(),
            4_000L, 800, 400, null, "{}", 1L, "test", false)
        database.readerPresetDao().upsertBackground(asset)
        return asset
    }
    private fun variantFile(asset: ReaderBackgroundAssetEntity, key: String) =
        repository.resourceStore.variantFile(JSONObject(asset.variantsJson).getJSONObject(key).getString("fileName"))!!
}
