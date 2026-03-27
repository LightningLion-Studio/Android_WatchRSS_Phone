package com.lightningstudio.watchrss.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.acoustic.AcousticAudioPlayer
import com.lightningstudio.watchrss.phone.acoustic.AcousticAudioReceiver
import com.lightningstudio.watchrss.phone.acoustic.AcousticCodec
import com.lightningstudio.watchrss.phone.connection.AcousticConnectionProtocol
import com.lightningstudio.watchrss.phone.connection.PhoneConnectionAbility
import com.lightningstudio.watchrss.phone.connection.guided.GuidedSessionState
import com.lightningstudio.watchrss.phone.connection.guided.PhoneGuidedSessionManager
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.model.WatchAbility
import com.lightningstudio.watchrss.phone.data.model.WatchEndpoint
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class MainUiState(
    val endpoint: WatchEndpoint? = null,
    val abilities: List<WatchAbility> = emptyList(),
    val rssUrlInput: String = "",
    val isBusy: Boolean = false,
    val isPureSoundListening: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val guidedSession: GuidedSessionState? = null,
    val favorites: List<PhoneSavedItemEntity> = emptyList(),
    val watchLater: List<PhoneSavedItemEntity> = emptyList()
)

class MainViewModel(
    private val repository: PhoneCompanionRepository,
    private val guidedSessionManager: PhoneGuidedSessionManager
) : ViewModel() {
    private val sessionState = MutableStateFlow(MainUiState())
    private val acousticPlayer = AcousticAudioPlayer()
    private val acousticReceiver = AcousticAudioReceiver()

    val uiState: StateFlow<MainUiState> = combine(
        sessionState,
        repository.observeSavedItems(PhoneSavedItemType.FAVORITE),
        repository.observeSavedItems(PhoneSavedItemType.WATCH_LATER)
    ) { state, favorites, watchLater ->
        state.copy(
            favorites = favorites,
            watchLater = watchLater
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState()
    )

    fun updateRssUrlInput(value: String) {
        sessionState.value = sessionState.value.copy(rssUrlInput = value)
    }

    fun clearMessage() {
        sessionState.value = sessionState.value.copy(message = null, error = null)
    }

    fun connectWithQr(rawText: String) {
        viewModelScope.launch {
            runBusy("正在连接手表…") {
                val endpoint = repository.parseEndpointFromQr(rawText)
                val abilities = withContext(Dispatchers.IO) {
                    repository.verifyHealth(endpoint)
                    repository.fetchAbilities(endpoint)
                }
                sessionState.value = sessionState.value.copy(
                    endpoint = endpoint,
                    abilities = abilities,
                    message = "已连接 ${endpoint.displayLabel}",
                    error = null
                )
            }
        }
    }

    fun sendRemoteUrl() {
        val endpoint = sessionState.value.endpoint ?: return
        val url = sessionState.value.rssUrlInput.trim()
        if (url.isBlank()) {
            sessionState.value = sessionState.value.copy(error = "请输入 RSS 地址")
            return
        }
        viewModelScope.launch {
            runBusy("正在发送 RSS…") {
                withContext(Dispatchers.IO) {
                    repository.sendRemoteUrl(endpoint, url)
                }
                sessionState.value = sessionState.value.copy(
                    message = "RSS 地址已发送到手表",
                    error = null,
                    rssUrlInput = ""
                )
            }
        }
    }

    fun syncSavedItems(type: PhoneSavedItemType) {
        val endpoint = sessionState.value.endpoint ?: return
        viewModelScope.launch {
            runBusy("正在同步${type.displayName}…") {
                val count = withContext(Dispatchers.IO) {
                    repository.syncSavedItems(endpoint, type)
                }
                sessionState.value = sessionState.value.copy(
                    message = "已同步 $count 条${type.displayName}",
                    error = null
                )
            }
        }
    }

    fun sendPureSoundRemoteUrl() {
        val url = sessionState.value.rssUrlInput.trim()
        if (url.isBlank()) {
            sessionState.value = sessionState.value.copy(error = "请输入 RSS 地址")
            return
        }

        viewModelScope.launch {
            runBusy("正在通过声波发送 RSS…") {
                val packet = withContext(Dispatchers.Default) {
                    AcousticCodec.encode(AcousticConnectionProtocol.buildPureSoundRemoteInput(url))
                }
                acousticPlayer.play(packet)
                sessionState.value = sessionState.value.copy(
                    message = "已通过声波播出 RSS 地址",
                    error = null,
                    rssUrlInput = ""
                )
            }
        }
    }

    fun receivePureSoundSync() {
        viewModelScope.launch {
            sessionState.value = sessionState.value.copy(
                isBusy = true,
                isPureSoundListening = true,
                message = "正在聆听手表发来的声波数据…",
                error = null
            )
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    acousticReceiver.listen(timeoutMs = 300_000L)
                } ?: error("未收到有效的声波数据")
                val envelope = AcousticConnectionProtocol.parsePureSound(bytes)
                val type = envelope.ability.savedItemType ?: error("当前声波内容不是同步数据")
                val count = withContext(Dispatchers.IO) {
                    repository.replaceSavedItems(type, envelope.items ?: JSONArray())
                }
                sessionState.value = sessionState.value.copy(
                    message = "已通过声波接收 $count 条${type.displayName}",
                    error = null
                )
            }.onFailure { throwable ->
                sessionState.value = sessionState.value.copy(
                    error = throwable.message ?: "声波接收失败"
                )
            }
            sessionState.value = sessionState.value.copy(
                isBusy = false,
                isPureSoundListening = false
            )
        }
    }

    fun startGuidedRemoteInput() {
        val url = sessionState.value.rssUrlInput.trim()
        if (url.isBlank()) {
            sessionState.value = sessionState.value.copy(error = "请输入 RSS 地址")
            return
        }
        startGuidedSession(PhoneConnectionAbility.REMOTE_INPUT, url)
    }

    fun startGuidedFavoritesSync() {
        startGuidedSession(PhoneConnectionAbility.SYNC_FAVORITES, null)
    }

    fun startGuidedWatchLaterSync() {
        startGuidedSession(PhoneConnectionAbility.SYNC_WATCH_LATER, null)
    }

    fun stopGuidedSession() {
        guidedSessionManager.stopSession()
        sessionState.value = sessionState.value.copy(
            guidedSession = null,
            message = "已停止声波引导 WiFi 会话",
            error = null
        )
    }

    private fun startGuidedSession(
        ability: PhoneConnectionAbility,
        remoteUrl: String?
    ) {
        viewModelScope.launch {
            runBusy("正在启动${ability.displayName}引导会话…") {
                val session = guidedSessionManager.startSession(ability, remoteUrl)
                acousticPlayer.play(session.packet)
                sessionState.value = sessionState.value.copy(
                    guidedSession = session,
                    message = when (ability) {
                        PhoneConnectionAbility.REMOTE_INPUT -> "已播放热点信息，等待手表连入并拉取 RSS"
                        PhoneConnectionAbility.SYNC_FAVORITES -> "已播放热点信息，等待手表同步收藏"
                        PhoneConnectionAbility.SYNC_WATCH_LATER -> "已播放热点信息，等待手表同步稍后再看"
                    },
                    error = null,
                    rssUrlInput = if (ability == PhoneConnectionAbility.REMOTE_INPUT) "" else sessionState.value.rssUrlInput
                )
            }
        }
    }

    private suspend fun runBusy(busyMessage: String, block: suspend () -> Unit) {
        sessionState.value = sessionState.value.copy(isBusy = true, message = busyMessage, error = null)
        runCatching { block() }
            .onFailure { throwable ->
                sessionState.value = sessionState.value.copy(error = throwable.message ?: "操作失败")
        }
        sessionState.value = sessionState.value.copy(isBusy = false)
    }

    override fun onCleared() {
        guidedSessionManager.stopSession()
        super.onCleared()
    }
}
