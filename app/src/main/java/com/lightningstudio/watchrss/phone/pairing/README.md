# 手机扫码绑定：Lead 界面接入

此包只实现联网手表绑定及云密钥交付。文章库连接方式仍为原有蓝牙。布局、入口、相机 Preview 和权限 launcher 由 Lead 接入。

1. 用 `PhoneCompanionContainer.createWatchPairingViewModel()` 在页面的 ViewModelStore 中创建并保留 ViewModel；不要在重组时反复创建。订阅 `state`，进入页面不调用 `startScan`，不申请权限。
2. 用户点击“扫码绑定手表”才调用 `startScan(cameraPermissionGranted = 当前 OS 权限)`。先显示 PreparingCloud。NeedsKey(existingCloud) 引导用户开通或恢复云密钥；完成后观察器会自动续接扫码，不必重新开始会话。
3. 只有 Scanning(REQUEST_REQUIRED) 才启动 CAMERA permission launcher，把结果交给 `onCameraPermissionResult(granted, canAskAgain)`。DENIED 可显式点击重试，BLOCKED 引导系统设置；从设置返回重新核对权限。不要在其它状态启动相机。
4. Scanning(GRANTED) 时，把 CameraX Preview 和 `PairingQrAnalyzer.imageAnalysis(analysisExecutor, mainExecutor, viewModel.qrCallback())` 一起 bindToLifecycle。每次新扫码绑定创建新的 analyzer，并在绑定时取得 `qrCallback()`，避免旧预览队列的迟到帧进入新会话。离开 Scanning 就解绑相机；不得保存 QR 原文、secret、capability 或帧到日志、磁盘、SavedStateHandle。
5. Confirming(sas) 显示完整 8 位数字（保留前导零），要求用户核对手表。只有明确相符才 `confirmSas(true)`，不符调用 `confirmSas(false)`。凭据签发发生在确认之后。
6. Configuring(step) 显示步骤。Done 分别展示 bound 和 cloudReady：只有手表确认设备身份、密钥版本和 cloudReady 才能显示云同步可用。cloudError 不抹去已绑定账号；finishError 表示账号与云密钥已保存但结束配对未确认。canRetry=true 时调用 `retry()`，重用本会话及尚未确认的原始消息，不重新签发已确认的凭据或重做已确认的交付。超限提示改用设备审批 v2。
7. 返回、关闭、导航离开或明确取消时立即 `cancel()`，再解绑相机和关闭 executor。配置变化只重建 UI/相机并保留 ViewModel；不要因此重新 `startScan`。进程死亡不恢复机密，重新扫码。`onCleared` 也会清理；账号、后端或 epoch 改变自动作废，并忽略迟到结果。

`Done(true, true)` 为绑定及交付均完成；`Done(true, false, cloudError=...)` 只能称“已绑定，云授权待完成”。联网 relay 的 close 尽力发送；账号已切换时不借新账号关闭旧会话，依靠后端 TTL 回收。

待 Lead/设备验收：入口与文案、预览布局及无相机设备状态、拒绝/永久拒绝/设置返回、恢复词页面往返续接、前后台与旋转屏幕、真人 SAS 比码、真实 PodJS 与后端交付、弱网重试及过期说明。JVM 向量和 APK 编译不能替代这些验收。
