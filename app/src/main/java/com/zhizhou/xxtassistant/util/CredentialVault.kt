package com.zhizhou.xxtassistant.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM credential storage backed by a non-exportable Android Keystore key. */
internal object CredentialVault {
    private const val KEY_ALIAS = "xxt_account_cookie_v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_SIZE = 12
    private val aad = "com.zhizhou.xxtassistant/account-cookie/v1".toByteArray(Charsets.UTF_8)

    fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }.getOrNull()

    /** Strictly decrypts a value from the current credential store. */
    fun decrypt(value: String): String? {
        if (!value.startsWith(PREFIX)) return null
        return runCatching {
            val bytes = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** Migration-only compatibility for cookies written by older app versions. */
    fun decryptOrLegacy(value: String): String? =
        if (value.startsWith(PREFIX)) decrypt(value) else value

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }
}
