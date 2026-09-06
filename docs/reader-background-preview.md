# 阅读器背景预处理与实时预览

## 资源约定

- 手机保留原文件，生成供手表使用的 `watch` 变体；视频同时生成 `watchPoster`。
- 完整画面按原比例放进手表宽、高各 2 倍的边界框，不裁剪、不放大小素材。图片先应用 EXIF，视频先计算显示方向；视频尺寸向下对齐偶数，余下比例差使用留白。
- 图片为无增益图的 sRGB SDR PNG，保留透明度。视频通过 Media3 OpenGL 色调映射输出 SDR H.265/H.264，按实际输出尺寸选择手表硬件解码能力；无法满足条件明确失败。
- 视频无音轨、最长 60 秒，帧率不高于源视频、手表刷新率、解码器上限和 60 fps。封面从完成转换的视频提取。
- 缓存包含处理版本、源 SHA-256、手表尺寸、实际输出尺寸、颜色和编码信息；旧 1× 裁剪变体失效。位置、缩放和颜色编辑不改变资源身份。
- 预览只推送缺失的处理后背景变体；普通库同步继续保留原文件的同步语义。

## 预览生命周期

- 分别显示连接、预处理、资源传输和预览状态。初始化期间可取消；资源准备不受旧的 20 秒首连等待限制。
- 处理过程中每 30 秒刷新手表资源交接状态。单次编码最长 180 秒；取消时先在主线程释放 Transformer，再清理临时文件。
- 确定性处理错误直接结束预览，不进入蓝牙无限重连。关闭或快速重新开启预览后，旧任务不能覆盖新状态。
- 手表按不可变资源路径复用 ExoPlayer，静音播放，使用预设的循环与速度设置。参数变化只调整矩阵和绘制效果；TextureView 始终保持显示窗口大小。
- 图片与视频使用相同的适配、焦点、缩放和旋转规则。Android 11 使用临时图片模糊与 Media3 视频 GPU 模糊；较新系统沿用系统模糊。原资源文件不会被这些绘制效果修改。
- 视频首帧前或播放失败时保留封面；页面暂停时停止出帧，离开时释放播放器。

## 验证记录（2026-09-05）

- 手机 JVM：`WatchBackgroundSizeTest` 5 项、`ReaderPresetPreviewPayloadTest` 8 项通过。
- 手机模拟器 Android 16：`WatchBackgroundTranscoderTest` 10 项通过，包含图片完整边缘/透明度/EXIF/Ultra HDR、缓存更新、SDR 视频与封面、视频旋转、目标尺寸解码器选择、缺失原文件的库同步恢复、取消、无解码器拒绝和 HDR 不支持时拒绝。
- 对模拟器生成的实际 MP4 使用 ffprobe 检查：输入 960×540，目标手表 200×200，输出 400×224，H.264、8-bit yuv420p、BT.709 SDR、24 fps、无音轨；时长 3.958 秒，大小 140227 字节。封面保留完整测试图。
- 手表 JVM：背景几何 3 项、预览会话 4 项通过。
- 手表设备 OWW242 / Android 11：`ReaderVideoBackgroundTest` 4 项全部通过；验证播放帧实际变化、缩放时播放器/播放视图复用、前后台暂停恢复、资源切换、图片与视频四角定位，以及播放中图片/视频模糊的实际像素。验证时临时模拟断开充电以去除系统充电遮罩，结束后已恢复电池状态。

## 真手机补充验证（2026-09-05，当地时间）

- 三星 SM-F9560 / Android 16：安装手机调试版后，`WatchBackgroundTranscoderTest` 10 项全部通过，耗时 4.424 秒。
- HDR PQ 样本成功输出；从手机提取成品经 ffprobe 确认为 H.264、400×224、8-bit yuv420p、BT.709 色域/传递函数、24 fps。此结果覆盖真手机 HDR 成功转码路径；模拟器结果仍用于验证不支持时明确拒绝。
- 手机预设编辑页开启实时预览后，OWW242 实际进入 `ReaderPresetPreviewActivity`；手机日志确认 RFCOMM 连接、预览更新 ACK 成功及持续心跳。退出编辑页结束预览。

**尚未验收的边界：** 本次真实蓝牙检查使用现有预设，只确认预览连接与更新链路；尚未完成真实蓝牙视频资源切换、连续拖动延迟及拖动期间无资源分块传输的整体验收。

## 重跑

手机：

```sh
./gradlew :app:testDebugUnitTest --tests '*WatchBackgroundSizeTest' --tests '*ReaderPresetPreviewPayloadTest' :app:assembleDebug :app:assembleDebugAndroidTest
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s PHONE_SERIAL shell am instrument -w -e class com.lightningstudio.watchrss.phone.data.reader.WatchBackgroundTranscoderTest com.lightningstudio.watchrss.phone.test/com.karumi.shot.ShotTestRunner
```

手表仓库：

```sh
ANDROID_SERIAL=WATCH_SERIAL ./gradlew :app:installDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*ReaderBackgroundPlaneTest' --tests '*WatchReaderPresetPreviewSessionTest'
adb -s WATCH_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s WATCH_SERIAL shell am instrument -w -e class com.lightningstudio.watchrss.ui.reader.ReaderVideoBackgroundTest com.lightningstudio.watchrss.test/com.karumi.shot.ShotTestRunner
```

测试媒体均为本地 FFmpeg 合成的色条/四色方格，无用户媒体，放在 `src/androidTest/assets/reader-backgrounds/`，不会进入正式 APK。`sdr.mp4` 为 960×540、24 fps 的 `testsrc2`；`hdr-pq.mp4` 使用 zscale 将 BT.709 转 BT.2020/PQ，再以 10-bit HEVC 编码；`rotated.mp4` 使用 FFmpeg 9 的 `-display_rotation:v:0 90` 对 SDR 样本无损重封装。`corners.mp4` 为红、绿、蓝、黄四象限静态测试视频，用于检查视频显示位置和模糊过渡。图片及 Ultra HDR 增益图在测试时生成。
