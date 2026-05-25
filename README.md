# 腕上RSS - 手机端

<div align="center">

**OPPO Watch RSS 阅读器的手机伴侣应用**

[![下载最新版本](https://img.shields.io/badge/下载-最新版本-blue?style=for-the-badge)](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases)

[📥 前往 Releases 页面下载 APK](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases)

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

1. 从 [Releases 页面](https://github.com/LightningLion-Studio/Android_WatchRSS_Phone/releases) 下载最新版本的 APK
2. 在手机上安装应用
3. 在手表端打开“连接手机 > 蓝牙同步”
4. 在手机端选择发送 RSS、同步收藏或同步稍后观看
5. 手机通过蓝牙连接手表并完成数据交换

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

- **开发语言**: Kotlin 2.0.21
- **UI 框架**: Jetpack Compose + Material3
- **构建工具**: Gradle 8.13
- **核心库**:
  - Android Bluetooth RFCOMM - 手机与手表蓝牙数据通道
  - Room - 本地文章、收藏、稍后观看缓存
  - OkHttp + Jsoup - 网页抓取与正文提取
  - Coil - 图片加载
  - ZXing Core - 联系方式二维码生成

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
- 不引入厂商闭源 SDK，仅使用 Android 公开蓝牙 API
- 蓝牙消息限制最大帧大小，避免异常大载荷

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
