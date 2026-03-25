package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightningstudio.watchrss.phone.network.NetworkManager

class ConnectedActivity : ComponentActivity() {
    private val TAG = "WatchRSS_Navigation"
    private val handler = Handler(Looper.getMainLooper())
    private var isConnected by mutableStateOf(true)
    private var abilityCode by mutableStateOf<String?>(null)
    private var abilityName by mutableStateOf<String?>(null)
    private var abilityVersion by mutableStateOf<String?>(null)

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            NetworkManager.checkHealth { success ->
                runOnUiThread {
                    if (!success && isConnected) {
                        isConnected = false
                    } else if (success && !isConnected) {
                        isConnected = true
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ip = intent.getStringExtra("ip") ?: ""
        val port = intent.getStringExtra("port") ?: ""

        NetworkManager.setBaseUrl(ip, port.toInt())

        // 获取能力信息
        NetworkManager.getAbility { ability ->
            runOnUiThread {
                if (ability != null) {
                    abilityCode = ability.code
                    abilityName = ability.name
                    abilityVersion = ability.version
                    handleAbility(ability.code, ability.name, ability.version)
                }
            }
        }

        // 开始健康检查
        handler.post(healthCheckRunnable)

        setContent {
            WatchRSSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConnectedScreen(
                        isConnected = isConnected,
                        abilityName = abilityName,
                        onRescan = {
                            startActivity(Intent(this, QRScanActivity::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(healthCheckRunnable)
    }

    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun handleAbility(code: String, name: String, version: String) {
        Log.i(TAG, "=== Handle Ability Started ===")
        Log.i(TAG, "Ability Code: $code")
        Log.i(TAG, "Ability Name: $name")
        Log.i(TAG, "Ability Version: $version")

        val knownAbilities = mapOf(
            "dc40517c-a09c-419c-8c4d-d3883258992e" to Pair("0.0.1", RSSInputActivity::class.java),
            "c4bf141f-b0de-46f7-a661-0a3ad0716bce" to Pair("0.0.1", FavoritesActivity::class.java),
            "f1aa43bd-0fe3-4771-ae6b-d4799ecf84b5" to Pair("0.0.1", WatchLaterActivity::class.java),
            "a3e72c1d-5f84-4b90-9d16-e8c047f2b3a1" to Pair("0.0.1", LLMConfigActivity::class.java)
        )

        val ability = knownAbilities[code]
        if (ability != null) {
            val (expectedVersion, activityClass) = ability
            val versionComparison = compareVersion(version, expectedVersion)

            Log.d(TAG, "=== Ability Matched ===")
            Log.d(TAG, "Target Activity: ${activityClass.simpleName}")
            Log.d(TAG, "Expected Version: $expectedVersion")
            Log.d(TAG, "Actual Version: $version")
            Log.d(TAG, "Version Comparison: $versionComparison")

            if (versionComparison == 0) {
                // 版本匹配，跳转到对应功能
                Log.i(TAG, "=== Preparing Navigation ===")
                Log.i(TAG, "Target: ${activityClass.simpleName}")
                Log.i(TAG, "Delay: 1500ms")

                handler.postDelayed({
                    Log.i(TAG, "=== Starting Activity ===")
                    Log.i(TAG, "Activity: ${activityClass.simpleName}")
                    Log.i(TAG, "Timestamp: ${System.currentTimeMillis()}")

                    startActivity(Intent(this, activityClass))

                    Log.d(TAG, "=== Activity Started ===")
                    Log.d(TAG, "Finishing ConnectedActivity")
                    finish()
                }, 1500)
            } else {
                // 版本不匹配
                Log.w(TAG, "=== Version Mismatch ===")
                Log.w(TAG, "Expected: $expectedVersion")
                Log.w(TAG, "Actual: $version")
                Log.w(TAG, "Comparison Result: $versionComparison")

                runOnUiThread {
                    val message = if (versionComparison > 0) {
                        "手表版本较新，请更新手机App"
                    } else {
                        "手机版本较新，请更新手表App"
                    }
                    setContent {
                        WatchRSSTheme {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                VersionMismatchScreen(message = message)
                            }
                        }
                    }
                }
            }
        } else {
            // 未知功能
            Log.w(TAG, "=== Unknown Ability ===")
            Log.w(TAG, "Code: $code")
            Log.w(TAG, "Name: $name")

            runOnUiThread {
                setContent {
                    WatchRSSTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            UnknownFeatureScreen(featureName = name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedScreen(
    isConnected: Boolean,
    abilityName: String?,
    onRescan: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedVisibility(
            visible = !isConnected,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "连接已断开",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(800)) +
                    scaleIn(
                        initialScale = 0.8f,
                        animationSpec = tween(800, easing = FastOutSlowInEasing)
                    )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "✅",
                    fontSize = 72.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "手表已连接",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (abilityName != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在加载 $abilityName...",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        AnimatedVisibility(
            visible = !isConnected,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Button(
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("再次扫码")
            }
        }
    }
}

@Composable
fun VersionMismatchScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "版本不匹配",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun UnknownFeatureScreen(featureName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "功能不可用",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "功能\"$featureName\"不可用，请更新手机App",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
