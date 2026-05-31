package com.lightningstudio.watchrss.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncProgress
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothWatchDevice
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncDeleteConflict
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val watchLater: List<PhoneArticleEntity> = emptyList(),
    val refreshingRssSourceUrls: Set<String> = emptySet(),
    val conflictPrompt: MainConflictPromptUi? = null,
    val bluetoothDevicePrompt: MainBluetoothDevicePromptUi? = null,
    val sharedImportPrompt: SharedImportPromptUi? = null
)

data class MainSyncProgressUi(
    val phase: String,
    val percent: Int,
    val bytesTransferred: Long = 0L,
    val bytesPerSecond: Long = 0L
)

data class MainConflictPromptUi(
    val conflicts: List<PhoneSyncDeleteConflict>,
    val manual: Boolean = false
)

data class MainBluetoothDevicePromptUi(
    val devices: List<MainBluetoothDeviceUi>
)

data class MainBluetoothDeviceUi(
    val name: String,
    val address: String
)

enum class SharedImportPromptKind {
    LINK,
    FILE
}

data class SharedImportPromptUi(
    val kind: SharedImportPromptKind,
    val url: String = "",
    val fileName: String = "",
    val mimeType: String? = null,
    val uriString: String = ""
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
    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val sessionState = MutableStateFlow(MainUiState())
    private var conflictResolutionDeferred: CompletableDeferred<PhoneSyncConflictResolution>? = null

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
            runCatching { repository.repairImportedContentSourceStates() }
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
        importWebArticle(sessionState.value.urlInput.trim())
    }

    fun showSharedLinkPrompt(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        sessionState.value = sessionState.value.copy(
            urlInput = normalized,
            message = null,
            error = null,
            sharedImportPrompt = SharedImportPromptUi(
                kind = SharedImportPromptKind.LINK,
                url = normalized
            )
        )
    }

    fun showSharedFilePrompt(fileName: String, mimeType: String?, uriString: String) {
        if (uriString.isBlank()) return
        sessionState.value = sessionState.value.copy(
            message = null,
            error = null,
            sharedImportPrompt = SharedImportPromptUi(
                kind = SharedImportPromptKind.FILE,
                fileName = fileName.ifBlank { "未命名文件" },
                mimeType = mimeType,
                uriString = uriString
            )
        )
    }

    fun dismissSharedImportPrompt() {
        sessionState.value = sessionState.value.copy(sharedImportPrompt = null)
    }

    fun importSharedLinkAsIndependent(url: String) {
        val normalized = url.trim()
        sessionState.value = sessionState.value.copy(
            sharedImportPrompt = null,
            urlInput = normalized
        )
        importWebArticle(normalized)
    }

    fun importSharedLinkAsRss(url: String) {
        val normalized = url.trim()
        sessionState.value = sessionState.value.copy(
            sharedImportPrompt = null,
            urlInput = normalized
        )
        addRssSource(normalized)
    }

    fun importLocalContent(fileName: String, mimeType: String?, bytes: ByteArray) {
        viewModelScope.launch {
            runBusy("正在导入文件…") {
                val result = repository.importLocalContent(fileName, mimeType, bytes)
                sessionState.value = sessionState.value.copy(
                    message = when (result.kind) {
                        LocalContentImportKind.TXT -> "已导入 TXT 到导入内容，文章 ${result.articleCount} 篇"
                        LocalContentImportKind.EPUB -> "已导入 EPUB 频道：${result.source.title}，章节 ${result.articleCount} 篇"
                    },
                    error = null
                )
            }
        }
    }

    fun addRssSource() {
        addRssSource(sessionState.value.urlInput.trim())
    }

    private fun addRssSource(url: String) {
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

    fun refreshAllRssSources() {
        val sources = uiState.value.rssSources
            .filterNot { ImportedContentIds.isImportedContentUrl(it.url) }
        if (sources.isEmpty()) {
            sessionState.value = sessionState.value.copy(
                message = null,
                error = null
            )
            _toastEvent.tryEmit("暂无可刷新的 RSS 源")
            return
        }
        val urls = sources.map { it.url }.toSet()
        if (urls.any { it in sessionState.value.refreshingRssSourceUrls }) return
        viewModelScope.launch {
            markRssSourcesRefreshing(urls)
            _toastEvent.tryEmit("正在刷新 RSS 源…")
            var refreshedCount = 0
            val failures = mutableListOf<String>()
            sources.forEach { source ->
                runCatching {
                    repository.refreshRssSource(source.url)
                    refreshedCount += 1
                }.onFailure { throwable ->
                    val name = source.title.ifBlank { source.url }
                    failures += "$name：${throwable.message ?: "刷新失败"}"
                }
            }
            sessionState.update { state ->
                state.copy(
                    refreshingRssSourceUrls = state.refreshingRssSourceUrls - urls
                )
            }
            if (failures.isEmpty()) {
                _toastEvent.tryEmit("已刷新 RSS 源：$refreshedCount 个")
            } else {
                _toastEvent.tryEmit("已刷新 $refreshedCount 个 RSS 源，失败 ${failures.size} 个")
                _toastEvent.tryEmit(failures.first())
            }
        }
    }

    fun refreshRssSource(source: PhoneRssSourceEntity) {
        if (ImportedContentIds.isImportedContentUrl(source.url)) {
            _toastEvent.tryEmit("本地导入频道无需从 RSS 源刷新")
            return
        }
        if (source.url in sessionState.value.refreshingRssSourceUrls) return
        viewModelScope.launch {
            markRssSourcesRefreshing(urls = setOf(source.url))
            _toastEvent.tryEmit("正在刷新频道：${source.title.ifBlank { source.url }}")
            runCatching {
                repository.refreshRssSource(source.url)
            }.onSuccess { result ->
                sessionState.update { state ->
                    state.copy(
                        refreshingRssSourceUrls = state.refreshingRssSourceUrls - source.url
                    )
                }
                _toastEvent.tryEmit("已刷新频道：${result.source.title.ifBlank { result.source.url }}，拉取 ${result.articleCount} 篇")
            }.onFailure { throwable ->
                sessionState.update { state ->
                    state.copy(
                        refreshingRssSourceUrls = state.refreshingRssSourceUrls - source.url
                    )
                }
                _toastEvent.tryEmit(throwable.message ?: "刷新失败")
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

    fun clearImportedContent() {
        viewModelScope.launch {
            runBusy("正在清空导入内容…") {
                val deletedCount = repository.clearImportedContent()
                sessionState.value = sessionState.value.copy(
                    message = "已清空导入内容：$deletedCount 篇",
                    error = null
                )
            }
        }
    }

    fun syncLibraryByBluetooth() {
        viewModelScope.launch {
            sessionState.value = sessionState.value.copy(
                isBusy = true,
                message = "探测手表中",
                error = null,
                syncProgress = MainSyncProgressUi(phase = "探测手表中", percent = 0),
                bluetoothDevicePrompt = null,
                conflictPrompt = null
            )
            val reachableDevices = runCatching {
                bluetoothSyncManager.probeLibrarySyncTargets(::updateBluetoothProbeProgress)
            }.getOrElse { throwable ->
                sessionState.value = sessionState.value.copy(
                    isBusy = false,
                    message = null,
                    error = null,
                    syncProgress = null,
                    bluetoothDevicePrompt = null,
                    conflictPrompt = null
                )
                _toastEvent.tryEmit(throwable.message ?: "操作失败")
                return@launch
            }
            when (reachableDevices.size) {
                0 -> {
                    sessionState.value = sessionState.value.copy(
                        isBusy = false,
                        message = null,
                        error = null,
                        syncProgress = null,
                        bluetoothDevicePrompt = null
                    )
                    _toastEvent.tryEmit("未找到已打开 WatchRSS 的已配对手表，请在手表端打开应用并保持亮屏后重试")
                }
                1 -> {
                    delay(400L)
                    runLibrarySync(reachableDevices.single().address)
                }
                else -> {
                    sessionState.value = sessionState.value.copy(
                        isBusy = false,
                        message = "发现 ${reachableDevices.size} 块可同步手表",
                        error = null,
                        syncProgress = null,
                        bluetoothDevicePrompt = MainBluetoothDevicePromptUi(
                            devices = reachableDevices.map { it.toUi() }
                        )
                    )
                }
            }
        }
    }

    fun chooseBluetoothDeviceForSync(device: MainBluetoothDeviceUi) {
        viewModelScope.launch {
            sessionState.value = sessionState.value.copy(bluetoothDevicePrompt = null)
            runLibrarySync(device.address)
        }
    }

    fun dismissBluetoothDevicePrompt() {
        sessionState.value = sessionState.value.copy(
            bluetoothDevicePrompt = null,
            message = null,
            syncProgress = null
        )
    }

    private suspend fun runLibrarySync(deviceAddress: String?) {
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = "建立连接中",
            error = null,
            syncProgress = MainSyncProgressUi(phase = "建立连接中", percent = 0),
            bluetoothDevicePrompt = null
        )
        runCatching {
            val result = bluetoothSyncManager.syncLibrary(
                deviceAddress = deviceAddress,
                onProgress = ::updateLibrarySyncProgress,
                resolveDeleteConflicts = ::resolveDeleteConflicts
            )
            val stats = result.libraryStats
            sessionState.value = sessionState.value.copy(
                error = null,
                syncProgress = null
            )
            _toastEvent.tryEmit(
                if (stats != null) {
                    "已与 ${result.deviceName.ifBlank { "手表" }} 同步：文章发送 ${stats.sent}，收到 ${stats.received}，合并 ${stats.merged}；RSS源发送 ${stats.sourcesSent}，收到 ${stats.sourcesReceived}，合并 ${stats.sourcesMerged}"
                } else {
                    "已与 ${result.deviceName.ifBlank { "手表" }} 同步"
                }
            )
        }.onFailure { throwable ->
            sessionState.value = sessionState.value.copy(
                error = null,
                syncProgress = null,
                conflictPrompt = null
            )
            _toastEvent.tryEmit(throwable.message ?: "操作失败")
        }
        conflictResolutionDeferred?.complete(PhoneSyncConflictResolution.KEEP_LATEST)
        conflictResolutionDeferred = null
        sessionState.value = sessionState.value.copy(isBusy = false, conflictPrompt = null)
    }

    fun chooseConflictResolution(resolution: PhoneSyncConflictResolution) {
        conflictResolutionDeferred?.complete(resolution)
    }

    fun showManualConflictOptions() {
        val prompt = sessionState.value.conflictPrompt ?: return
        sessionState.value = sessionState.value.copy(conflictPrompt = prompt.copy(manual = true))
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

    private fun importWebArticle(url: String) {
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
            val updated = repository.toggleSaved(article, type)
            val saved = when (type) {
                PhoneSavedItemType.FAVORITE -> updated.favoriteSaved
                PhoneSavedItemType.WATCH_LATER -> updated.watchLaterSaved
            }
            val msg = if (saved) "已加入${type.displayName}" else "已从${type.displayName}移除"
            _toastEvent.tryEmit(msg)
        }
    }

    private fun markRssSourcesRefreshing(urls: Set<String>) {
        sessionState.update { state ->
            state.copy(
                error = null,
                syncProgress = null,
                refreshingRssSourceUrls = state.refreshingRssSourceUrls + urls
            )
        }
    }

    private suspend fun resolveDeleteConflicts(
        conflicts: List<PhoneSyncDeleteConflict>
    ): Map<String, PhoneSyncConflictResolution> {
        conflictResolutionDeferred?.complete(PhoneSyncConflictResolution.KEEP_LATEST)
        val deferred = CompletableDeferred<PhoneSyncConflictResolution>()
        conflictResolutionDeferred = deferred
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = "双端内容有冲突，请选择处理方式",
            error = null,
            syncProgress = null,
            conflictPrompt = MainConflictPromptUi(conflicts = conflicts)
        )
        return try {
            val resolution = deferred.await()
            conflicts.associate { conflict -> conflict.articleId to resolution }
        } finally {
            if (conflictResolutionDeferred === deferred) {
                conflictResolutionDeferred = null
            }
            sessionState.value = sessionState.value.copy(
                message = "信息传输中",
                conflictPrompt = null,
                syncProgress = MainSyncProgressUi(phase = "信息传输中", percent = 30)
            )
        }
    }

    private suspend fun runBusy(busyMessage: String, block: suspend () -> Unit) {
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = busyMessage,
            error = null,
            syncProgress = null,
            conflictPrompt = null,
            bluetoothDevicePrompt = null
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
                percent = percent,
                bytesTransferred = progress.bytesTransferred,
                bytesPerSecond = progress.bytesPerSecond
            )
        )
    }

    private fun updateBluetoothProbeProgress(completed: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val percent = ((completed.coerceIn(0, safeTotal).toFloat() / safeTotal.toFloat()) * 100).toInt()
        sessionState.value = sessionState.value.copy(
            message = "探测手表中（$completed/$total）",
            error = null,
            syncProgress = MainSyncProgressUi(
                phase = "探测手表中",
                percent = percent.coerceIn(0, 100)
            )
        )
    }

    private fun PhoneBluetoothWatchDevice.toUi(): MainBluetoothDeviceUi =
        MainBluetoothDeviceUi(
            name = name.ifBlank { "未知手表" },
            address = address
        )

    override fun onCleared() {
        super.onCleared()
    }
}
