package io.orangebuffalo.renalo.auth.passkeys

import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.ByteArray as YubicoByteArray
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import io.orangebuffalo.renalo.user.UserRepository
import jakarta.inject.Singleton
import java.util.Optional

@Singleton
class PasskeyCredentialRepositoryAdapter(
    private val passkeyCredentialRepository: PasskeyCredentialRepository,
    private val userRepository: UserRepository,
) : CredentialRepository {
    override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> {
        val user = userRepository.findByUsername(username) ?: return emptySet()
        val userId = user.id ?: return emptySet()
        return passkeyCredentialRepository.findByUserId(userId)
            .map { credential ->
                PublicKeyCredentialDescriptor.builder()
                    .id(YubicoByteArray.fromBase64Url(credential.credentialId))
                    .build()
            }
            .toSet()
    }

    override fun getUserHandleForUsername(username: String): Optional<YubicoByteArray> {
        val user = userRepository.findByUsername(username) ?: return Optional.empty()
        val userId = user.id ?: return Optional.empty()
        return passkeyCredentialRepository.findByUserId(userId)
            .firstOrNull()
            ?.let { Optional.of(YubicoByteArray.fromBase64Url(it.userHandle)) }
            ?: Optional.empty()
    }

    override fun getUsernameForUserHandle(userHandle: YubicoByteArray): Optional<String> {
        val credential = passkeyCredentialRepository.findByUserHandle(userHandle.base64Url).firstOrNull()
            ?: return Optional.empty()
        val user = userRepository.findById(credential.userId).orElse(null)
            ?: return Optional.empty()
        return Optional.of(user.username)
    }

    override fun lookup(
        credentialId: YubicoByteArray,
        userHandle: YubicoByteArray,
    ): Optional<RegisteredCredential> {
        return passkeyCredentialRepository
            .findByCredentialIdAndUserHandle(credentialId.base64Url, userHandle.base64Url)
            ?.toRegisteredCredential()
            ?.let { Optional.of(it) }
            ?: Optional.empty()
    }

    override fun lookupAll(credentialId: YubicoByteArray): Set<RegisteredCredential> {
        return passkeyCredentialRepository.findByCredentialId(credentialId.base64Url)
            ?.toRegisteredCredential()
            ?.let { setOf(it) }
            ?: emptySet()
    }
}

private fun PasskeyCredential.toRegisteredCredential(): RegisteredCredential = RegisteredCredential.builder()
    .credentialId(YubicoByteArray.fromBase64Url(credentialId))
    .userHandle(YubicoByteArray.fromBase64Url(userHandle))
    .publicKeyCose(YubicoByteArray.fromBase64Url(publicKeyCose))
    .signatureCount(signatureCount)
    .backupEligible(backupEligible)
    .backupState(backedUp)
    .build()
