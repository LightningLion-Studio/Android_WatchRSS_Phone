package com.lightningstudio.watchrss.phone.account

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException

class PhonePasskeyCoordinator(
    private val activity: ComponentActivity,
    private val accountRepository: PhoneAccountRepository,
    private val credentialManager: CredentialManager = CredentialManager.create(activity)
) {
    private var preparedLogin: PreparedPasskeyLogin? = null

    suspend fun createPasskey() {
        val options = accountRepository.startPasskeyRegistration()
        val response = try {
            credentialManager.createCredential(
                context = activity,
                request = CreatePublicKeyCredentialRequest(options.requestJson)
            )
        } catch (error: CreateCredentialException) {
            throw IllegalStateException(createErrorMessage(error), error)
        }
        val passkey = response as? CreatePublicKeyCredentialResponse
            ?: error("系统未返回 Passkey 注册凭据")
        accountRepository.finishPasskeyRegistration(
            challengeId = options.challengeId,
            credentialJson = passkey.registrationResponseJson
        )
    }

    suspend fun login(phone: String): PhoneAccountSession {
        val normalizedPhone = phone.trim()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            preparedLogin
                ?.takeIf { it.phone == normalizedPhone }
                ?.let { return finishPreparedLogin(it) }
            if (!prepareLoginApi34(normalizedPhone)) {
                error("本机没有可用的通行密钥")
            }
            return finishPreparedLogin(requireNotNull(preparedLogin))
        }

        val options = accountRepository.startPasskeyAuthentication(phone)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPublicKeyCredentialOption(options.requestJson))
            .setPreferImmediatelyAvailableCredentials(true)
            .build()
        val response = try {
            credentialManager.getCredential(
                context = activity,
                request = request
            )
        } catch (error: GetCredentialException) {
            throw IllegalStateException(getErrorMessage(error), error)
        }
        val passkey = response.credential as? PublicKeyCredential
            ?: error("系统未返回 Passkey 登录凭据")
        return accountRepository.finishPasskeyAuthentication(
            challengeId = options.challengeId,
            credentialJson = passkey.authenticationResponseJson
        )
    }

    /**
     * Checks for a matching local passkey without presenting Credential Manager UI.
     * Android does not expose reliable candidate metadata before API 34, so older
     * releases deliberately keep the shortcut hidden and retain SMS as the fallback.
     */
    suspend fun prepareLogin(phone: String): Boolean {
        preparedLogin = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return prepareLoginApi34(phone.trim())
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun prepareLoginApi34(phone: String): Boolean {
        val options = accountRepository.startPasskeyAuthentication(phone)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPublicKeyCredentialOption(options.requestJson))
            .setPreferImmediatelyAvailableCredentials(true)
            .build()
        val prepared = try {
            credentialManager.prepareGetCredential(request)
        } catch (_: GetCredentialException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        val hasLocalCandidate =
            prepared.hasCredentialResults(PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL)
        val hasRemoteCandidate = prepared.hasRemoteResults()
        if (!shouldOfferPasskeyLogin(hasLocalCandidate, hasRemoteCandidate)) {
            return false
        }
        val handle = prepared.pendingGetCredentialHandle ?: return false
        preparedLogin = PreparedPasskeyLogin(
            phone = phone,
            challengeId = options.challengeId,
            handle = handle
        )
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun finishPreparedLogin(
        prepared: PreparedPasskeyLogin
    ): PhoneAccountSession {
        preparedLogin = null
        val response = try {
            credentialManager.getCredential(
                context = activity,
                pendingGetCredentialHandle = prepared.handle
            )
        } catch (error: GetCredentialException) {
            throw IllegalStateException(getErrorMessage(error), error)
        }
        val passkey = response.credential as? PublicKeyCredential
            ?: error("系统未返回 Passkey 登录凭据")
        return accountRepository.finishPasskeyAuthentication(
            challengeId = prepared.challengeId,
            credentialJson = passkey.authenticationResponseJson
        )
    }

    private fun createErrorMessage(error: CreateCredentialException): String =
        when (error) {
            is CreateCredentialCancellationException -> "已取消创建 Passkey"
            is CreatePublicKeyCredentialDomException -> {
                if (
                    error.domError is InvalidStateError ||
                    error.errorMessage?.contains("excluded credential", ignoreCase = true) == true
                ) {
                    "本机已有这个账号的 Passkey，可直接使用 Passkey 登录"
                } else {
                    "无法创建 Passkey，请确认系统凭据服务和屏幕锁可用"
                }
            }
            else -> "无法创建 Passkey，请确认系统凭据服务和屏幕锁可用"
        }

    private fun getErrorMessage(error: GetCredentialException): String =
        when (error) {
            is GetCredentialCancellationException -> "已取消 Passkey 登录"
            is NoCredentialException -> "该账号没有可用的 Passkey"
            else -> "Passkey 登录失败，请改用短信验证码"
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private data class PreparedPasskeyLogin(
        val phone: String,
        val challengeId: String,
        val handle: PrepareGetCredentialResponse.PendingGetCredentialHandle
    )
}

internal fun shouldOfferPasskeyLogin(
    hasLocalCandidate: Boolean,
    hasRemoteCandidate: Boolean
): Boolean = hasLocalCandidate && !hasRemoteCandidate
