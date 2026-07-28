package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.ui.PredictiveBackSurface
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountRepository =
            (application as PhoneCompanionApplication).container.accountRepository

        setContent {
            WatchRssPhoneTheme {
                val session by accountRepository.session.collectAsState()
                val onBack = ::finish

                PredictiveBackSurface(onBack = onBack) {
                    ProfileScreen(
                        accountSummary = session?.phoneMasked ?: "未登录",
                        onBack = onBack,
                        onAccountClick = {
                            startActivity(AccountActivity.createIntent(this@ProfileActivity))
                        },
                        onFavoritesClick = {
                            startActivity(
                                ListPageActivity.createIntent(
                                    this@ProfileActivity,
                                    PageType.FAVORITES
                                )
                            )
                        },
                        onWatchLaterClick = {
                            startActivity(
                                ListPageActivity.createIntent(
                                    this@ProfileActivity,
                                    PageType.WATCH_LATER
                                )
                            )
                        },
                        onAboutClick = {
                            startActivity(Intent(this@ProfileActivity, AboutActivity::class.java))
                        },
                        onContactDeveloperClick = {
                            startActivity(
                                Intent(
                                    this@ProfileActivity,
                                    ContactDeveloperActivity::class.java
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, ProfileActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    accountSummary: String,
    onBack: () -> Unit,
    onAccountClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onWatchLaterClick: () -> Unit,
    onAboutClick: () -> Unit,
    onContactDeveloperClick: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("我的") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileEntry(
                title = "账号",
                supportingText = accountSummary,
                icon = Icons.Default.AccountCircle,
                onClick = onAccountClick
            )
            ProfileEntry(
                title = "我的收藏",
                icon = Icons.Default.Favorite,
                onClick = onFavoritesClick
            )
            ProfileEntry(
                title = "稍后再看",
                icon = Icons.Default.Bookmark,
                onClick = onWatchLaterClick
            )
            ProfileEntry(
                title = "关于",
                icon = Icons.Default.Info,
                onClick = onAboutClick
            )
            ProfileEntry(
                title = "联系开发者",
                icon = Icons.Default.SupportAgent,
                onClick = onContactDeveloperClick
            )
        }
    }
}

@Composable
private fun ProfileEntry(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supportingText: String? = null
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = supportingText?.let {
                {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}
