package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.theme.AppCard
import com.lightningstudio.watchrss.phone.ui.theme.AppPrimaryCard
import com.lightningstudio.watchrss.phone.ui.theme.PrimaryRed
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.effects.*
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

class ArticleReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleId = intent.getStringExtra(EXTRA_ARTICLE_ID).orEmpty()
        val repository = (application as PhoneCompanionApplication).container.repository

        setContent {
            WatchRssPhoneTheme {
                val article by remember(articleId) {
                    repository.observeArticle(articleId)
                }.collectAsState(initial = null)
                ArticleReaderScreen(
                    article = article,
                    invalidArticleId = articleId.isBlank(),
                    onBack = { finish() },
                    onOpenImportedArticle = { url ->
                        val targetId = runCatching {
                            WebArticleImporter.stableArticleId(url)
                        }.getOrNull()
                        if (targetId != null) {
                            startActivity(createIntent(this, targetId))
                        }
                    },
                    onOpenOriginal = { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ARTICLE_ID = "article_id"

        fun createIntent(context: Context, articleId: String): Intent {
            return Intent(context, ArticleReaderActivity::class.java).apply {
                putExtra(EXTRA_ARTICLE_ID, articleId)
            }
        }
    }
}

@Composable
private fun ArticleReaderScreen(
    article: PhoneArticleEntity?,
    invalidArticleId: Boolean,
    onBack: () -> Unit,
    onOpenImportedArticle: (String) -> Unit,
    onOpenOriginal: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        if (invalidArticleId) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "文章不存在", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            return@Surface
        }
        val safeArticle = article
        if (safeArticle == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "正在加载文章…", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            return@Surface
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val backgroundColor = MaterialTheme.colorScheme.background
            val surfaceColorArgb = MaterialTheme.colorScheme.surface.toArgb()
            val backdrop = rememberLayerBackdrop {
                drawRect(backgroundColor)
                drawContent()
            }

            // 顶部玻璃条高度
            var topBarHeight by remember { mutableStateOf(0.dp) }

            // 内容区域 - 使用原生 Compose 渲染
            Box(
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                if (!safeArticle.contentHtml.isNullOrBlank()) {
                    NativeArticleView(
                        article = safeArticle,
                        onOpenImportedArticle = onOpenImportedArticle,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = topBarHeight + 20.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
                    )
                } else {
                    PlainArticleView(
                        text = safeArticle.contentText
                            .ifBlank { safeArticle.excerpt }
                            .ifBlank { safeArticle.url },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = topBarHeight + 20.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
                    )
                }
            }

            // 顶部液态玻璃（常可见）
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        topBarHeight = with(density) { coordinates.size.height.toDp() }
                    }
                    .fillMaxWidth()
                    .liquidGlassBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) },
                        isDark = isSystemInDarkTheme()
                    )
                    .padding(bottom = 12.dp)
            ) {
                ReaderTopContent(
                    article = safeArticle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // 底部液态玻璃按钮
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .liquidGlassBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) },
                        isDark = isSystemInDarkTheme()
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text("返回")
                    }
                    if (safeArticle.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(safeArticle.url)) {
                        GlassButton(onClick = { onOpenOriginal(safeArticle.url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Text("原网页")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopContent(
    article: PhoneArticleEntity,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = article.title.ifBlank { article.url },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        article.siteName.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GlassButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

// 统一的液态玻璃效果
private fun Modifier.liquidGlassBackdrop(
    backdrop: LayerBackdrop,
    shape: () -> Shape,
    isDark: Boolean
) = drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        vibrancy()
        blur(8f.dp.toPx())
        lens(16f.dp.toPx(), 32f.dp.toPx())
    },
    onDrawSurface = {
        val surfaceAlpha = if (isDark) 0.12f else 0.5f
        drawRect(Color.White.copy(alpha = surfaceAlpha))
    }
)

/**
 * 原生文章渲染器 - 将 HTML 解析为 Compose 组件
 */
@Composable
private fun NativeArticleView(
    article: PhoneArticleEntity,
    onOpenImportedArticle: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val parsedContent = remember(article.articleId, article.contentHash) {
        parseArticleContent(article.contentHtml ?: "")
    }
    
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(parsedContent) { node ->
            when (node) {
                is ArticleNode.Heading -> {
                    Text(
                        text = node.text,
                        style = when (node.level) {
                            1 -> MaterialTheme.typography.headlineLarge
                            2 -> MaterialTheme.typography.headlineMedium
                            3 -> MaterialTheme.typography.headlineSmall
                            else -> MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.Paragraph -> {
                    Text(
                        text = node.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4f
                    )
                }
                is ArticleNode.Image -> {
                    ArticleImage(
                        url = node.url,
                        alt = node.alt,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.BlockQuote -> {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // 左侧红色强调线
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(24.dp)
                                    .background(PrimaryRed)
                            )
                            Text(
                                text = node.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is ArticleNode.CodeBlock -> {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                is ArticleNode.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = PrimaryRed
                        )
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is ArticleNode.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
                is ArticleNode.Spacer -> {
                    Spacer(modifier = Modifier.height(node.height))
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ArticleImage(
    url: String,
    alt: String,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        AsyncImage(
            model = url,
            contentDescription = alt.takeIf { it.isNotBlank() },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth
        )
    }
}

@Composable
private fun PlainArticleView(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val paragraphs = remember(text) {
        text.split("\n").filter { it.isNotBlank() }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(paragraphs) { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

// ==================== HTML 解析 ====================

private sealed class ArticleNode {
    data class Heading(val text: String, val level: Int) : ArticleNode()
    data class Paragraph(val text: String) : ArticleNode()
    data class Image(val url: String, val alt: String) : ArticleNode()
    data class BlockQuote(val text: String) : ArticleNode()
    data class CodeBlock(val text: String) : ArticleNode()
    data class ListItem(val text: String) : ArticleNode()
    object HorizontalRule : ArticleNode()
    data class Spacer(val height: Dp) : ArticleNode()
}

private fun parseArticleContent(html: String): List<ArticleNode> {
    val result = mutableListOf<ArticleNode>()
    if (html.isBlank()) return result
    
    val doc = Jsoup.parseBodyFragment(html)
    doc.outputSettings().prettyPrint(false)
    
    val body = doc.body()
    if (body == null) return result
    
    val children = body.children()
    if (children.isEmpty()) {
        // 如果没有结构化内容，把整个文本作为一个段落
        val text = body.text().trim()
        if (text.isNotBlank()) {
            result.add(ArticleNode.Paragraph(text))
        }
        return result
    }
    
    for (element in children) {
        when (element.tagName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Heading(text, element.tagName()[1].digitToInt()))
                }
            }
            "p" -> {
                val text = extractTextWithInlineFormatting(element)
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Paragraph(text))
                }
            }
            "blockquote" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.BlockQuote(text))
                }
            }
            "pre" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.CodeBlock(text))
                }
            }
            "code" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.CodeBlock(text))
                }
            }
            "img" -> {
                val src = element.attr("src").trim()
                if (src.isNotBlank()) {
                    result.add(ArticleNode.Image(src, element.attr("alt")))
                }
            }
            "figure" -> {
                val img = element.selectFirst("img")
                if (img != null) {
                    val src = img.attr("src").trim()
                    if (src.isNotBlank()) {
                        result.add(ArticleNode.Image(src, img.attr("alt")))
                    }
                }
                val caption = element.selectFirst("figcaption")
                if (caption != null) {
                    val text = caption.text().trim()
                    if (text.isNotBlank()) {
                        result.add(ArticleNode.Paragraph(text))
                    }
                }
            }
            "ul", "ol" -> {
                element.select("li").forEach { li ->
                    val text = li.text().trim()
                    if (text.isNotBlank()) {
                        result.add(ArticleNode.ListItem(text))
                    }
                }
            }
            "hr", "br" -> {
                result.add(ArticleNode.HorizontalRule)
            }
            "div" -> {
                // 递归处理 div 的内容
                val divChildren = element.children()
                if (divChildren.isEmpty()) {
                    val text = element.text().trim()
                    if (text.isNotBlank()) {
                        result.add(ArticleNode.Paragraph(text))
                    }
                } else {
                    for (child in divChildren) {
                        parseElement(child, result)
                    }
                }
            }
            "article", "section", "main" -> {
                for (child in element.children()) {
                    parseElement(child, result)
                }
            }
            else -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Paragraph(text))
                }
            }
        }
    }
    
    return result
}

private fun parseElement(element: Element, result: MutableList<ArticleNode>) {
    when (element.tagName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.Heading(text, element.tagName()[1].digitToInt()))
            }
        }
        "p" -> {
            val text = extractTextWithInlineFormatting(element)
            if (text.isNotBlank()) {
                result.add(ArticleNode.Paragraph(text))
            }
        }
        "blockquote" -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.BlockQuote(text))
            }
        }
        "pre", "code" -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.CodeBlock(text))
            }
        }
        "img" -> {
            val src = element.attr("src").trim()
            if (src.isNotBlank()) {
                result.add(ArticleNode.Image(src, element.attr("alt")))
            }
        }
        "figure" -> {
            val img = element.selectFirst("img")
            if (img != null) {
                val src = img.attr("src").trim()
                if (src.isNotBlank()) {
                    result.add(ArticleNode.Image(src, img.attr("alt")))
                }
            }
            val caption = element.selectFirst("figcaption")
            if (caption != null) {
                val text = caption.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Paragraph(text))
                }
            }
        }
        "ul", "ol" -> {
            element.select("li").forEach { li ->
                val text = li.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.ListItem(text))
                }
            }
        }
        "hr" -> {
            result.add(ArticleNode.HorizontalRule)
        }
        "div", "article", "section" -> {
            for (child in element.children()) {
                parseElement(child, result)
            }
        }
        else -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.Paragraph(text))
            }
        }
    }
}

/**
 * 提取包含内联格式（如 <strong>, <em>, <a>）的文本
 */
private fun extractTextWithInlineFormatting(element: Element): String {
    val builder = StringBuilder()
    for (node in element.childNodes()) {
        when (node) {
            is TextNode -> builder.append(node.text())
            is Element -> {
                when (node.tagName()) {
                    "br" -> builder.append("\n")
                    "strong", "b" -> builder.append(node.text())
                    "em", "i" -> builder.append(node.text())
                    "a" -> builder.append(node.text())
                    "span" -> builder.append(node.text())
                    "code" -> builder.append(node.text())
                    else -> builder.append(node.text())
                }
            }
        }
    }
    return builder.toString().trim()
}
