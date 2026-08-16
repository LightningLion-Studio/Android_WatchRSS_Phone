package com.lightningstudio.watchrss.phone.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class OnboardingUiState(
    val stepIndex: Int = 0,
    val draft: OnboardingDraft = OnboardingDraft(),
    val loginInProgress: Boolean = false,
    val importState: ImportState = ImportState.Idle,
    val phoneMasked: String? = null
) {
    /** 登录进行中时进度头展示第 19 步（虚拟计数步）。 */
    val displayStepIndex: Int
        get() = if (loginInProgress) OnboardingCatalogIndices.LOGIN_GUIDE_INDEX + 1 else stepIndex
}

class OnboardingViewModel(
    private val repository: PhoneCompanionRepository,
    private val accountRepository: PhoneAccountRepository,
    private val usageTelemetry: PhoneUsageTelemetry,
    private val draftStore: OnboardingDraftStore,
    private val profileStore: OnboardingProfileStore,
    private val consentStore: PhonePrivacyConsentStore
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        val draft = draftStore.load() ?: OnboardingDraft(stepIndex = 0)
        // 防御：未同意协议时任何输入步骤都不得可达（目录顺序已保证，这里再兜底一次）。
        val stepIndex = if (!consentStore.hasRequiredConsent() && draft.stepIndex > OnboardingCatalogIndices.CONSENT_INDEX) {
            OnboardingCatalogIndices.CONSENT_INDEX
        } else {
            draft.stepIndex.coerceIn(0, ONBOARDING_CATALOG.size - 1)
        }
        val importedArticle = draft.importedArticleId?.let { id ->
            ImportState.Success(id, draft.importedArticleTitle.orEmpty())
        }
        _state.value = OnboardingUiState(
            stepIndex = stepIndex,
            draft = draft.copy(stepIndex = stepIndex),
            importState = importedArticle ?: ImportState.Idle,
            phoneMasked = accountRepository.session.value?.phoneMasked
        )
    }

    fun answerStep(stepId: String, values: List<String>, reason: String? = null) {
        val cleanValues = values.map { it.trim() }.filter { it.isNotBlank() }
        val answers = _state.value.draft.answers.toMutableMap()
        if (cleanValues.isNotEmpty()) answers[stepId] = cleanValues
        reason?.trim()?.takeIf { it.isNotBlank() }?.let { answers["${stepId}_reason"] = listOf(it) }
        mutateDraft { draft -> draft.copy(answers = answers) }
        usageTelemetry.recordOnboardingStepCompleted()
        advance()
    }

    fun skipStep(stepId: String) {
        mutateDraft { draft ->
            draft.copy(skipped = draft.skipped + stepId, updatedAtMillis = System.currentTimeMillis())
        }
        usageTelemetry.recordOnboardingStepSkipped()
        advance()
    }

    fun advance() {
        val current = _state.value
        var next = current.stepIndex + 1
        // 虚拟登录计数步只在登录进行中可达；未登录时直接跳过。
        while (next < ONBOARDING_CATALOG.size &&
            ONBOARDING_CATALOG[next].type == StepType.LOGIN_VIRTUAL
        ) {
            next++
        }
        if (next >= ONBOARDING_CATALOG.size) return
        mutateDraft { it.copy(stepIndex = next, updatedAtMillis = System.currentTimeMillis()) }
        usageTelemetry.recordOnboardingStepCompleted()
    }

    /** 返回上一步；已在第 1 步则记一次 dropped（由 Activity 退出）。 */
    fun backOrDrop(): Boolean {
        val current = _state.value.stepIndex
        if (current <= 0) {
            usageTelemetry.recordOnboardingDropped()
            return false
        }
        mutateDraft { it.copy(stepIndex = current - 1, updatedAtMillis = System.currentTimeMillis()) }
        return true
    }

    fun drop() = usageTelemetry.recordOnboardingDropped()

    fun startImport(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _state.update { it.copy(importState = ImportState.Failure("请输入以 http:// 或 https:// 开头的链接")) }
            return
        }
        _state.update { it.copy(importState = ImportState.Loading) }
        viewModelScope.launch {
            val result = withTimeoutOrNull(IMPORT_TIMEOUT_MILLIS) {
                runCatching { repository.importWebArticle(trimmed) }.getOrNull()
            }
            if (result == null) {
                _state.update { it.copy(importState = ImportState.Failure("导入失败：网络超时或文章无法解析，请换一个链接试试")) }
                usageTelemetry.recordOnboardingImportFailed()
                return@launch
            }
            mutateDraft { draft ->
                draft.copy(
                    importedArticleId = result.articleId,
                    importedArticleTitle = result.title.trim().takeIf { it.isNotBlank() },
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            _state.update { it.copy(importState = ImportState.Success(result.articleId, result.title)) }
            usageTelemetry.recordOnboardingImportSucceeded()
        }
    }

    fun resetImport() {
        _state.update { it.copy(importState = ImportState.Idle) }
    }

    fun launchLogin() {
        _state.update { it.copy(loginInProgress = true) }
    }

    /** AccountActivity 返回后调用：登录成功则直接跳到授权说明，否则留在登录引导。 */
    fun onLoginReturned() {
        val hasUsableSession = accountRepository.session.value?.isExpired == false
        val target = if (hasUsableSession) OnboardingCatalogIndices.LOGIN_GUIDE_INDEX + 3 else OnboardingCatalogIndices.LOGIN_GUIDE_INDEX
        mutateDraft { it.copy(stepIndex = target, updatedAtMillis = System.currentTimeMillis()) }
        _state.update {
            it.copy(loginInProgress = false, phoneMasked = accountRepository.session.value?.phoneMasked)
        }
    }

    /** 完成引导：沉淀档案、清草稿、记遥测。completeOobe 与 finish 由 Activity 负责。 */
    fun complete() {
        val draft = _state.value.draft
        profileStore.save(OnboardingProfileBuilder.buildProfile(draft))
        draftStore.clear()
        usageTelemetry.recordOnboardingCompleted()
    }

    private fun mutateDraft(transform: (OnboardingDraft) -> OnboardingDraft) {
        _state.update { state ->
            state.copy(draft = transform(state.draft.copy(updatedAtMillis = System.currentTimeMillis())))
        }
        _state.value.draft.let(draftStore::save)
    }

    companion object {
        private const val IMPORT_TIMEOUT_MILLIS = 60_000L
    }
}

class OnboardingViewModelFactory(
    private val repository: PhoneCompanionRepository,
    private val accountRepository: PhoneAccountRepository,
    private val usageTelemetry: PhoneUsageTelemetry,
    private val draftStore: OnboardingDraftStore,
    private val profileStore: OnboardingProfileStore,
    private val consentStore: PhonePrivacyConsentStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            return OnboardingViewModel(
                repository,
                accountRepository,
                usageTelemetry,
                draftStore,
                profileStore,
                consentStore
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
