package com.lightningstudio.watchrss.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncProgress
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncStage
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothWatchDevice
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncSession
import com.lightningstudio.watchrss.phone.connection.ip.PhoneIpSyncSessionRegistry
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncDeleteConflict
import com.lightningstudio.watchrss.phone.data.backup.BackupImportMode
import com.lightningstudio.watchrss.phone.data.backup.BackupPreview
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.importer.LocalContentImportKind
import com.lightningstudio.watchrss.phone.data.importer.OpmlImporter
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneLocalContentImportInspection
import com.lightningstudio.watchrss.phone.data.repo.PhoneTxtUpdateCandidate
import com.lightningstudio.watchrss.phone.data.repo.TxtUpdateRelation
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageDailyPojo
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageStatisticsPojo
import com.lightningstudio.watchrss.phone.tips.TipEvents
import com.lightningstudio.watchrss.phone.tips.TipManager
import com.lightningstudio.watchrss.phone.ui.SupportContactAlertUi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val urlInput: String = "",
    val isBusy: Boolean = false,
    val message: String? = null,
    val syncStatusMessage: String? = null,
    val syncStatusError: String? = null,
    val syncTransportLabel: String = "两设备连接到同一个wifi以获得更快的同步速度",
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
    val sharedImportPrompt: SharedImportPromptUi? = null,
    val backupImportPrompt: BackupImportPromptUi? = null,
    val txtChapterPrompt: TxtChapterPromptUi? = null,
    val txtUpdatePrompt: TxtUpdatePromptUi? = null,
    val llmTokenUsageStats: PhoneLlmTokenUsageStatisticsPojo? = null,
    val llmTokenUsageDaily: List<PhoneLlmTokenUsageDailyPojo> = emptyList(),
    val supportAlert: SupportContactAlertUi? = null
)

data class MainSyncProgressUi(
    val phase: String,
    val percent: Int,
    val indeterminate: Boolean = false,
    val bytesTransferred: Long = 0L,
    val bytesPerSecond: Long = 0L
)

internal fun syncFailureMessage(throwable: Throwable, phase: String?): String {
    val detail = generateSequence(throwable) { it.cause }
        .mapNotNull { cause -> cause.message?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
    val errorName = throwable::class.java.simpleName.ifBlank { "同步异常" }
    val reason = if (detail == null) errorName else "$errorName：$detail"
    val stage = phase?.trim()?.removeSuffix("中")?.takeIf(String::isNotEmpty)
    return if (stage == null) "同步失败：$reason" else "${stage}失败：$reason"
}

data class MainConflictPromptUi(
    val conflicts: List<PhoneSyncDeleteConflict>,
    val manual: Boolean = false
)

data class MainBluetoothDevicePromptUi(
    val devices: List<MainBluetoothDeviceUi>,
    val purpose: MainBluetoothDevicePromptPurpose = MainBluetoothDevicePromptPurpose.LIBRARY
)

data class MainBluetoothDeviceUi(
    val name: String,
    val address: String,
    val remoteDeviceId: String = "",
    val transportLabel: String = "RFCOMM",
    val bluetoothAddress: String = address,
    val supportsPersistentSession: Boolean = false
)

enum class MainBluetoothDevicePromptPurpose {
    LIBRARY,
    ACCOUNT
}

enum class SharedImportPromptKind {
    LINK,
    FILE,
    MARKDOWN_FILE
}

data class SharedImportPromptUi(
    val kind: SharedImportPromptKind,
    val url: String = "",
    val fileName: String = "",
    val mimeType: String? = null,
    val uriString: String = ""
)

data class BackupImportPromptUi(
    val fileName: String,
    val uriString: String,
    val preview: BackupPreview,
    val confirmingReplace: Boolean = false
)

data class TxtUpdatePromptUi(
    val fileName: String,
    val candidates: List<PhoneTxtUpdateCandidate>
)

data class TxtChapterPromptUi(
    val fileName: String,
    val bookTitle: String,
    val chapterCount: Int
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
    private val bluetoothSyncManager: PhoneBluetoothSyncManager,
    private val llmTokenUsageRepository: PhoneLlmTokenUsageRepository,
    private val usageTelemetry: PhoneUsageTelemetry,
    private val backupService: WatchRssBackupService,
    private val tipManager: TipManager
) : ViewModel() {
    private var pendingLocalContentInspection: PhoneLocalContentImportInspection? = null
    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val sessionState = MutableStateFlow(MainUiState())
    private var conflictResolutionDeferred: CompletableDeferred<PhoneSyncConflictResolution>? = null
    private var smoothedSyncProgressJob: Job? = null
    private var smoothedSyncProgressTarget: MainSyncProgressUi? = null
    private var verificationTransitionJob: Job? = null
    private var verificationProgressTarget: MainSyncProgressUi? = null

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
        llmTokenUsageRepository.observeStatistics(),
        llmTokenUsageRepository.observeDaily(),
        sessionState
    ) { lists, llmStats, llmDaily, state ->
        state.copy(
            rssSources = lists.rssSources,
            rssArticles = lists.rssArticles,
            independentArticles = lists.independentArticles,
            importedContentArticles = lists.importedContentArticles,
            favorites = lists.favorites,
            watchLater = lists.watchLater,
            llmTokenUsageStats = llmStats,
            llmTokenUsageDaily = llmDaily
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
        sessionState.value = sessionState.value.copy(
            message = null,
            syncStatusMessage = null,
            syncStatusError = null,
            error = null
        )
    }

    fun dismissSupportAlert() {
        sessionState.value = sessionState.value.copy(supportAlert = null)
    }

    private fun showSupportAlert(title: String, message: String, errorDetails: String? = null) {
        sessionState.value = sessionState.value.copy(
            supportAlert = SupportContactAlertUi(
                title = title,
                message = message,
                errorDetails = errorDetails
            )
        )
    }

    fun showMessage(message: String) {
        sessionState.value = sessionState.value.copy(
            message = message,
            syncStatusMessage = null,
            syncStatusError = null,
            error = null
        )
    }

    fun showSyncStatusMessage(message: String) {
        sessionState.value = sessionState.value.copy(
            message = message,
            syncStatusMessage = message,
            syncStatusError = null,
            error = null
        )
    }

    fun showSyncStatusError(error: String) {
        sessionState.value = sessionState.value.copy(
            syncStatusMessage = null,
            syncStatusError = error,
            error = error
        )
    }

    fun showError(error: String) {
        sessionState.value = sessionState.value.copy(
            syncStatusMessage = null,
            syncStatusError = null,
            error = error
        )
    }

    /**
     * Shows a content-level error that must be visible to the user regardless of
     * which page is currently displayed.
     *
     * Uses Toast for immediate visibility (page-independent) and also persists the
     * error in session state so the Imports page can display it persistently.
     *
     * This is critical for cold-start scenarios where the app is launched from an
     * external intent (e.g. another app shares an unsupported file). The user
     * lands on the Dashboard page — page-specific error rendering in ImportActionsCard
     * would be invisible because HorizontalPager only composes the current page.
     */
    fun showContentError(error: String) {
        _toastEvent.tryEmit(error)
        sessionState.value = sessionState.value.copy(
            syncStatusMessage = null,
            syncStatusError = null,
            error = error
        )
    }

    fun showContentMessage(message: String) {
        _toastEvent.tryEmit(message)
        sessionState.value = sessionState.value.copy(
            message = message,
            syncStatusMessage = null,
            syncStatusError = null,
            error = null
        )
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
            syncStatusMessage = null,
            syncStatusError = null,
            error = null,
            sharedImportPrompt = SharedImportPromptUi(
                kind = SharedImportPromptKind.LINK,
                url = normalized
            )
        )
    }

    fun showSharedFilePrompt(
        fileName: String,
        mimeType: String?,
        uriString: String,
        markdownNote: Boolean = false
    ) {
        if (uriString.isBlank()) return
        sessionState.value = sessionState.value.copy(
            message = null,
            syncStatusMessage = null,
            syncStatusError = null,
            error = null,
            sharedImportPrompt = SharedImportPromptUi(
                kind = if (markdownNote) {
                    SharedImportPromptKind.MARKDOWN_FILE
                } else {
                    SharedImportPromptKind.FILE
                },
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
            runBusy("正在检查文件…") {
                val inspection = repository.inspectLocalContentImport(fileName, mimeType, bytes)
                inspection.imported.txtChapterPlan?.let { chapterPlan ->
                    pendingLocalContentInspection = inspection
                    sessionState.value = sessionState.value.copy(
                        txtChapterPrompt = TxtChapterPromptUi(
                            fileName = fileName,
                            bookTitle = chapterPlan.bookTitle,
                            chapterCount = chapterPlan.headings.size
                        ),
                        txtUpdatePrompt = null,
                        message = null,
                        error = null
                    )
                    return@runBusy
                }
                continueLocalContentImport(inspection)
            }
        }
    }

    fun importOpml(bytes: ByteArray) {
        viewModelScope.launch {
            runBusy("正在导入 OPML 订阅…") {
                val subscriptions = OpmlImporter().parse(bytes)
                var importedCount = 0
                var articleCount = 0
                subscriptions.forEach { subscription ->
                    runCatching { repository.addRssSource(subscription.feedUrl) }
                        .onSuccess { result ->
                            importedCount += 1
                            articleCount += result.articleCount
                            usageTelemetry.recordRssSourceAdded(
                                url = result.source.url,
                                title = result.source.title,
                                articleCount = result.articleCount
                            )
                        }
                }
                if (importedCount > 0) {
                    tipManager.recordEvent(TipEvents.RSS_SOURCE_ADDED)
                }
                val failedCount = subscriptions.size - importedCount
                sessionState.value = sessionState.value.copy(
                    message = buildString {
                        append("OPML 导入完成：成功 $importedCount 个订阅源，导入 $articleCount 篇文章")
                        if (failedCount > 0) append("，失败 $failedCount 个")
                    },
                    error = if (importedCount == 0) "OPML 中的订阅源均导入失败" else null
                )
            }
        }
    }

    fun chooseTxtChapterImport(splitIntoChapters: Boolean) {
        val inspection = pendingLocalContentInspection ?: return
        viewModelScope.launch {
            runBusy(if (splitIntoChapters) "正在按章节导入 TXT…" else "正在导入 TXT…") {
                val selectedInspection = if (splitIntoChapters) {
                    repository.useTxtChapterImport(inspection)
                } else {
                    inspection
                }
                pendingLocalContentInspection = null
                sessionState.value = sessionState.value.copy(txtChapterPrompt = null)
                continueLocalContentImport(selectedInspection)
            }
        }
    }

    fun dismissTxtChapterPrompt() {
        pendingLocalContentInspection = null
        sessionState.value = sessionState.value.copy(txtChapterPrompt = null)
    }

    private suspend fun continueLocalContentImport(inspection: PhoneLocalContentImportInspection) {
        val identical = inspection.candidates.firstOrNull {
            it.relation == TxtUpdateRelation.IDENTICAL
        }
        if (identical != null) {
            pendingLocalContentInspection = null
            sessionState.value = sessionState.value.copy(
                txtUpdatePrompt = null,
                message = "该 TXT 已导入：${identical.existingTitle}",
                error = null
            )
            return
        }
        if (inspection.imported.kind == LocalContentImportKind.TXT &&
            inspection.candidates.isNotEmpty()
        ) {
            pendingLocalContentInspection = inspection
            sessionState.value = sessionState.value.copy(
                txtUpdatePrompt = TxtUpdatePromptUi(
                    fileName = inspection.fileName,
                    candidates = inspection.candidates
                ),
                message = null,
                error = null
            )
            return
        }
        val result = repository.confirmLocalContentImport(
            inspection = inspection,
            replaceArticleId = null
        )
        usageTelemetry.recordLocalContentImported(
            kind = result.kind.name,
            title = result.source.title,
            articleCount = result.articleCount
        )
        tipManager.recordEvent(TipEvents.LOCAL_CONTENT_IMPORTED)
        sessionState.value = sessionState.value.copy(
            message = when (result.kind) {
                LocalContentImportKind.TXT -> "已导入 TXT 到导入内容，文章 ${result.articleCount} 篇"
                LocalContentImportKind.TXT_CHAPTERS -> "已导入 TXT 频道：${result.source.title}，章节 ${result.articleCount} 篇"
                LocalContentImportKind.EPUB -> "已导入 EPUB 频道：${result.source.title}，章节 ${result.articleCount} 篇"
            },
            error = null
        )
    }

    fun confirmTxtUpdate(articleId: String) {
        val inspection = pendingLocalContentInspection ?: return
        viewModelScope.launch {
            runBusy("正在覆盖 TXT…") {
                val result = repository.confirmLocalContentImport(
                    inspection = inspection,
                    replaceArticleId = articleId
                )
                usageTelemetry.recordLocalContentImported(
                    kind = result.kind.name,
                    title = result.source.title,
                    articleCount = result.articleCount
                )
                pendingLocalContentInspection = null
                sessionState.value = sessionState.value.copy(
                    txtUpdatePrompt = null,
                    message = "已更新 TXT：${result.source.title}，阅读进度已继承",
                    error = null
                )
            }
        }
    }

    fun importPendingTxtAsNew() {
        val inspection = pendingLocalContentInspection ?: return
        viewModelScope.launch {
            runBusy("正在导入新 TXT…") {
                val result = repository.confirmLocalContentImport(
                    inspection = inspection,
                    replaceArticleId = null
                )
                usageTelemetry.recordLocalContentImported(
                    kind = result.kind.name,
                    title = result.source.title,
                    articleCount = result.articleCount
                )
                pendingLocalContentInspection = null
                sessionState.value = sessionState.value.copy(
                    txtUpdatePrompt = null,
                    message = "已作为新 TXT 导入，文章 ${result.articleCount} 篇",
                    error = null
                )
            }
        }
    }

    fun dismissTxtUpdatePrompt() {
        pendingLocalContentInspection = null
        sessionState.value = sessionState.value.copy(txtUpdatePrompt = null)
    }

    fun exportBackup(uriString: String) {
        if (uriString.isBlank() || sessionState.value.isBusy) return
        viewModelScope.launch {
            runBusy("正在导出资料库…") {
                val result = backupService.exportTo(uriString)
                usageTelemetry.recordBackupExported(
                    articleCount = result.articleCount,
                    sourceCount = result.sourceCount
                )
                tipManager.recordEvent(TipEvents.BACKUP_EXPORTED)
                sessionState.value = sessionState.value.copy(
                    message = "已导出 WRSS：${result.articleCount} 篇文章，${result.sourceCount} 个 RSS 源",
                    error = null
                )
            }
        }
    }

    fun inspectBackup(fileName: String, uriString: String) {
        if (uriString.isBlank() || sessionState.value.isBusy) return
        viewModelScope.launch {
            runBusy("正在检查 WRSS 备份…") {
                val preview = backupService.inspect(uriString)
                sessionState.value = sessionState.value.copy(
                    message = null,
                    error = null,
                    backupImportPrompt = BackupImportPromptUi(
                        fileName = fileName.ifBlank { "未命名.wrss" },
                        uriString = uriString,
                        preview = preview
                    )
                )
            }
        }
    }

    fun requestBackupReplaceConfirmation() {
        val prompt = sessionState.value.backupImportPrompt ?: return
        sessionState.value = sessionState.value.copy(
            backupImportPrompt = prompt.copy(confirmingReplace = true)
        )
    }

    fun dismissBackupImportPrompt() {
        sessionState.value = sessionState.value.copy(backupImportPrompt = null)
    }

    fun importBackup(mode: BackupImportMode) {
        if (sessionState.value.isBusy) return
        val prompt = sessionState.value.backupImportPrompt ?: return
        sessionState.value = sessionState.value.copy(backupImportPrompt = null)
        viewModelScope.launch {
            runBusy(if (mode == BackupImportMode.REPLACE) "正在覆盖资料库…" else "正在合并资料库…") {
                val result = backupService.importFrom(prompt.uriString, mode)
                val action = if (mode == BackupImportMode.REPLACE) "覆盖" else "合并"
                usageTelemetry.recordBackupImported(
                    mode = mode.name,
                    articleCount = result.articleCount,
                    sourceCount = result.sourceCount
                )
                tipManager.recordEvent(TipEvents.BACKUP_IMPORTED)
                sessionState.value = sessionState.value.copy(
                    message = "已${action} WRSS：${result.articleCount} 篇文章，${result.sourceCount} 个 RSS 源",
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
                usageTelemetry.recordRssSourceAdded(
                    url = result.source.url,
                    title = result.source.title,
                    articleCount = result.articleCount
                )
                tipManager.recordEvent(TipEvents.RSS_SOURCE_ADDED)
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
                sessionState.value = sessionState.value.copy(
                    syncStatusError = null,
                    error = null
                )
            } else {
                val summary = "已刷新 $refreshedCount 个 RSS 源，失败 ${failures.size} 个"
                showSyncStatusError(summary)
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

    fun reorderContentChannels(sourceUrlsInDisplayOrder: List<String>, independentIndex: Int?) {
        if (sourceUrlsInDisplayOrder.size < 2 && independentIndex == null) return
        viewModelScope.launch {
            runBusy("正在调整频道顺序…") {
                repository.reorderContentChannels(sourceUrlsInDisplayOrder, independentIndex)
                sessionState.value = sessionState.value.copy(
                    message = "已更新频道顺序",
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

    fun setRssSourceOriginalContentEnabled(source: PhoneRssSourceEntity, enabled: Boolean) {
        viewModelScope.launch {
            runBusy("正在更新频道设置…") {
                repository.setRssSourceOriginalContentEnabled(source.url, enabled)
                sessionState.value = sessionState.value.copy(
                    message = if (enabled) "已开启原文阅读模式" else "已关闭原文阅读模式",
                    error = null
                )
            }
        }
    }

    fun setRssSourceContinuePlaybackInBackground(
        source: PhoneRssSourceEntity,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            runBusy("正在更新频道设置…") {
                repository.setRssSourceContinuePlaybackInBackground(source.url, enabled)
                sessionState.value = sessionState.value.copy(
                    message = if (enabled) "已允许在后台继续播放" else "已关闭后台继续播放",
                    error = null
                )
            }
        }
    }

    fun clearRssSourceContent(source: PhoneRssSourceEntity) {
        viewModelScope.launch {
            runBusy("正在清空频道内容…") {
                val deletedCount = repository.clearRssSourceContent(source.url)
                sessionState.value = sessionState.value.copy(
                    message = "已清空频道内容：$deletedCount 篇",
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
            if (sessionState.value.isBusy) return@launch
            beginSmoothedSyncProgress(MainSyncProgressUi(phase = "探测手表中", percent = 0))
            sessionState.value = sessionState.value.copy(
                isBusy = true,
                message = "探测手表中",
                syncStatusMessage = "探测手表中",
                syncStatusError = null,
                error = null,
                bluetoothDevicePrompt = null,
                conflictPrompt = null
            )
            val probeTargets = runCatching {
                bluetoothSyncManager.probeLibrarySyncTargets(::updateBluetoothProbeProgress)
            }.getOrElse { throwable ->
                clearSmoothedSyncProgress()
                sessionState.value = sessionState.value.copy(
                    isBusy = false,
                    message = null,
                    syncStatusMessage = null,
                    syncStatusError = null,
                    error = null,
                    bluetoothDevicePrompt = null,
                    conflictPrompt = null
                )
                _toastEvent.tryEmit(throwable.message ?: "操作失败")
                showSupportAlert(
                    title = "同步失败",
                    message = "探测手表时出错，请检查蓝牙是否开启、手表端是否已打开应用。",
                    errorDetails = throwable.message
                )
                return@launch
            }
            val reachableDevices = probeTargets.devices
            when (reachableDevices.size) {
                0 -> {
                    clearSmoothedSyncProgress()
                    sessionState.value = sessionState.value.copy(
                        isBusy = false,
                        message = null,
                        syncStatusMessage = null,
                        syncStatusError = null,
                        error = null,
                        bluetoothDevicePrompt = null
                    )
                    _toastEvent.tryEmit("未找到已打开 WatchRSS 的已配对手表，请在手表端打开应用并保持亮屏后重试")
                    showSupportAlert(
                        title = "未找到可同步手表",
                        message = "未找到已打开 WatchRSS 的已配对手表。请确认手表端已打开应用并保持亮屏，然后重试。",
                        errorDetails = "未找到已打开 WatchRSS 的已配对手表"
                    )
                }
                1 -> {
                    val device = reachableDevices.single()
                    sessionState.value = sessionState.value.copy(
                        syncTransportLabel = transportLabel(device.address)
                    )
                    runLibrarySync(device.toUi(), probeTargets.sessionLease)
                }
                else -> {
                    clearSmoothedSyncProgress()
                    val message = "发现 ${reachableDevices.size} 块可同步手表"
                    sessionState.value = sessionState.value.copy(
                        isBusy = false,
                        message = message,
                        syncStatusMessage = message,
                        syncStatusError = null,
                        error = null,
                        bluetoothDevicePrompt = MainBluetoothDevicePromptUi(
                            devices = reachableDevices.map { it.toUi() },
                            purpose = MainBluetoothDevicePromptPurpose.LIBRARY
                        )
                    )
                }
            }
        }
    }

    fun syncAccountByBluetooth() {
        viewModelScope.launch {
            if (sessionState.value.isBusy) return@launch
            beginSmoothedSyncProgress(MainSyncProgressUi(phase = "探测手表中", percent = 0))
            sessionState.value = sessionState.value.copy(
                isBusy = true,
                message = "探测手表中",
                syncStatusMessage = "探测手表中",
                syncStatusError = null,
                error = null,
                bluetoothDevicePrompt = null,
                conflictPrompt = null
            )
            val probeTargets = runCatching {
                bluetoothSyncManager.probeLibrarySyncTargets(::updateBluetoothProbeProgress)
            }.getOrElse { throwable ->
                clearSmoothedSyncProgress()
                sessionState.value = sessionState.value.copy(
                    isBusy = false,
                    message = null,
                    syncStatusMessage = null,
                    syncStatusError = null,
                    error = null,
                    bluetoothDevicePrompt = null,
                    conflictPrompt = null
                )
                usageTelemetry.recordSyncResult(false, "account", throwable.message)
                _toastEvent.tryEmit(throwable.message ?: "操作失败")
                showSupportAlert(
                    title = "账号同步失败",
                    message = "探测手表时出错，请检查蓝牙是否开启、手表端是否已打开应用。",
                    errorDetails = throwable.message
                )
                return@launch
            }
            val reachableDevices = probeTargets.devices
            when (reachableDevices.size) {
                0 -> {
                    clearSmoothedSyncProgress()
                    sessionState.value = sessionState.value.copy(
                        isBusy = false,
                        message = null,
                        syncStatusMessage = null,
                        syncStatusError = null,
                        error = null,
                        bluetoothDevicePrompt = null
                    )
                    usageTelemetry.recordSyncResult(false, "account", "no_watch")
                    _toastEvent.tryEmit("未找到已打开 WatchRSS 的已配对手表，请在手表端打开应用并保持亮屏后重试")
                    showSupportAlert(
                        title = "未找到可同步手表",
                        message = "未找到已打开 WatchRSS 的已配对手表。请确认手表端已打开应用并保持亮屏，然后重试。",
                        errorDetails = "未找到已打开 WatchRSS 的已配对手表"
                    )
                }
                1 -> {
                    val device = reachableDevices.single()
                    sessionState.value = sessionState.value.copy(
                        syncTransportLabel = transportLabel(device.address)
                    )
                    runAccountSync(device.toUi(), probeTargets.sessionLease)
                }
                else -> {
                    clearSmoothedSyncProgress()
                    val message = "发现 ${reachableDevices.size} 块可同步手表"
                    sessionState.value = sessionState.value.copy(
                        isBusy = false,
                        message = message,
                        syncStatusMessage = message,
                        syncStatusError = null,
                        error = null,
                        bluetoothDevicePrompt = MainBluetoothDevicePromptUi(
                            devices = reachableDevices.map { it.toUi() },
                            purpose = MainBluetoothDevicePromptPurpose.ACCOUNT
                        )
                    )
                }
            }
        }
    }

    fun chooseBluetoothDeviceForSync(device: MainBluetoothDeviceUi) {
        viewModelScope.launch {
            val purpose = sessionState.value.bluetoothDevicePrompt?.purpose
            sessionState.value = sessionState.value.copy(
                bluetoothDevicePrompt = null,
                syncTransportLabel = device.transportLabel
            )
            if (purpose == MainBluetoothDevicePromptPurpose.ACCOUNT) {
                runAccountSync(device)
            } else {
                runLibrarySync(device)
            }
        }
    }

    fun dismissBluetoothDevicePrompt() {
        clearSmoothedSyncProgress()
        sessionState.value = sessionState.value.copy(
            bluetoothDevicePrompt = null,
            message = null,
            syncStatusMessage = null,
            syncStatusError = null
        )
    }

    private suspend fun runLibrarySync(
        device: MainBluetoothDeviceUi,
        reusableSession: PhoneSyncSession? = null
    ) {
        beginSmoothedSyncProgress(MainSyncProgressUi(phase = "建立连接中", percent = 0))
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = "建立连接中",
            syncStatusMessage = "建立连接中",
            syncStatusError = null,
            error = null,
            bluetoothDevicePrompt = null
        )
        runCatching {
            val result = bluetoothSyncManager.syncAll(
                device = PhoneBluetoothWatchDevice(
                    name = device.name,
                    address = device.address,
                    uuidCount = 0,
                    remoteDeviceId = device.remoteDeviceId,
                    bluetoothAddress = device.bluetoothAddress,
                    supportsPersistentSession = device.supportsPersistentSession
                ),
                reusableSession = reusableSession,
                onProgress = ::updateLibrarySyncProgress,
                resolveDeleteConflicts = ::resolveDeleteConflicts
            )
            val stats = result.libraryStats
            val noteStats = result.noteStats
            val deviceName = result.deviceName.ifBlank { "手表" }
            val readerWarning = result.readerSyncWarning
            val accountWarning = result.accountSyncWarning
            val tokenWarning = result.tokenUsageSyncWarning
            completeSmoothedSyncProgress()
            val message = if (
                readerWarning == null && accountWarning == null && tokenWarning == null
            ) {
                "已与 $deviceName 同步完成"
            } else {
                "已与 $deviceName 完成资料库和备忘录同步"
            }
            sessionState.value = sessionState.value.copy(
                message = message,
                syncStatusMessage = message,
                syncStatusError = null,
                syncTransportLabel = transportLabel(result.deviceAddress),
                error = null,
                syncProgress = null
            )
            _toastEvent.tryEmit(
                if (accountWarning != null || readerWarning != null || tokenWarning != null) {
                    buildString {
                        append(message)
                        accountWarning?.let { append("；账号授权同步失败：$it") }
                        readerWarning?.let { append("；阅读器资源同步失败：$it") }
                        tokenWarning?.let { append("；词元用量同步失败：$it") }
                    }
                } else if (stats != null) {
                    buildString {
                        append("已与 $deviceName 同步：文章发送 ${stats.sent}，收到 ${stats.received}，合并 ${stats.merged}")
                        append("；RSS源发送 ${stats.sourcesSent}，收到 ${stats.sourcesReceived}，合并 ${stats.sourcesMerged}")
                        if (noteStats != null) {
                            append("；备忘录发送 ${noteStats.sent}，手表应用 ${noteStats.appliedOnWatch}，收到 ${noteStats.received}")
                            if (noteStats.conflictsOnPhone > 0) {
                                append("，冲突 ${noteStats.conflictsOnPhone}")
                            }
                        }
                    }
                } else {
                    "已与 $deviceName 同步"
                }
            )
            usageTelemetry.recordSyncResult(true, "library")
            tipManager.recordEvent(TipEvents.SYNC_COMPLETED)
        }.onFailure { throwable ->
            val error = syncFailureMessage(
                throwable = throwable,
                phase = verificationProgressTarget?.phase
                    ?: sessionState.value.syncProgress?.phase
                    ?: sessionState.value.syncStatusMessage
            )
            clearSmoothedSyncProgress()
            sessionState.value = sessionState.value.copy(
                syncStatusMessage = null,
                syncStatusError = error,
                error = null,
                conflictPrompt = null
            )
            usageTelemetry.recordSyncResult(false, "library", throwable.message)
            _toastEvent.tryEmit(error)
            showSupportAlert(
                title = "资料库同步失败",
                message = "同步过程中断，请确认手表保持亮屏并重试。",
                errorDetails = error
            )
        }
        conflictResolutionDeferred?.complete(PhoneSyncConflictResolution.KEEP_LATEST)
        conflictResolutionDeferred = null
        sessionState.value = sessionState.value.copy(isBusy = false, conflictPrompt = null)
    }

    private suspend fun runAccountSync(
        device: MainBluetoothDeviceUi,
        reusableSession: PhoneSyncSession? = null
    ) {
        beginSmoothedSyncProgress(MainSyncProgressUi(phase = "同步账号中", percent = 20))
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = "同步账号中",
            syncStatusMessage = "同步账号中",
            syncStatusError = null,
            error = null,
            bluetoothDevicePrompt = null
        )
        runCatching {
            val result = bluetoothSyncManager.syncAccount(
                PhoneBluetoothWatchDevice(
                    name = device.name,
                    address = device.address,
                    uuidCount = 0,
                    remoteDeviceId = device.remoteDeviceId,
                    bluetoothAddress = device.bluetoothAddress,
                    supportsPersistentSession = device.supportsPersistentSession
                ),
                reusableSession = reusableSession
            )
            completeSmoothedSyncProgress()
            val deviceName = result.deviceName.ifBlank { device.name.ifBlank { "手表" } }
            val message = "已向 $deviceName 同步账号"
            sessionState.value = sessionState.value.copy(
                message = message,
                syncStatusMessage = message,
                syncStatusError = null,
                error = null,
                syncProgress = null
            )
            usageTelemetry.recordSyncResult(true, "account")
            _toastEvent.tryEmit(message)
        }.onFailure { throwable ->
            clearSmoothedSyncProgress()
            sessionState.value = sessionState.value.copy(
                syncStatusMessage = null,
                syncStatusError = null,
                error = null,
                conflictPrompt = null
            )
            usageTelemetry.recordSyncResult(false, "account", throwable.message)
            _toastEvent.tryEmit(throwable.message ?: "操作失败")
            showSupportAlert(
                title = "账号同步失败",
                message = "账号同步过程中断，请确认手表保持亮屏并重试。",
                errorDetails = throwable.message
            )
        }
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
            runBusy("正在通过蓝牙发送 RSS 地址…", showInSyncStatus = true) {
                val result = bluetoothSyncManager.sendRemoteInput(url)
                usageTelemetry.recordRemoteInputSent(url)
                val message = "已通过蓝牙发送到 ${result.deviceName.ifBlank { "手表" }}"
                sessionState.value = sessionState.value.copy(
                    message = message,
                    syncStatusMessage = message,
                    syncStatusError = null,
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
            runBusy("正在通过蓝牙同步${type.displayName}…", showInSyncStatus = true) {
                val result = bluetoothSyncManager.syncSavedItems(type)
                val message = "已从 ${result.deviceName.ifBlank { "手表" }} 同步 ${result.importedCount ?: 0} 条${type.displayName}"
                sessionState.value = sessionState.value.copy(
                    message = message,
                    syncStatusMessage = message,
                    syncStatusError = null,
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
                usageTelemetry.recordArticleImported(
                    source = "web",
                    url = article.url,
                    title = article.title
                )
                tipManager.recordEvent(TipEvents.ARTICLE_IMPORTED)
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
            tipManager.recordEvent(
                when (type) {
                    PhoneSavedItemType.FAVORITE -> TipEvents.FAVORITE_TOGGLED
                    PhoneSavedItemType.WATCH_LATER -> TipEvents.WATCH_LATER_TOGGLED
                }
            )
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
                syncStatusMessage = null,
                syncStatusError = null,
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
        clearSmoothedSyncProgress()
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = "双端内容有冲突，请选择处理方式",
            syncStatusMessage = "双端内容有冲突，请选择处理方式",
            syncStatusError = null,
            error = null,
            conflictPrompt = MainConflictPromptUi(conflicts = conflicts)
        )
        return try {
            val resolution = deferred.await()
            conflicts.associate { conflict -> conflict.articleId to resolution }
        } finally {
            if (conflictResolutionDeferred === deferred) {
                conflictResolutionDeferred = null
            }
            beginSmoothedSyncProgress(MainSyncProgressUi(phase = "信息传输中", percent = 30))
            sessionState.value = sessionState.value.copy(
                message = "信息传输中",
                syncStatusMessage = "信息传输中",
                syncStatusError = null,
                conflictPrompt = null
            )
        }
    }

    private suspend fun runBusy(
        busyMessage: String,
        showInSyncStatus: Boolean = false,
        block: suspend () -> Unit
    ) {
        sessionState.value = sessionState.value.copy(
            isBusy = true,
            message = busyMessage,
            syncStatusMessage = if (showInSyncStatus) busyMessage else null,
            syncStatusError = null,
            error = null,
            syncProgress = null,
            conflictPrompt = null,
            bluetoothDevicePrompt = null
        )
        runCatching { block() }
            .onFailure { throwable ->
                val error = throwable.message ?: "操作失败"
                sessionState.value = sessionState.value.copy(
                    error = error,
                    syncStatusError = if (showInSyncStatus) error else null
                )
        }
        sessionState.value = sessionState.value.copy(isBusy = false, syncProgress = null)
    }

    private fun updateLibrarySyncProgress(progress: PhoneBluetoothSyncProgress) {
        val percent = progress.percent.coerceIn(0, 100)
        val uiProgress = MainSyncProgressUi(
            phase = progress.stage.displayName,
            percent = percent,
            indeterminate = progress.stage == PhoneBluetoothSyncStage.VERIFYING,
            bytesTransferred = progress.bytesTransferred,
            bytesPerSecond = progress.bytesPerSecond
        )
        if (progress.stage == PhoneBluetoothSyncStage.VERIFYING) {
            transitionToVerification(uiProgress)
        } else {
            verificationTransitionJob?.cancel()
            verificationTransitionJob = null
            verificationProgressTarget = null
            updateSmoothedSyncProgress(uiProgress)
        }
    }

    private fun transitionToVerification(progress: MainSyncProgressUi) {
        verificationProgressTarget = progress
        if (sessionState.value.syncProgress?.indeterminate == true) {
            updateSmoothedSyncProgress(progress)
            return
        }
        if (verificationTransitionJob?.isActive == true) return
        smoothedSyncProgressJob?.cancel()
        smoothedSyncProgressJob = null
        sessionState.value.syncProgress?.let { current ->
            sessionState.value = sessionState.value.copy(
                syncProgress = current.copy(percent = 100, indeterminate = false)
            )
        }
        verificationTransitionJob = viewModelScope.launch {
            delay(VERIFICATION_TRANSITION_HOLD_MS)
            verificationProgressTarget?.let(::updateSmoothedSyncProgress)
        }
    }

    private fun updateBluetoothProbeProgress(completed: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val percent = ((completed.coerceIn(0, safeTotal).toFloat() / safeTotal.toFloat()) * 100).toInt()
        updateSmoothedSyncProgress(
            MainSyncProgressUi(
                phase = "探测手表中",
                percent = percent.coerceIn(0, 100)
            )
        )
    }

    private fun PhoneBluetoothWatchDevice.toUi(): MainBluetoothDeviceUi =
        MainBluetoothDeviceUi(
            name = name.ifBlank { "未知手表" },
            address = address,
            remoteDeviceId = remoteDeviceId,
            transportLabel = transportLabel(address),
            bluetoothAddress = bluetoothAddress,
            supportsPersistentSession = supportsPersistentSession
        )

    private fun transportLabel(address: String): String =
        PhoneIpSyncSessionRegistry.session(address)?.let { session ->
            "${if (session.routeKind.wireName == "wifiLan") "Wi-Fi IP" else "IP"} · ${session.remoteAddress}"
        } ?: "RFCOMM"

    private fun beginSmoothedSyncProgress(progress: MainSyncProgressUi) {
        smoothedSyncProgressJob?.cancel()
        smoothedSyncProgressJob = null
        smoothedSyncProgressTarget = progress
        sessionState.value = sessionState.value.copy(
            message = progress.phase,
            syncStatusMessage = progress.phase,
            syncStatusError = null,
            error = null,
            syncProgress = progress
        )
    }

    private fun updateSmoothedSyncProgress(progress: MainSyncProgressUi) {
        val visible = sessionState.value.syncProgress
        val phaseChanged = visible?.phase != null && visible.phase != progress.phase
        if (phaseChanged) {
            smoothedSyncProgressJob?.cancel()
            smoothedSyncProgressJob = null
            smoothedSyncProgressTarget = progress.copy(percent = progress.percent.coerceIn(0, 100))
            sessionState.value = sessionState.value.copy(
                message = progress.phase,
                syncStatusMessage = progress.phase,
                syncStatusError = null,
                error = null,
                syncProgress = progress.copy(percent = progress.percent.coerceIn(0, 100))
            )
            return
        }
        val visiblePercent = visible?.percent ?: progress.percent
        val targetPercent = maxOf(progress.percent, visiblePercent).coerceIn(0, 100)
        smoothedSyncProgressTarget = progress.copy(percent = targetPercent)
        sessionState.value = sessionState.value.copy(
            message = progress.phase,
            syncStatusMessage = progress.phase,
            syncStatusError = null,
            error = null
        )
        if (visible == null) {
            sessionState.value = sessionState.value.copy(
                syncProgress = progress.copy(percent = targetPercent)
            )
            return
        }
        if (targetPercent <= visiblePercent) {
            sessionState.value = sessionState.value.copy(
                syncProgress = progress.copy(percent = visiblePercent.coerceIn(0, 100))
            )
            return
        }
        if (smoothedSyncProgressJob?.isActive == true) return
        smoothedSyncProgressJob = viewModelScope.launch {
            while (true) {
                val target = smoothedSyncProgressTarget ?: break
                val currentPercent = sessionState.value.syncProgress?.percent ?: target.percent
                if (currentPercent >= target.percent) break
                val gap = target.percent - currentPercent
                val step = when {
                    gap >= 24 -> 3
                    gap >= 8 -> 2
                    else -> 1
                }
                val nextPercent = (currentPercent + step).coerceAtMost(target.percent)
                sessionState.value = sessionState.value.copy(
                    message = target.phase,
                    syncStatusMessage = target.phase,
                    syncStatusError = null,
                    error = null,
                    syncProgress = target.copy(percent = nextPercent)
                )
                delay(SMOOTH_PROGRESS_TICK_MS)
            }
        }
    }

    private suspend fun completeSmoothedSyncProgress() {
        val current = sessionState.value.syncProgress ?: MainSyncProgressUi(phase = "同步完成", percent = 100)
        updateSmoothedSyncProgress(current.copy(phase = "同步完成", percent = 100, indeterminate = false))
        var ticks = 0
        while ((sessionState.value.syncProgress?.percent ?: 100) < 100 && ticks < SMOOTH_PROGRESS_FINISH_MAX_TICKS) {
            delay(SMOOTH_PROGRESS_TICK_MS)
            ticks += 1
        }
        sessionState.value.syncProgress?.takeIf { it.percent < 100 }?.let { progress ->
            sessionState.value = sessionState.value.copy(syncProgress = progress.copy(percent = 100))
        }
        delay(SMOOTH_PROGRESS_COMPLETE_HOLD_MS)
        clearSmoothedSyncProgress()
    }

    private fun clearSmoothedSyncProgress() {
        smoothedSyncProgressJob?.cancel()
        smoothedSyncProgressJob = null
        smoothedSyncProgressTarget = null
        verificationTransitionJob?.cancel()
        verificationTransitionJob = null
        verificationProgressTarget = null
        sessionState.value = sessionState.value.copy(syncProgress = null)
    }

    override fun onCleared() {
        super.onCleared()
        smoothedSyncProgressJob?.cancel()
        verificationTransitionJob?.cancel()
    }

    private companion object {
        private const val SMOOTH_PROGRESS_TICK_MS = 80L
        private const val SMOOTH_PROGRESS_COMPLETE_HOLD_MS = 180L
        private const val VERIFICATION_TRANSITION_HOLD_MS = 180L
        private const val SMOOTH_PROGRESS_FINISH_MAX_TICKS = 18
    }
}
