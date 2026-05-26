package com.lightningstudio.watchrss.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncProgress
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val urlInput: String = "",
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val syncProgress: MainSyncProgressUi? = null,
    val rssSources: List<PhoneRssSourceEntity> = emptyList(),
    val rssArticles: List<PhoneArticleEntity> = emptyList(),
    val independentArticles: List<PhoneArticleEntity> = emptyList(),
    val importedContentArticles: List<PhoneArticleEntity> = emptyList(),
    val favorites: List<PhoneArticleEntity> = emptyList(),
    val watchLater: List<PhoneArticleEntity> = emptyList()
)

data class MainSyncProgressUi(
    val phase: String,
    val percent: Int
)

private data class LibraryLists(
    val rssSources: List<PhoneRssSourceEntity>,
    val rssArticles: List<PhoneArticleEntity>,
    val independentArticles: List<PhoneArticleEntity>,
    val importedContentArticles: List<PhoneArticleEntity>,
    val favorites: List<PhoneArticleEntity>,
    val watchLater: List<PhoneArticleEntity>
)

private data class LibraryContentLists(
    val rssSources: List<PhoneRssSourceEntity>,
    val rssArticles: List<PhoneArticleEntity>,
    val independentArticles: List<PhoneArticleEntity>,
    val importedContentArticles: List<PhoneArticleEntity>
)

class MainViewModel(
    private val repository: PhoneCompanionRepository,
    private val bluetoothSyncManager: PhoneBluetoothSyncManager
) : ViewModel() {
    private val sessionState = MutableStateFlow(MainUiState())

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            combine(
                repository.observeRssSources(),
                repository.observeRssArticles(),
                repository.observeIndependentArticles(),
                repository.observeImportedContentArticles()
            ) { rssSources, rssArticles, independentArticles, importedContentArticles ->
                LibraryContentLists(
                    rssSources = rssSources,
                    rssArticles = rssArticles,
                    independentArticles = independentArticles,
                    importedContentArticles = importedContentArticles
                )
            },
            repository.observeSavedArticles(PhoneSavedItemType.FAVORITE),
            repository.observeSavedArticles(PhoneSavedItemType.WATCH_LATER)
        ) { content, favorites, watchLater ->
            LibraryLists(
                rssSources = content.rssSources,
                rssArticles = content.rssArticles,
                independentArticles = content.independentArticles,
                importedContentArticles = content.importedContentArticles,
                favorites = favorites,
                watchLater = watchLater
            )
        },
        sessionState
    ) { lists, state ->
        state.copy(
            rssSources = lists.rssSources,
            rssArticles = lists.rssArticles,
            independentArticles = lists.independentArticles,
            importedContentArticles = lists.importedContentArticles,
            favorites = lists.favorites,
            watchLater = lists.watchLater
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState()
    )

    init {
        viewModelScope.launch {
            runCatching { repository.repairImportedContentTitles() }
        }
    }

    fun updateUrlInput(value: String) {
        sessionState.value = sessionState.value.copy(urlInput = value)
    }

    fun clearMessage() {
        sessionState.value = sessionState.value.copy(message = null, error = null)
    }

    fun showMessage(message: String) {
        sessionState.value = sessionState.value.copy(message = message, error = null)
    }

    fun showError(error: String) {
        sessionState.value = sessionState.value.copy(error = error)
    }

    fun importIndependentArticle() {
        importWebArticle()
    }

    fun importLocalContent(fileName: String, mimeType: String?, bytes: ByteArray) {
        viewModelScope.launch {
            runBusy("正在导入小说…") {
                val result = repository.importLocalContent(fileName, mimeType, bytes)
                sessionState.value = sessionState.value.copy(
                    message = when (result.kind) {
                        LocalContentImportKind.TXT -> "已导入 TXT 到导入内容，文章 ${result.articleCount} 篇"
                        LocalContentImportKind.EPUB -> "已导入 EPUB：${result.source.title}，章节 ${result.articleCount} 篇"
                    },
                    error = null
                )
            }
        }
    }

    fun addRssSource() {
        val url = sessionState.value.urlInput.trim()
        if (url.isBlank()) {
            sessionState.value = sessionState.value.copy(error = "请输入 RSS 源地址")
            return
        }
        viewModelScope.launch {
            runBusy("正在添加 RSS 源…") {
                val result = repository.addRssSource(url)
                sessionState.value = sessionState.value.copy(
                    message = "已添加 RSS 源：${result.source.title}，导入 ${result.articleCount} 篇",
                    error = null,
                    urlInput = ""
                )
            }
        }
    }

    fun toggleFavorite(article: PhoneArticleEntity) {
        toggleSaved(article, PhoneSavedItemType.FAVORITE)
    }

    fun toggleWatchLater(article: PhoneArticleEntity) {
        toggleSaved(article, PhoneSavedItemType.WATCH_LATER)
    }

    fun moveRssSourceToTop(source: PhoneRssSourceEntity) {
        viewModelScope.launch {
            runBusy("正在调整频道顺序…") {
                repository.moveRssSourceToTop(source.url)
                sessionState.value = sessionState.value.copy(
                    message = "已移到顶部：${source.title.ifBlank { source.url }}",
                    error = null
                )
            }
        }
    }

    fun toggleRssSourcePinned(source: PhoneRssSourceEntity) {
        viewModelScope.launch {
            runBusy("正在更新频道置顶…") {
                repository.setRssSourcePinned(source.url, !source.isPinned)
                sessionState.value = sessionState.value.copy(
                    message = if (source.isPinned) {
                        "已取消置顶：${source.title.ifBlank { source.url }}"
                    } else {
                        "已置顶：${source.title.ifBlank { source.url }}"
                    },
                    error = null
                )
            }
        }
    }

    fun deleteRssSource(source: PhoneRssSourceEntity) {
        viewModelScope.launch {
            runBusy("正在删除频道…") {
                repository.deleteRssSource(source.url)
                sessionState.value = sessionState.value.copy(
                    message = "已删除频道：${source.title.ifBlank { source.url }}",
                    error = null
                )
            }
        }
    }

    fun deleteArticle(article: PhoneArticleEntity) {
        viewModelScope.launch {
            runBusy("正在删除内容…") {
                repository.deleteArticle(article.articleId)
                sessionState.value = sessionState.value.copy(
                    message = "已删除：${article.title.ifBlank { article.url }}",
                    error = null
                )
            }
        }
    }

    fun syncLibraryByBluetooth() {
        viewModelScope.launch {
            sessionState.value = sessionState.value.copy(
                isBusy = true,
                message = "建立连接中",
                error = null,
                syncProgress = MainSyncProgressUi(phase = "建立连接中", percent = 0)
            )
            runCatching {
                val result = bluetoothSyncManager.syncLibrary(::updateLibrarySyncProgress)
                val stats = result.libraryStats
                sessionState.value = sessionState.value.copy(
                    message = if (stats != null) {
                        "已与 ${result.deviceName.ifBlank { "手表" }} 同步：文章发送 ${stats.sent}，收到 ${stats.received}，合并 ${stats.merged}；RSS源发送 ${stats.sourcesSent}，收到 ${stats.sourcesReceived}，合并 ${stats.sourcesMerged}"
                    } else {
                        "已与 ${result.deviceName.ifBlank { "手表" }} 同步"
                    },
                    error = null,
                    syncProgress = null
                )
            }.onFailure { throwable ->
                sessionState.value = sessionState.value.copy(
                    error = throwable.message ?: "操作失败",
                    syncProgress = null
                )
            }
            sessionState.value = sessionState.value.copy(isBusy = false)
        }
    }

    fun sendRemoteInputByBluetooth() {
        val url = sessionState.value.urlInput.trim()
        if (url.isBlank()) {
            sessionState.value = sessionState.value.copy(error = "请输入 RSS 地址")
            return
        }
        viewModelScope.launch {
            runBusy("正在通过蓝牙发送 RSS 地址…") {
                val result = bluetoothSyncManager.sendRemoteInput(url)
                sessionState.value = sessionState.value.copy(
                    message = "已通过蓝牙发送到 ${result.deviceName.ifBlank { "手表" }}",
                    error = null,
                    urlInput = ""
                )
            }
        }
    }

    fun syncFavoritesByBluetooth() {
        syncSavedItemsByBluetooth(PhoneSavedItemType.FAVORITE)
    }

    fun syncWatchLaterByBluetooth() {
        syncSavedItemsByBluetooth(PhoneSavedItemType.WATCH_LATER)
    }

    private fun syncSavedItemsByBluetooth(type: PhoneSavedItemType) {
        viewModelScope.launch {
            runBusy("正在通过蓝牙同步${type.displayName}…") {
                val result = bluetoothSyncManager.syncSavedItems(type)
                sessionState.value = sessionState.value.copy(
                    message = "已从 ${result.deviceName.ifBlank { "手表" }} 同步 ${result.importedCount ?: 0} 条${type.displayName}",
                    error = null
                )
            }
        }
    }

    private fun importWebArticle() {
        val url = sessionState.value.urlInput.trim()
        if (url.isBlank()) {
            sessionState.value = sessionState.value.copy(error = "请输入网页地址")
            return
        }
        viewModelScope.launch {
            runBusy("正在导入网页…") {
                val article = repository.importWebArticle(url)
                sessionState.value = sessionState.value.copy(
                    message = "已导入到独立文章：${article.title}",
                    error = null,
                    urlInput = ""
                )
            }
        }
    }

    private fun toggleSaved(article: PhoneArticleEntity, type: PhoneSavedItemType) {
        viewModelScope.launch {
            runBusy("正在更新${type.displayName}…") {
                val updated = repository.toggleSaved(article, type)
                val saved = when (type) {
                    PhoneSavedItemType.FAVORITE -> updated.favoriteSaved
                    PhoneSavedItemType.WATCH_LATER -> updated.watchLaterSaved
                }
                sessionState.value = sessionState.value.copy(
                    message = if (saved) "已加入${type.displayName}" else "已从${type.displayName}移除",
                    error = null
                )
            }
        }
    }

    private suspend fun runBusy(busyMessage: String, block: suspend () -> Unit) {
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = busyMessage,
            error = null,
            syncProgress = null
        )
        runCatching { block() }
            .onFailure { throwable ->
                sessionState.value = sessionState.value.copy(error = throwable.message ?: "操作失败")
        }
        sessionState.value = sessionState.value.copy(isBusy = false, syncProgress = null)
    }

    private fun updateLibrarySyncProgress(progress: PhoneBluetoothSyncProgress) {
        val percent = progress.percent.coerceIn(0, 100)
        sessionState.value = sessionState.value.copy(
            message = progress.stage.displayName,
            error = null,
            syncProgress = MainSyncProgressUi(
                phase = progress.stage.displayName,
                percent = percent
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
    }
}
