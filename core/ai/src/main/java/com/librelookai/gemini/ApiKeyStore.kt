package com.librelookai.gemini

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local storage for the user-supplied Gemini API key (device-local, never synced to Drive).
 *
 * The key is encrypted at rest (refactor § 7): AES-256-GCM with a key held in the Android
 * Keystore, persisted as base64(iv ‖ ciphertext) in SharedPreferences. A pre-existing
 * plaintext value migrates on first read and the plaintext pref is removed. Crypto failures
 * never lose the key: if the Keystore is unavailable, [set] degrades to the legacy plaintext
 * pref, and an undecryptable value falls back to the legacy pref (or empty). Reads are served
 * from an in-memory cache after the first decrypt — [get] is on the hot path of every
 * BYOK Gemini call.
 */
object ApiKeyStore {
    private const val PREFS_NAME = "librelookai_local"
    private const val KEY_LEGACY = "gemini_api_key"
    private const val KEY_ENCRYPTED = "gemini_api_key_enc"
    private const val KEYSTORE_ALIAS = "librelookai_api_key"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_ENCRYPTED, null)?.let { encoded ->
            runCatching { decrypt(encoded) }.getOrNull()?.let { plain ->
                cached = plain
                return plain
            }
            // Undecryptable (Keystore key lost, e.g. some backup restores) — fall through.
        }
        val legacy = prefs.getString(KEY_LEGACY, "") ?: ""
        if (legacy.isNotEmpty()) {
            set(context, legacy) // one-time migration off the plaintext pref (also caches)
            return legacy
        }
        cached = legacy
        return legacy
    }

    fun set(context: Context, key: String) {
        val trimmed = key.trim()
        cached = trimmed
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val encrypted = if (trimmed.isEmpty()) null else runCatching { encrypt(trimmed) }.getOrNull()
        when {
            trimmed.isEmpty() -> editor.remove(KEY_ENCRYPTED).remove(KEY_LEGACY)
            encrypted != null -> editor.putString(KEY_ENCRYPTED, encrypted).remove(KEY_LEGACY)
            // Keystore unavailable — keep the key usable rather than silently dropping it.
            else -> editor.putString(KEY_LEGACY, trimmed).remove(KEY_ENCRYPTED)
        }
        editor.apply()
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(GCM_TAG_BITS, bytes.copyOf(GCM_IV_BYTES)),
        )
        return String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
    }
}
