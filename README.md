# 腕上RSS - 手机端

<div align="center">

**OPPO Watch RSS 阅读器的手机伴侣应用**

[![下载最新版本](https://img.shields.io/badge/下载-最新版本-blue?style=for-the-badge)](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases)

[📥 前往 Releases 页面下载 APK](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases)

</div>

---

## 📖 项目简介

腕上RSS 手机端是配合 OPPO Watch 上的 RSS 阅读器使用的伴侣应用。通过扫描手表生成的二维码，您可以在手机上轻松管理 RSS 订阅源、查看收藏和稍后观看列表。

## ✨ 主要功能

- 🔍 **二维码扫描连接** - 扫描手表生成的二维码快速建立连接
- 📡 **远程输入 RSS 链接** - 在手机上输入 RSS 订阅地址，同步到手表
- ⭐ **收藏管理** - 查看和管理在手表上收藏的文章
- 🕐 **稍后观看** - 管理标记为稍后观看的内容
- 🎨 **Material Design 3** - 现代化的界面设计，支持深色模式

## 📱 系统要求

- Android 11 (API 30) 或更高版本
- 相机权限（用于扫描二维码）
- 网络权限（用于与手表通信）
- 需要与 OPPO Watch 处于同一 WiFi 网络

## 🚀 快速开始

### 用户使用

1. 从 [Releases 页面](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases) 下载最新版本的 APK
2. 在手机上安装应用
3. 打开应用，点击"前往扫一扫"
4. 扫描 OPPO Watch 上显示的二维码
5. 连接成功后即可开始使用

### 开发者构建

```bash
# 克隆仓库
git clone https://github.com/LightningLion-Studio/Android_WatchRSS_Phone.git
cd Android_WatchRSS_Phone

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到连接的设备
./gradlew installDebug

# 构建 Release 版本
./gradlew assembleRelease
```

## 🛠️ 技术栈

- **开发语言**: Kotlin 1.9.21
- **UI 框架**: Jetpack Compose + Material3
- **构建工具**: Gradle 8.2
- **核心库**:
  - CameraX - 相机功能
  - ML Kit - 二维码识别
  - OkHttp - 网络请求
  - Gson - JSON 解析
  - Coil - 图片加载
  - ZXing - 二维码生成

## 📂 项目结构

```
app/src/main/java/com/lightningstudio/watchrss/phone/
├── MainActivity.kt              # 主界面
├── QRScanActivity.kt           # 二维码扫描
├── ConnectedActivity.kt        # 连接后的主界面
├── RSSInputActivity.kt         # RSS 输入界面
├── FavoritesActivity.kt        # 收藏列表
├── WatchLaterActivity.kt       # 稍后观看列表
├── AboutActivity.kt            # 关于页面
├── ContactDeveloperActivity.kt # 联系开发者
├── model/
│   └── Models.kt               # 数据模型
└── network/
    └── NetworkManager.kt       # 网络管理器
```

## 🔌 API 接口

应用通过 HTTP 与手表通信，基础 URL 格式：`http://{ip}:{port}`

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/getCurrentActivationAbility` | GET | 获取手表应用信息 |
| `/remoteEnterRSSURL` | POST | 发送 RSS 链接到手表 |
| `/getFavorites` | GET | 获取收藏列表 |
| `/getWatchlaterList` | GET | 获取稍后观看列表 |

## 🔐 安全说明

- 应用使用本地网络通信（HTTP），无需互联网连接
- 手机和手表必须在同一 WiFi 网络下
- 二维码包含 Base64 编码的连接信息（IP:端口）
- 无用户认证机制，依赖本地网络安全

## 📄 许可证

本项目采用开源许可证（请根据实际情况添加具体许可证信息）

## 👨‍💻 开发团队

Lightning Lion Studio

## 🐛 问题反馈

如遇到问题或有功能建议，请在 [Issues](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/issues) 页面提交。

## 📮 联系方式

- 备案号：浙ICP备2024111886号-5A

---

<div align="center">

**[⬆ 返回顶部](#腕上rss---手机端)**

Made with ❤️ by Lightning Lion Studio

</div>
