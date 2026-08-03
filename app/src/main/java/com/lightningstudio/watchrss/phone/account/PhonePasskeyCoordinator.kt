package com.lightningstudio.watchrss.phone.account

import androidx.activity.ComponentActivity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
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
        val options = accountRepository.startPasskeyAuthentication(phone)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPublicKeyCredentialOption(options.requestJson))
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
}
