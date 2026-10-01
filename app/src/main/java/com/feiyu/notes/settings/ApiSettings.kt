package com.feiyu.notes.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.feiyu.notes.ai.AiConfig
import com.feiyu.notes.ai.AiDefaults
import com.feiyu.notes.ai.AiModel
import com.feiyu.notes.ai.AiError
import com.feiyu.notes.ai.DeepSeekClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key (encrypted with an Android Keystore AES-GCM key) and model name in private prefs.
 * The generator and connectivity check use decrypted keys; screens never receive stored key text.
 */
class ApiSettings(context: Context, name: String = "api_settings", private val alias: String = ALIAS) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val _models = MutableStateFlow(runCatching {
        Json.decodeFromString<List<AiModel>>(prefs.getString("models", "[]")!!)
    }.getOrDefault(emptyList()))
    val models = _models.asStateFlow()

    suspend fun refreshModels(typedKey: String? = null): List<AiModel> {
        val config = withContext(Dispatchers.IO) {
            typedKey?.trim()?.takeIf { it.isNotBlank() }?.let { AiConfig(it) } ?: load() ?: throw AiError.MissingKey()
        }
        val result = DeepSeekClient(config).listModels()
        prefs.edit().putString("models", Json.encodeToString(result)).apply()
        _models.value = result
        return result
    }

    fun effort(): String = prefs.getString("effort", null)?.takeIf { it.isNotBlank() } ?: AiDefaults.EFFORT

    fun load(): AiConfig? {
        val key = decrypt(prefs.getString(KEY_CIPHER, null) ?: return null) ?: return null
        return AiConfig(apiKey = key, model = model(), effort = effort())
    }

    fun model(): String = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: AiDefaults.MODEL

    fun hasKey(): Boolean = load() != null

    /** A null [apiKey] keeps the stored key; a blank one clears it. */
    fun save(apiKey: String?, model: String, effort: String = effort()) {
        val editor = prefs.edit().putString(KEY_MODEL, model.trim()).putString("effort", effort.trim().ifBlank { AiDefaults.EFFORT })
        when {
            apiKey == null -> Unit
            apiKey.isBlank() -> editor.remove(KEY_CIPHER)
            else -> editor.putString(KEY_CIPHER, encrypt(apiKey.trim()))
        }
        check(editor.commit()) { "Could not save API settings" }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey(create = true)) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sealed)
    }

    /** Returns null if the Keystore key was lost (e.g. restored backup); the user re-enters the key. */
    private fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.getDecoder().decode(stored)
        require(sealed.size >= IV_BYTES + 16)
        val key = secretKey(create = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
