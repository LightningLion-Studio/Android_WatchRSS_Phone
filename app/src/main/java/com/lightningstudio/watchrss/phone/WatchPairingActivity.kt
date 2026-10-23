package com.lightningstudio.watchrss.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchQrNotOpenedReason
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchQrRequest
import com.lightningstudio.watchrss.phone.pairing.PairingCameraPermission
import com.lightningstudio.watchrss.phone.pairing.PairingQrAnalyzer
import com.lightningstudio.watchrss.phone.pairing.WatchPairingState
import com.lightningstudio.watchrss.phone.pairing.WatchPairingViewModel
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import java.util.concurrent.Executors

/** Scan the watch's pairing code: signs the watch in and hands it the cloud key in one go. */
class WatchPairingActivity : ComponentActivity() {
    private val viewModel: WatchPairingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                (application as PhoneCompanionApplication).container.createWatchPairingViewModel() as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRssPhoneTheme {
                WatchPairingScreen(
                    viewModel = viewModel,
                    onClose = { viewModel.cancel(); finish() },
                    onOpenCloudSync = { startActivity(AccountActivity.createIntent(this, section = AccountSection.CLOUD_SYNC)) }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.cancel()
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, WatchPairingActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchPairingScreen(viewModel: WatchPairingViewModel, onClose: () -> Unit, onOpenCloudSync: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val watchQr by viewModel.watchQrRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val granted = { ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val activity = context as? ComponentActivity
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        viewModel.onCameraPermissionResult(ok, activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true)
    }
    LaunchedEffect(state) {
        if ((state as? WatchPairingState.Scanning)?.permission == PairingCameraPermission.REQUEST_REQUIRED)
            permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Returning from system settings re-checks the permission instead of asking again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val permission = (state as? WatchPairingState.Scanning)?.permission
        if ((permission == PairingCameraPermission.BLOCKED || permission == PairingCameraPermission.DENIED) && granted())
            viewModel.onCameraPermissionResult(true)
    }
    BackHandler(onBack = onClose)
    // Opening this page already asks the watch to show its code; the model ignores repeats on recreation.
    LaunchedEffect(Unit) { viewModel.onPageEntered() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("扫码绑定手表") }, navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = state) {
                WatchPairingState.Idle -> {
                    Lead("扫描手表上的二维码")
                    WatchQrStatus(watchQr, onAgain = { viewModel.requestWatchQrAgain() })
                    Muted("绑定后，手表会登录当前账号，云同步立即可用。两台设备会显示同一组 8 位数字供你核对。")
                    Button(onClick = { viewModel.startScan(granted()) }, modifier = Modifier.fillMaxWidth()) { Text("开始扫码") }
                }
                WatchPairingState.PreparingCloud -> Progress("正在检查这台手机的云同步…")
                is WatchPairingState.NeedsKey -> {
                    Lead(if (current.existingCloud) "这台手机还没有云密钥" else "先开通云同步")
                    Muted(if (current.existingCloud) "用 24 个恢复词恢复，或请已登录的设备批准这台手机。完成后会自动继续扫码。"
                        else "开通时请抄下 24 个恢复词。完成后会自动继续扫码，手表绑定后即可云同步。")
                    Button(onClick = onOpenCloudSync, modifier = Modifier.fillMaxWidth()) { Text("前往云同步") }
                }
                is WatchPairingState.Scanning -> when (current.permission) {
                    PairingCameraPermission.GRANTED -> {
                        PairingCamera(viewModel)
                        WatchQrStatus(watchQr, onAgain = { viewModel.requestWatchQrAgain() })
                        Muted("双指缩放取景，点按画面对焦。二维码只在当前配对页面有效，请勿分享。")
                    }
                    PairingCameraPermission.DENIED -> {
                        Lead("需要相机权限才能扫码")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("允许使用相机") }
                    }
                    PairingCameraPermission.BLOCKED -> {
                        Lead("相机权限已关闭")
                        Muted("请在系统设置里允许腕上RSS使用相机，返回后即可扫码。")
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }, modifier = Modifier.fillMaxWidth()) { Text("打开系统设置") }
                    }
                    else -> Progress("正在请求相机权限…")
                }
                WatchPairingState.Connecting -> Progress("正在连接手表…")
                is WatchPairingState.Confirming -> {
                    Lead("核对手表上的数字")
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            current.sas.let { if (it.length == 8) "${it.take(4)} ${it.takeLast(4)}" else it },
                            style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }
                    val waitingForWatch = current is WatchPairingState.AwaitingWatchConfirm
                    if (waitingForWatch) {
                        // The watch answers only after its own user confirms; until then the phone just shows the digits.
                        Text("数字一致的话，先在手表上点「一致，绑定」。", style = MaterialTheme.typography.bodyLarge)
                        Progress("等待手表确认…")
                    } else {
                        Muted("手表已确认。手机上的数字与手表一致才点“一致”；不一致说明扫错了手表或有人冒充，请点“不一致”。")
                    }
                    Button(onClick = { viewModel.confirmSas(true) }, enabled = !waitingForWatch, modifier = Modifier.fillMaxWidth()) { Text("一致") }
                    OutlinedButton(onClick = { viewModel.confirmSas(false) }, modifier = Modifier.fillMaxWidth()) { Text("不一致") }
                }
                is WatchPairingState.Configuring -> {
                    Progress(current.step)
                    Muted("请保持手机和手表的配对页面打开。")
                }
                is WatchPairingState.Done -> {
                    Lead(when {
                        current.cloudReady -> "手表已绑定，云同步已可用"
                        current.bound -> "手表已绑定，云同步授权未完成"
                        else -> "绑定未完成"
                    })
                    if (current.cloudReady) Muted("手表已登录当前账号，并已保存云密钥。")
                    current.cloudError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    current.finishError?.let { Muted("$it 账号和云密钥已保存，无需重新绑定。") }
                    if (current.canRetry) Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) { Text("重试云同步授权") }
                    (if (current.canRetry) OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                        else Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("完成") })
                }
                is WatchPairingState.Failed -> {
                    Lead("绑定没有完成")
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    if (current.canRetry) Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                }
            }
        }
    }
}

/** Preview plus analyzer, bound only while this composable is on screen. Pinch zooms, a tap focuses. */
@Composable
private fun PairingCamera(viewModel: WatchPairingViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            provider = providerFuture.get().also {
                val preview = Preview.Builder().build().also { use -> use.surfaceProvider = previewView.surfaceProvider }
                // A fresh analyzer per binding: late frames from an old session cannot reach a new one.
                val analysis = PairingQrAnalyzer.imageAnalysis(analysisExecutor, ContextCompat.getMainExecutor(context), viewModel.qrCallback())
                it.unbindAll()
                camera = it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { camera = null; provider?.unbindAll(); analysisExecutor.shutdown() }
    }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    DisposableEffect(camera, lifecycleOwner) {
        val state = camera?.cameraInfo?.zoomState
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomRatio = it.zoomRatio }
        state?.observe(lifecycleOwner, observer)
        onDispose { state?.removeObserver(observer) }
    }
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Surface(color = Color.Transparent, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.7f)) {}
        Box(modifier = Modifier.fillMaxSize()
            .pointerInput(camera) {
                detectTransformGestures { _, _, scale, _ ->
                    val current = camera ?: return@detectTransformGestures
                    val state = current.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    current.cameraControl.setZoomRatio((state.zoomRatio * scale).coerceIn(state.minZoomRatio, state.maxZoomRatio))
                }
            }
            .pointerInput(camera) {
                detectTapGestures { offset ->
                    val current = camera ?: return@detectTapGestures
                    val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                    current.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                }
            })
        if (zoomRatio > 1.05f) {
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Text(String.format(java.util.Locale.US, "%.1f×", zoomRatio), color = Color.White,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** Whether the phone could make the watch open its pairing code; the manual path is always spelled out. */
@Composable
private fun WatchQrStatus(request: WatchQrRequest?, onAgain: () -> Unit) {
    val manual = "请在手表上进入「我的 → 连接手机 → 扫码绑定」。"
    when (request) {
        WatchQrRequest.Sending -> Progress("正在让手表弹出二维码…")
        WatchQrRequest.Opened -> Text("手表已弹出二维码，对准扫描即可。", style = MaterialTheme.typography.bodyLarge)
        is WatchQrRequest.NotOpened -> Text(when (request.reason) {
            WatchQrNotOpenedReason.NOT_PAIRED -> "没找到已配对的手表。"
            WatchQrNotOpenedReason.BLUETOOTH_OFF -> "手机蓝牙未开启。"
            WatchQrNotOpenedReason.NO_RESPONSE -> "手表没有响应，可能腕上RSS未在前台。"
            WatchQrNotOpenedReason.UNSUPPORTED_WATCH -> "手表版本较旧，不能自动弹出二维码。"
            WatchQrNotOpenedReason.PERMISSION_MISSING -> "没有蓝牙权限，无法通知手表。"
        } + manual, style = MaterialTheme.typography.bodyLarge)
        null -> Muted("如果手表没有弹出二维码，$manual")
    }
    if (request is WatchQrRequest.NotOpened) TextButton(onClick = onAgain) { Text("重新让手表弹出二维码") }
}

@Composable private fun Lead(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
@Composable private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun Progress(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
