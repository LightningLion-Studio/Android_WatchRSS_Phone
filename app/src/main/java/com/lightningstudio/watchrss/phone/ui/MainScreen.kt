package com.lightningstudio.watchrss.phone.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState

@Composable
fun MainScreen(
    uiState: MainUiState,
    onUrlChange: (String) -> Unit,
    onImportFavorite: () -> Unit,
    onImportWatchLater: () -> Unit,
    onSyncLibrary: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDismissMessage: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
            onImportFavorite = onImportFavorite,
            onImportWatchLater = onImportWatchLater,
            onSyncLibrary = onSyncLibrary
        )

        ArticleSection(
            title = "收藏",
            emptyText = "暂无收藏",
            articles = uiState.favorites,
            onOpenArticle = onOpenArticle,
            onOpenOriginalLink = { uriHandler.openUri(it) },
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater
        )

        ArticleSection(
            title = "稍后再看",
            emptyText = "暂无稍后再看",
            articles = uiState.watchLater,
            onOpenArticle = onOpenArticle,
            onOpenOriginalLink = { uriHandler.openUri(it) },
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater
        )

        ArticleSection(
            title = "最近导入",
            emptyText = "暂无导入文章",
            articles = uiState.recentArticles,
            onOpenArticle = onOpenArticle,
            onOpenOriginalLink = { uriHandler.openUri(it) },
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater
        )
    }
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
                text = "导入网页后可手动与已配对手表双向同步收藏和稍后再看。",
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
    onImportFavorite: () -> Unit,
    onImportWatchLater: () -> Unit,
    onSyncLibrary: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "网页导入", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text(text = "网页地址") },
                placeholder = { Text(text = "https://example.com/article") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImportFavorite, enabled = enabled) {
                    Text(text = "导入收藏")
                }
                Button(onClick = onImportWatchLater, enabled = enabled) {
                    Text(text = "导入稍后")
                }
            }
            Button(onClick = onSyncLibrary, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(text = "同步手表")
            }
        }
    }
}

@Composable
private fun ArticleSection(
    title: String,
    emptyText: String,
    articles: List<PhoneArticleEntity>,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "$title (${articles.size})",
                style = MaterialTheme.typography.titleMedium
            )
            if (articles.isEmpty()) {
                Text(text = emptyText, style = MaterialTheme.typography.bodyMedium)
            } else {
                articles.take(20).forEach { article ->
                    ArticleRow(
                        article = article,
                        onOpenArticle = onOpenArticle,
                        onOpenOriginalLink = onOpenOriginalLink,
                        onToggleFavorite = onToggleFavorite,
                        onToggleWatchLater = onToggleWatchLater
                    )
                }
            }
        }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onOpenArticle(article)
            }
    ) {
        Text(
            text = article.title.ifBlank { article.url },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (article.siteName.isNotBlank()) {
            Text(
                text = article.siteName,
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
        Spacer(modifier = Modifier.height(4.dp))
    }
}
