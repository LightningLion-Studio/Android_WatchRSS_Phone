package com.lightningstudio.watchrss.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState

private enum class MainPage {
    HOME,
    RSS_SOURCES,
    FAVORITES,
    WATCH_LATER,
    INDEPENDENT,
    RSS_CHANNEL
}

@Composable
fun MainScreen(
    uiState: MainUiState,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onAddRssSource: () -> Unit,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDismissMessage: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var page by rememberSaveable { mutableStateOf(MainPage.HOME) }
    var selectedSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val articlesBySource = remember(uiState.rssArticles) {
        uiState.rssArticles.groupBy { it.rssSourceUrl.orEmpty() }
    }
    val selectedSource = uiState.rssSources.firstOrNull { it.url == selectedSourceUrl }
    val goHome = {
        selectedSourceUrl = null
        page = MainPage.HOME
    }

    BackHandler(enabled = page != MainPage.HOME) {
        goHome()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (page) {
            MainPage.HOME -> HomePage(
                uiState = uiState,
                articlesBySource = articlesBySource,
                onUrlChange = onUrlChange,
                onImportArticle = onImportArticle,
                onAddRssSource = onAddRssSource,
                onSyncLibrary = onSyncLibrary,
                onExportBluetoothLog = onExportBluetoothLog,
                onOpenRssSources = { page = MainPage.RSS_SOURCES },
                onOpenFavorites = { page = MainPage.FAVORITES },
                onOpenWatchLater = { page = MainPage.WATCH_LATER },
                onOpenIndependent = { page = MainPage.INDEPENDENT },
                onDismissMessage = onDismissMessage
            )

            MainPage.RSS_SOURCES -> RssSourcesPage(
                sources = uiState.rssSources,
                articlesBySource = articlesBySource,
                onBack = { page = MainPage.HOME },
                onOpenSource = { source ->
                    selectedSourceUrl = source.url
                    page = MainPage.RSS_CHANNEL
                }
            )

            MainPage.FAVORITES -> ArticleListPage(
                title = "收藏",
                emptyText = "暂无收藏",
                articles = uiState.favorites,
                onBack = { page = MainPage.HOME },
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater
            )

            MainPage.WATCH_LATER -> ArticleListPage(
                title = "稍后再看",
                emptyText = "暂无稍后再看",
                articles = uiState.watchLater,
                onBack = { page = MainPage.HOME },
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater
            )

            MainPage.INDEPENDENT -> ArticleListPage(
                title = "独立文章",
                emptyText = "暂无独立文章",
                articles = uiState.independentArticles,
                onBack = { page = MainPage.HOME },
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater
            )

            MainPage.RSS_CHANNEL -> ArticleListPage(
                title = selectedSource?.title ?: "RSS 频道",
                emptyText = "此频道暂无文章",
                articles = selectedSourceUrl?.let { articlesBySource[it] }.orEmpty(),
                onBack = goHome,
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater
            )
        }
    }
}

@Composable
private fun HomePage(
    uiState: MainUiState,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onAddRssSource: () -> Unit,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenRssSources: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Text(
        text = "WatchRSS 手机端",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    StatusCard(
        message = uiState.message,
        error = uiState.error,
        onDismissMessage = onDismissMessage
    )

    ImportAndSyncCard(
        urlInput = uiState.urlInput,
        enabled = !uiState.isBusy,
        onUrlChange = onUrlChange,
        onImportArticle = onImportArticle,
        onAddRssSource = onAddRssSource,
        onSyncLibrary = onSyncLibrary,
        onExportBluetoothLog = onExportBluetoothLog
    )

    LibraryEntryCard(
        rssSourceCount = uiState.rssSources.size,
        rssArticleCount = articlesBySource.values.sumOf { it.size },
        favoriteCount = uiState.favorites.size,
        watchLaterCount = uiState.watchLater.size,
        independentCount = uiState.independentArticles.size,
        onOpenRssSources = onOpenRssSources,
        onOpenFavorites = onOpenFavorites,
        onOpenWatchLater = onOpenWatchLater,
        onOpenIndependent = onOpenIndependent
    )
}

@Composable
private fun StatusCard(
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "蓝牙互联", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "导入网页、添加 RSS 源后，可手动与已配对手表双向同步资料库。",
                style = MaterialTheme.typography.bodyMedium
            )
            message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDismissMessage)
                )
            }
            error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDismissMessage)
                )
            }
        }
    }
}

@Composable
private fun ImportAndSyncCard(
    urlInput: String,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onAddRssSource: () -> Unit,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "添加内容", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text(text = "网页或 RSS 地址") },
                placeholder = { Text(text = "https://example.com/article-or-feed.xml") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImportArticle, enabled = enabled) {
                    Text(text = "添加独立文章")
                }
                Button(onClick = onAddRssSource, enabled = enabled) {
                    Text(text = "添加 RSS 源")
                }
            }
            Button(onClick = onSyncLibrary, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(text = "同步手表")
            }
            OutlinedButton(
                onClick = onExportBluetoothLog,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "导出蓝牙日志")
            }
        }
    }
}

@Composable
private fun LibraryEntryCard(
    rssSourceCount: Int,
    rssArticleCount: Int,
    favoriteCount: Int,
    watchLaterCount: Int,
    independentCount: Int,
    onOpenRssSources: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "资料库", style = MaterialTheme.typography.titleMedium)
            LibraryEntryRow(
                title = "RSS源",
                subtitle = "$rssSourceCount 个频道，$rssArticleCount 篇文章",
                onClick = onOpenRssSources
            )
            LibraryEntryRow(
                title = "收藏",
                subtitle = "$favoriteCount 篇",
                onClick = onOpenFavorites
            )
            LibraryEntryRow(
                title = "稍后再看",
                subtitle = "$watchLaterCount 篇",
                onClick = onOpenWatchLater
            )
            LibraryEntryRow(
                title = "独立文章",
                subtitle = "$independentCount 篇",
                onClick = onOpenIndependent
            )
        }
    }
}

@Composable
private fun LibraryEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RssSourcesPage(
    sources: List<PhoneRssSourceEntity>,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    onBack: () -> Unit,
    onOpenSource: (PhoneRssSourceEntity) -> Unit
) {
    PageHeader(title = "RSS源", onBack = onBack)
    if (sources.isEmpty()) {
        Text(text = "暂无 RSS 源", style = MaterialTheme.typography.bodyMedium)
        return
    }
    sources.forEach { source ->
        SourceRow(
            source = source,
            articleCount = articlesBySource[source.url].orEmpty().size,
            onClick = { onOpenSource(source) }
        )
    }
}

@Composable
private fun SourceRow(
    source: PhoneRssSourceEntity,
    articleCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = source.title.ifBlank { source.url },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            source.description.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "$articleCount 篇文章",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArticleListPage(
    title: String,
    emptyText: String,
    articles: List<PhoneArticleEntity>,
    onBack: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit
) {
    PageHeader(title = "$title (${articles.size})", onBack = onBack)
    if (articles.isEmpty()) {
        Text(text = emptyText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    articles.forEach { article ->
        ArticleRow(
            article = article,
            onOpenArticle = onOpenArticle,
            onOpenOriginalLink = onOpenOriginalLink,
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater
        )
    }
}

@Composable
private fun PageHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack) {
            Text(text = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ArticleRow(
    article: PhoneArticleEntity,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenArticle(article) }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = article.title.ifBlank { article.url },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val sourceLabel = article.rssSourceTitle?.takeIf { it.isNotBlank() } ?: article.siteName
            if (sourceLabel.isNotBlank()) {
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val summary = article.excerpt.ifBlank { article.contentText }
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onToggleFavorite(article) }) {
                    Text(text = if (article.favoriteSaved) "取消收藏" else "收藏")
                }
                OutlinedButton(onClick = { onToggleWatchLater(article) }) {
                    Text(text = if (article.watchLaterSaved) "移出稍后" else "稍后再看")
                }
                OutlinedButton(
                    onClick = { onOpenOriginalLink(article.url) },
                    enabled = article.url.isNotBlank()
                ) {
                    Text(text = "原网页")
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
