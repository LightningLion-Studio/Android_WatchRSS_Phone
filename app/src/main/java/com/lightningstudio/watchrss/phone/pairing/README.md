# 手机扫码绑定：Lead 界面接入

此包只实现联网手表绑定及云密钥交付。文章库连接方式仍为原有蓝牙。布局、入口、相机 Preview 和权限 launcher 由 Lead 接入。

1. 用 `PhoneCompanionContainer.createWatchPairingViewModel()` 在页面的 ViewModelStore 中创建并保留 ViewModel；不要在重组时反复创建。进入页面调用 `onPageEntered()` 通知手表弹码；不调用 `startScan`，不申请相机或蓝牙权限。该方法有 ViewModel 级防重，旋转时即使再次调用也不重发。
2. 用户点击“扫码绑定手表”才调用 `startScan(cameraPermissionGranted = 当前 OS 权限)`。先显示 PreparingCloud。NeedsKey(existingCloud) 引导用户开通或恢复云密钥；完成后观察器会自动续接扫码，不必重新开始会话。
3. 只有 Scanning(REQUEST_REQUIRED) 才启动 CAMERA permission launcher，把结果交给 `onCameraPermissionResult(granted, canAskAgain)`。DENIED 可显式点击重试，BLOCKED 引导系统设置；从设置返回重新核对权限。不要在其它状态启动相机。
4. Scanning(GRANTED) 时，把 CameraX Preview 和 `PairingQrAnalyzer.imageAnalysis(analysisExecutor, mainExecutor, viewModel.qrCallback())` 一起 bindToLifecycle。每次新扫码绑定创建新的 analyzer，并在绑定时取得 `qrCallback()`，避免旧预览队列的迟到帧进入新会话。离开 Scanning 就解绑相机；不得保存 QR 原文、secret、capability 或帧到日志、磁盘、SavedStateHandle。
5. claim 成功后立即进入 `AwaitingWatchConfirm(sas)`，不等待 prepare 回包才显示完整 8 位数字（保留前导零）。显示 `message`：“核对一致后，先在手表上点‘一致，绑定’”，可取消/选择不一致；等待时不要显示可用的手机“一致”按钮。prepare 同时发送，手表确认回包且身份/公钥/本地 SAS 核验成功后进入 `Confirming.Phone(sas)`（`Confirming(sas)` 工厂构造的状态），此时再要求手机用户明确相符并调用 `confirmSas(true)`，不符调用 `confirmSas(false)`。凭据签发发生在手机确认之后。为保持原 SAS 展示分支兼容，`AwaitingWatchConfirm` 继承共同的 `Confirming`；Lead 的 when 必须把 `is AwaitingWatchConfirm` 分支放在 `is Confirming` 前，或使用 `is Confirming.Phone` 区分。旧泛型展示虽能显示 SAS，等待阶段误点“一致”会被 ViewModel 忽略。Connecting/等待手表共用单调时钟的 120 秒总预算；relay 原有 110 秒轮询和 QR 过期限制仍有效。超时进入 Failed，可重试原会话；离开/取消及账号/后端/纪元改变仍立即作废，迟到回包不能恢复确认或签发凭据。
6. Configuring(step) 显示步骤。Done 分别展示 bound 和 cloudReady：只有手表确认设备身份、密钥版本和 cloudReady 才能显示云同步可用。cloudError 不抹去已绑定账号；finishError 表示账号与云密钥已保存但结束配对未确认。canRetry=true 时调用 `retry()`，重用本会话及尚未确认的原始消息，不重新签发已确认的凭据或重做已确认的交付。超限提示改用设备审批 v2。
7. 返回、关闭、导航离开或明确取消时立即 `cancel()`，再解绑相机和关闭 executor。配置变化只重建 UI/相机并保留 ViewModel；不要因此重新 `startScan`。进程死亡不恢复机密，重新扫码。`onCleared` 也会清理；账号、后端或 epoch 改变自动作废，并忽略迟到结果。

`Done(true, true)` 为绑定及交付均完成；`Done(true, false, cloudError=...)` 只能称“已绑定，云授权待完成”。联网 relay 的 close 尽力发送；账号已切换时不借新账号关闭旧会话，依靠后端 TTL 回收。

待 Lead/设备验收：入口与文案、预览布局及无相机设备状态、拒绝/永久拒绝/设置返回、恢复词页面往返续接、前后台与旋转屏幕、真人 SAS 比码、真实 PodJS 与后端交付、弱网重试及过期说明。JVM 向量和 APK 编译不能替代这些验收。

## 自动通知手表打开二维码

`onPageEntered()` 在进入页面时并行发一次蓝牙 `openPairing`，不等待该动作才准备云/启动扫码。`startScan()` 保留已有请求与状态，通常不重发；仅之前为 NotOpened 且距上次发送严格超过 10 秒时允许重试。`requestWatchQrAgain(): Boolean` 给“重新让手表弹码”按钮使用同一冷却规则，返回是否发起。冷却使用单调时钟；重组、恢复密钥和相机权限重试不重复通知。Opened 不自动重发。不得为此请求蓝牙权限；无权限、蓝牙关闭、未配对、旧版不支持或 6 秒内没有有效确认时，扫码仍继续。请求不传账号/令牌/QR 内容，不算完成绑定。

Lead 订阅独立的 `watchQrRequest: StateFlow<WatchQrRequest?>`：初始/取消为 null；Sending 显示通知中；Opened 表示手表已确认打开界面；NotOpened(reason) 使用 `reason.message` 显示可手动打开的提示。原因有 NOT_PAIRED、BLUETOOTH_OFF、NO_RESPONSE、UNSUPPORTED_WATCH、PERMISSION_MISSING。该状态不应遮挡相机、禁止扫码、触发蓝牙系统权限框或覆盖主配对结果；Opened 也不代表二维码已完成网络生成，更不代表账号/云授权成功。

保持现有手动路径「我的 → 连接手机 → 扫码绑定」可见。cancel/离开页面同时取消可选通知并清其状态；账号变化会作废通知，新的 startScan 只作废旧扫码帧而保留本页通知。实际 PodJS openPairing 联调、蓝牙权限/关闭/未配对、连接超时和旋转屏幕仍需设备验收。

识码已改为 zxing-cpp Android 3.1.1 的 BarcodeReader.read(ImageProxy)，只读 QR，启用 tryHarder/tryRotate/tryInvert/tryDownscale/tryDenoise。ImageAnalysis 请求 1280×960、优先 4:3，设备不支持时按 CameraX 策略回退。使用 CameraX 管理的默认 CONTINUOUS_PICTURE 自动对焦及点按后恢复，不固定覆盖 Camera2 AF_MODE。Lead 已实现的双指缩放/点按对焦保留；本任务不再提供手势 helper，也不改 Activity。

本地单测的 native 图像回归需要 Linux、Python 3.12+、CMake、C++20 编译器、JDK。Gradle 自动校验并构建同版 upstream JNI/core（测试用 AndroidBitmap stub 只拒绝 Bitmap 路径），由 Robolectric 运行真实 AAR BarcodeReader.read(ImageProxy)。测试宿主库不会打进 APK。PNG 已入仓，无需装图像工具即可跑单测；重新生成图集才需要 qrencode、Pillow、NumPy、OpenCV。
