package com.feiyu.notes.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.feiyu.notes.ai.AiConfig
import com.feiyu.notes.ai.AiDefaults
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key (encrypted with an Android Keystore AES-GCM key) and model name in private prefs.
 * Only the generator calls [load]; screens use [model] and [hasKey] and never see the key.
 */
class ApiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)

    fun load(): AiConfig? {
        val key = decrypt(prefs.getString(KEY_CIPHER, null) ?: return null) ?: return null
        return AiConfig(apiKey = key, model = model())
    }

    fun model(): String = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: AiDefaults.MODEL

    fun hasKey(): Boolean = prefs.contains(KEY_CIPHER)

    /** A null [apiKey] keeps the stored key; a blank one clears it. */
    fun save(apiKey: String?, model: String) {
        val editor = prefs.edit().putString(KEY_MODEL, model.trim())
        when {
            apiKey == null -> Unit
            apiKey.isBlank() -> editor.remove(KEY_CIPHER)
            else -> editor.putString(KEY_CIPHER, encrypt(apiKey.trim()))
        }
        editor.apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sealed)
    }

    /** Returns null if the Keystore key was lost (e.g. restored backup); the user re-enters the key. */
    private fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_CIPHER = "api_key_cipher"
        const val KEY_MODEL = "model"
        const val ALIAS = "feiyu_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
