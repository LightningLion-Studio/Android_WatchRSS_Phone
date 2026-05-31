package com.lightningstudio.watchrss.phone.ui.theme

import androidx.compose.ui.graphics.Color

// 主色 RGB(226, 39, 52)
val PrimaryRed = Color(0xFFE22734)
val PrimaryRedLight = Color(0xFFFF6B6B)
val PrimaryRedDark = Color(0xFFB71C1C)

// 渐变系列（基于主色衍生）
val GradientStart = Color(0xFFE22734)      // 主色
val GradientMid = Color(0xFFEF5350)       // 稍淡
val GradientEnd = Color(0xFFFFCDD2)        // 很淡的粉色

// 深色模式 - 改进版（避免纯黑，使用 Material 3 推荐的深灰层级）
val DarkBackground = Color(0xFF121212)        // 推荐深色背景（非纯黑）
val DarkSurface = Color(0xFF1E1E1E)         // 卡片/表面背景
val DarkSurfaceElevated = Color(0xFF2C2C2C)  // 提升层级（如展开菜单）
val DarkCardStart = Color(0xFF2C2C2C)        // 中性灰色渐变起始
val DarkCardEnd = Color(0xFF1E1E1E)          // 中性灰色渐变结束

// 深色模式主色调整（降低饱和度，更柔和）
val DarkPrimary = Color(0xFFFF5252)          // 更亮更柔和的红
val DarkPrimaryContainer = Color(0xFFCF222E) // 容器色
val DarkOnPrimaryContainer = Color(0xFFFFDAD6) // 暖白，协调

// 浅色模式
val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)
val LightCardStart = Color(0xFFFFEBEE)     // 很淡的粉红
val LightCardEnd = Color(0xFFFFF5F5)       // 几乎白色带粉调

// 通用
val OnPrimary = Color(0xFFFFFFFF)
val OnBackgroundLight = Color(0xFF1A1A1A)
val OnBackgroundDark = Color(0xFFE0E0E0)
val OnSurfaceVariantLight = Color(0xFF666666)
val OnSurfaceVariantDark = Color(0xFFA0A0A0)

// 卡片高光边缘（半透明）
val CardHighlightLight = Color(0x40FFFFFF)   // 白色半透明
val CardHighlightDark = Color(0x40FF6B6B)    // 亮红半透明（深色下更明显）
