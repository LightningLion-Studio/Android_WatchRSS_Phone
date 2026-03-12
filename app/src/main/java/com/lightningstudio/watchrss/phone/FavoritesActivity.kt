package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightningstudio.watchrss.phone.model.FavoriteItem
import com.lightningstudio.watchrss.phone.network.NetworkManager

class FavoritesActivity : ComponentActivity() {
    private val TAG = "WatchRSS_Favorites"

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "=== FavoritesActivity onCreate ===")
        Log.i(TAG, "Timestamp: ${System.currentTimeMillis()}")
        Log.d(TAG, "Initializing Compose UI")

        super.onCreate(savedInstanceState)

        Log.d(TAG, "Setting content view")
        setContent {
            WatchRSSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FavoritesScreen(
                        onBack = {
                            Log.d(TAG, "Back button clicked")
                            finish()
                        },
                        onItemClick = { item ->
                            Log.d(TAG, "Item clicked: ${item.title}")
                            Log.d(TAG, "Opening URL: ${item.link}")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.link))
                            startActivity(intent)
                        }
                    )
                }
            }
        }

        Log.i(TAG, "=== FavoritesActivity onCreate Complete ===")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onItemClick: (FavoriteItem) -> Unit
) {
    val TAG = "WatchRSS_Favorites"

    Log.d(TAG, "=== FavoritesScreen Composing ===")

    var favorites by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    fun loadFavorites() {
        Log.i(TAG, "=== Load Favorites Started ===")
        Log.d(TAG, "Setting isRefreshing = true")
        isRefreshing = true

        NetworkManager.getFavorites { response ->
            Log.d(TAG, "=== Load Favorites Callback ===")
            Log.d(TAG, "Response Success: ${response?.success}")
            Log.d(TAG, "Items Count: ${response?.data?.size ?: 0}")

            isLoading = false
            isRefreshing = false

            if (response?.success == true) {
                favorites = response.data ?: emptyList()
                Log.i(TAG, "Favorites loaded: ${favorites.size} items")
            } else {
                Log.w(TAG, "Failed to load favorites")
            }
        }
    }

    LaunchedEffect(Unit) {
        Log.i(TAG, "=== FavoritesScreen Mounted ===")
        Log.d(TAG, "Triggering initial load")
        loadFavorites()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadFavorites() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                favorites.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "暂无收藏",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(favorites) { item ->
                            FavoriteItemCard(
                                item = item,
                                onClick = { onItemClick(item) }
                            )
                        }
                    }
                }
            }

            if (isRefreshing && !isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
fun FavoriteItemCard(
    item: FavoriteItem,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(500)) +
                slideInVertically(
                    initialOffsetY = { 50 },
                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = item.summary,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.channelTitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = item.pubDate.take(10),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
