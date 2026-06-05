package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 应用入口 Activity。
 * 接收所有外部 Intent（分享链接、打开文件等），并立即转发给 HomeActivity。
 * HomeActivity 作为首页根 Tab，根页面返回交给系统处理，以保留预测返回到桌面的预览动画。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(HomeActivity.createIntent(this, intent))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startActivity(HomeActivity.createIntent(this, intent))
        finish()
        overridePendingTransition(0, 0)
    }
}
