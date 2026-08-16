package com.lightningstudio.watchrss.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lightningstudio.watchrss.phone.onboarding.OnboardingViewModel
import com.lightningstudio.watchrss.phone.onboarding.OnboardingViewModelFactory
import com.lightningstudio.watchrss.phone.onboarding.ui.OnboardingActions
import com.lightningstudio.watchrss.phone.onboarding.ui.OnboardingScreen
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore

/**
 * 投入型引导漏斗（24 步）。保持原类名与 createIntent 契约；
 * 完成路径不变：completeOobe() → RESULT_OK → finish()，MainActivity 门禁逻辑零改动。
 */
class PhoneOobeActivity : ComponentActivity() {
    private lateinit var consentStore: PhonePrivacyConsentStore

    private val viewModel: OnboardingViewModel by viewModels {
        val container = (application as PhoneCompanionApplication).container
        OnboardingViewModelFactory(
            container.repository,
            container.accountRepository,
            container.usageTelemetry,
            container.onboardingDraftStore,
            container.onboardingProfileStore,
            PhonePrivacyConsentStore(this@PhoneOobeActivity)
        )
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onLoginReturned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentStore = PhonePrivacyConsentStore(this)
        setContent {
            val state by viewModel.state.collectAsState()
            BackHandler {
                if (!viewModel.backOrDrop()) finishAffinity()
            }
            OnboardingScreen(
                state = state,
                actions = OnboardingActions(
                    onAnswer = viewModel::answerStep,
                    onSkip = viewModel::skipStep,
                    onAdvance = viewModel::advance,
                    onAcceptPolicies = {
                        consentStore.acceptRequiredPolicies()
                        (application as PhoneCompanionApplication).onPrivacyConsentGranted()
                        viewModel.advance()
                    },
                    onRejectPolicies = {
                        viewModel.drop()
                        finishAffinity()
                    },
                    onOpenUserAgreement = { openLegalDocument(LegalDocument.USER_AGREEMENT) },
                    onOpenPrivacy = { openLegalDocument(LegalDocument.PRIVACY_POLICY) },
                    onOpenPaidAgreement = { openLegalDocument(LegalDocument.PAID_SERVICE_AGREEMENT) },
                    onLogin = {
                        viewModel.launchLogin()
                        loginLauncher.launch(
                            AccountActivity.createIntent(this, finishAfterLogin = true)
                        )
                    },
                    onImport = viewModel::startImport,
                    onResetImport = viewModel::resetImport,
                    onComplete = {
                        viewModel.complete()
                        consentStore.completeOobe()
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            )
        }
    }

    private fun openLegalDocument(document: LegalDocument) {
        startActivity(LegalDocumentActivity.createIntent(this, document))
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, PhoneOobeActivity::class.java)
    }
}
