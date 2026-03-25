package com.lightningstudio.watchrss.phone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.util.Range
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.GestureDetector
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lightningstudio.watchrss.phone.network.NetworkManager
import com.lightningstudio.watchrss.phone.network.QRCodeParser
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.concurrent.Executors

class QRScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRSSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QRScanScreen(
                        onBack = { finish() },
                        onQRCodeDetected = { content ->
                            handleQRCode(content)
                        }
                    )
                }
            }
        }
    }

    private fun handleQRCode(content: String) {
        // 尝试解析为 ip:port
        val (ip, port) = QRCodeParser.parseQRCode(content)

        if (ip != null && port != null) {
            // 是我们的二维码，尝试连接
            NetworkManager.setBaseUrl(ip, port.toInt())
            NetworkManager.checkHealth { success ->
                runOnUiThread {
                    if (success) {
                        val intent = Intent(this, ConnectedActivity::class.java)
                        intent.putExtra("ip", ip)
                        intent.putExtra("port", port)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, "连接失败，请联系开发者", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, ContactDeveloperActivity::class.java))
                    }
                }
            }
        } else {
            // 不是我们的二维码，按普通二维码处理
            handleGenericQRCode(content)
        }
    }

    private fun handleGenericQRCode(content: String) {
        when {
            content.startsWith("http://") || content.startsWith("https://") -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(content)))
                finish()
            }
            content.startsWith("mailto:") -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(content)))
                finish()
            }
            content.startsWith("tel:") -> {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(content)))
                finish()
            }
            content.startsWith("sms:") -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(content)))
                finish()
            }
            content.startsWith("BEGIN:VCARD") || content.startsWith("MECARD:") -> {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.type = "text/x-vcard"
                intent.putExtra(Intent.EXTRA_TEXT, content)
                startActivity(intent)
                finish()
            }
            content.startsWith("WIFI:") -> {
                // Android 10+ 可以直接打开WiFi设置
                Toast.makeText(this, "WiFi配置: $content", Toast.LENGTH_LONG).show()
            }
            content.startsWith("geo:") -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(content)))
                finish()
            }
            else -> {
                // Plain text，显示内容
                setContent {
                    WatchRSSTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            PlainTextScreen(
                                content = content,
                                onBack = { finish() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QRScanScreen(
    onBack: () -> Unit,
    onQRCodeDetected: (String) -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var hasScanned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreview(
                onQRCodeDetected = { content ->
                    if (!hasScanned) {
                        hasScanned = true
                        onQRCodeDetected(content)
                    }
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "需要相机权限来扫描二维码",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("授予权限")
                }
            }
        }

        // Top bar
        TopAppBar(
            title = { Text("扫一扫") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
fun CameraPreview(onQRCodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var macroCamera by remember { mutableStateOf<BackCameraCandidate?>(null) }
    var normalCamera by remember { mutableStateOf<BackCameraCandidate?>(null) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var requestedZoomRatio by remember { mutableFloatStateOf(1f) }
    var exposureCompensationIndex by remember { mutableIntStateOf(0) }

    val selectedCamera = remember(macroCamera, normalCamera, requestedZoomRatio) {
        when {
            requestedZoomRatio <= MACRO_MAX_ZOOM_RATIO && macroCamera != null -> macroCamera
            normalCamera != null -> normalCamera
            else -> macroCamera
        }
    }

    DisposableEffect(cameraProviderFuture) {
        val listener = Runnable {
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val candidates = provider.availableCameraInfos
                .mapNotNull { createBackCameraCandidate(it) }

            val defaultBackId = try {
                CameraSelector.DEFAULT_BACK_CAMERA
                    .filter(provider.availableCameraInfos)
                    .firstOrNull()
                    ?.let { Camera2CameraInfo.from(it).cameraId }
            } catch (_: IllegalArgumentException) {
                null
            }

            normalCamera = candidates.firstOrNull { it.cameraId == defaultBackId }
                ?: candidates.maxByOrNull { it.focalLength }

            macroCamera = candidates
                .filter { it.cameraId != normalCamera?.cameraId }
                .maxWithOrNull(
                    compareBy<BackCameraCandidate> { it.minimumFocusDistance }
                        .thenByDescending { -it.focalLength }
                )
                ?.takeIf { candidate ->
                    val normalFocus = normalCamera?.minimumFocusDistance ?: 0f
                    candidate.minimumFocusDistance > normalFocus
                }
        }

        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            barcodeScanner.close()
            executor.shutdown()
        }
    }

    DisposableEffect(cameraProvider, selectedCamera) {
        val provider = cameraProvider
        val candidate = selectedCamera

        if (provider == null || candidate == null) {
            onDispose { }
        } else {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )

                    barcodeScanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { value ->
                                    onQRCodeDetected(value)
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                provider.unbindAll()
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    candidate.selector,
                    preview,
                    imageAnalysis
                )
                applyZoom(activeCamera, requestedZoomRatio)
                applyExposure(activeCamera, exposureCompensationIndex)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            onDispose {
                provider.unbindAll()
                activeCamera = null
            }
        }
    }

    DisposableEffect(previewView, activeCamera, macroCamera, normalCamera) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var pointerCount = 0
        var adjustingExposure = false
        var dragStartExposureIndex = exposureCompensationIndex

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    requestedZoomRatio = (requestedZoomRatio * detector.scaleFactor)
                        .coerceIn(MIN_ZOOM_RATIO, MAX_ZOOM_RATIO)
                    activeCamera?.let { applyZoom(it, requestedZoomRatio) }
                    return true
                }
            }
        )

        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    activeCamera?.let { camera ->
                        startFocusAndMetering(
                            previewView = previewView,
                            camera = camera,
                            x = event.x,
                            y = event.y
                        )
                        dragStartExposureIndex = exposureCompensationIndex
                    }
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            pointerCount = event.pointerCount
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)

            if (pointerCount > 1) {
                adjustingExposure = false
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragStartExposureIndex = exposureCompensationIndex
                    adjustingExposure = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!adjustingExposure && abs(event.y - downY) > touchSlop) {
                        adjustingExposure = true
                    }

                    if (adjustingExposure) {
                        val newIndex = calculateExposureIndex(
                            range = activeCamera?.cameraInfo?.exposureState?.exposureCompensationRange,
                            baseIndex = dragStartExposureIndex,
                            verticalDelta = downY - event.y
                        )

                        if (newIndex != null && newIndex != exposureCompensationIndex) {
                            exposureCompensationIndex = newIndex
                            activeCamera?.let { applyExposure(it, newIndex) }
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (
                        !adjustingExposure &&
                        abs(event.x - downX) <= touchSlop &&
                        abs(event.y - downY) <= touchSlop
                    ) {
                        activeCamera?.let { camera ->
                            startFocusAndMetering(
                                previewView = previewView,
                                camera = camera,
                                x = event.x,
                                y = event.y
                            )
                        }
                    }
                    adjustingExposure = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    adjustingExposure = false
                }
            }

            true
        }

        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private data class BackCameraCandidate(
    val cameraId: String,
    val selector: CameraSelector,
    val minimumFocusDistance: Float,
    val focalLength: Float
)

private const val MACRO_MAX_ZOOM_RATIO = 2f
private const val MIN_ZOOM_RATIO = 1f
private const val MAX_ZOOM_RATIO = 10f
private const val EXPOSURE_DRAG_PIXELS_PER_STEP = 120f

private fun createBackCameraCandidate(cameraInfo: CameraInfo): BackCameraCandidate? {
    val camera2Info = Camera2CameraInfo.from(cameraInfo)
    val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
        return null
    }

    val cameraId = camera2Info.cameraId
    val minimumFocusDistance = camera2Info
        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        ?: 0f
    val focalLength = camera2Info
        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.minOrNull()
        ?: 0f

    val selectorBuilder = CameraSelector.Builder()
    Camera2Interop.Extender(selectorBuilder).setCameraId(cameraId)

    return BackCameraCandidate(
        cameraId = cameraId,
        selector = selectorBuilder.build(),
        minimumFocusDistance = minimumFocusDistance,
        focalLength = focalLength
    )
}

private fun applyZoom(camera: Camera?, requestedZoomRatio: Float) {
    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
    val clampedZoom = requestedZoomRatio.coerceIn(
        zoomState.minZoomRatio,
        zoomState.maxZoomRatio
    )
    camera.cameraControl.setZoomRatio(clampedZoom)
}

private fun applyExposure(camera: Camera?, requestedExposureIndex: Int) {
    val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return
    if (range.lower == 0 && range.upper == 0) return

    val clampedIndex = requestedExposureIndex.coerceIn(range.lower, range.upper)
    camera.cameraControl.setExposureCompensationIndex(clampedIndex)
}

private fun startFocusAndMetering(
    previewView: PreviewView,
    camera: Camera,
    x: Float,
    y: Float
) {
    val meteringPointFactory = previewView.meteringPointFactory
    val focusPoint = meteringPointFactory.createPoint(x, y)

    val action = FocusMeteringAction.Builder(
        focusPoint,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    )
        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    camera.cameraControl.startFocusAndMetering(action)
}

private fun calculateExposureIndex(
    range: Range<Int>?,
    baseIndex: Int,
    verticalDelta: Float
): Int? {
    if (range == null || (range.lower == 0 && range.upper == 0)) {
        return null
    }

    val steps = (verticalDelta / EXPOSURE_DRAG_PIXELS_PER_STEP).roundToInt()
    return (baseIndex + steps).coerceIn(range.lower, range.upper)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTextScreen(content: String, onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("扫描结果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            SelectionContainer {
                Text(
                    text = content,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("QR Code", content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("复制")
            }
        }
    }
}
