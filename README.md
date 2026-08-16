# 腕上RSS - 手机端

<div align="center">

**OPPO Watch 腕上RSS 阅读器的手机伴侣应用**

[![下载最新版本](https://img.shields.io/badge/下载-最新版本-blue?style=for-the-badge)](https://github.com/LightningLion-Studio/Android_腕上RSS_Phone/releases)

[📥 前往 Releases 页面下载 APK](https://github.com/LightningLion-Studio/Android_腕上RSS_Phone/releases)

</div>

---

## 📖 项目简介

腕上RSS 手机端是配合 OPPO Watch 上的 RSS 阅读器使用的伴侣应用。应用通过已配对蓝牙连接手表，您可以在手机上输入 RSS 订阅源、同步收藏和稍后观看列表。

## ✨ 主要功能

- 📡 **蓝牙同步连接** - 手机通过 Android 公开蓝牙 RFCOMM API 与已配对手表交换数据
- 📡 **远程输入 RSS 链接** - 在手机上输入 RSS 订阅地址，同步到手表
- ⭐ **收藏管理** - 查看和管理在手表上收藏的文章
- 🕐 **稍后观看** - 管理标记为稍后观看的内容
- 🎨 **Material Design 3** - 现代化的界面设计，支持深色模式

## 📱 系统要求

- Android 11 (API 30) 或更高版本
- 手机与手表已完成系统蓝牙配对
- 蓝牙连接权限

## 🚀 快速开始

### 用户使用

1. 从 [Releases 页面](https://github.com/LightningLion-Studio/Android_腕上RSS_Phone/releases) 下载最新版本的 APK
2. 在手机上安装应用
3. 在手表端打开“连接手机 > 蓝牙同步”
4. 在手机端选择发送 RSS、同步收藏或同步稍后观看
5. 手机通过蓝牙连接手表并完成数据交换

### 开发者构建

```bash
# 克隆仓库
git clone https://github.com/LightningLion-Studio/Android_腕上RSS_Phone.git
cd Android_腕上RSS_Phone

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到连接的设备
./gradlew installDebug

# 构建 Release 版本
./gradlew assembleRelease

# 截图测试 - 录制基线（首次或 UI 变更后）
./gradlew :app:executeScreenshotTests -Precord

# 截图测试 - 验证当前 UI 与基线一致
./gradlew :app:executeScreenshotTests
```

> 截图测试使用真实 `HomeActivity` 与真实 Room 数据库，通过 `ComposeTestRule` 导航、点击、输入，并用 AndroidX Test `Screenshot` + Shot 生成 HTML 报告。基线文件保存在 `app/screenshots/debug/`，需要提交到版本库用于 CI 回归比对。

## 🛠️ 技术栈

- **开发语言**: Kotlin 2.0.21
- **UI 框架**: Jetpack Compose + Material3
- **构建工具**: Gradle 8.13
- **核心库**:
  - Android Bluetooth RFCOMM - 手机与手表蓝牙数据通道
  - Room - 本地文章、收藏、稍后观看缓存
  - OkHttp + Jsoup - 网页抓取与正文提取
  - Coil - 图片加载
  - ZXing Core - 联系方式二维码生成
  - OPPO Push SDK (com.heytap.msp 3.7.1) - OPPO 系统推送通知

## 📂 项目结构

```
app/src/main/java/com/lightningstudio/watchrss/phone/
├── MainActivity.kt              # 主界面
├── AboutActivity.kt            # 关于页面
├── ContactDeveloperActivity.kt # 联系开发者
├── acoustic/                   # 旧声波调试/兼容代码
├── connection/                 # 蓝牙同步与兼容连接协议
├── data/                       # 本地数据库与仓库
└── ui/                         # Compose 主界面
```

## 🔌 连接协议

应用只保留蓝牙同步作为正式连接方式：手表端开启 RFCOMM 服务，手机端连接已配对手表并发送长度前缀 JSON 帧。

| 动作 | 说明 |
|------|------|
| `remoteInput` | 手机发送 RSS 链接到手表 |
| `pullSavedItems` | 手机从手表拉取收藏或稍后观看列表 |
| `syncLibrary` | 手机与手表双向同步收藏、稍后再看和导入文章正文 |

## 🔐 安全说明

- 应用使用系统已配对蓝牙连接，不依赖互联网或本地 WiFi
- 蓝牙同步通道仅使用 Android 公开 RFCOMM API;系统推送采用 OPPO 官方 Push SDK(详见「开源许可证」章节)
- 蓝牙消息限制最大帧大小，避免异常大载荷

## 📄 开源许可证

本项目本体采用 [MIT License](LICENSE)(Copyright © 2026 LightningLion-Studio):可自由使用、修改与分发,但需保留版权声明与许可文本。

此外,OPPO 推送能力使用 **OPPO Push SDK(com.heytap.msp 3.7.1)**,其为 OPPO 官方提供的闭源专有组件,以 aar 形式随仓库分发(`app/libs/`),仅按 OPPO 开放平台协议用于推送通知。

应用依赖的第三方开源组件遵循其各自的许可证,主要包括:

| 组件 | 许可证 |
|------|--------|
| AndroidX / Jetpack Compose / Room / Media3 | Apache-2.0 |
| Kotlin 与 kotlinx.coroutines | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Coil | Apache-2.0 |
| Jsoup | MIT |
| ZXing Core | Apache-2.0 |
| NanoHTTPD | BSD-3-Clause |
| WorkManager | Apache-2.0 |

## 👨‍💻 开发团队

Lightning Lion Studio

## 🐛 问题反馈

如遇到问题或有功能建议，请在 [Issues](https://github.com/LightningLion-Studio/Android_腕上RSS_Phone/issues) 页面提交。

## 📮 联系方式

- 备案号：浙ICP备2024111886号-5A

---

<div align="center">

**[⬆ 返回顶部](#腕上rss---手机端)**

Made with ❤️ by Lightning Lion Studio

</div>
