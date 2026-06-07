package com.opencode.android.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStringStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("opencode_secure", Context.MODE_PRIVATE)

    fun get(key: String): String {
        return read(key).getOrNull().orEmpty()
    }

    fun read(key: String): Result<String?> = runCatching {
        val encoded = prefs.getString(key, null) ?: return@runCatching null
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) throw IllegalStateException("Invalid encrypted value")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val payload = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    fun put(key: String, value: String): Boolean {
        if (value.isBlank()) {
            remove(key)
            return true
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(payload, Base64.NO_WRAP)
        return prefs.edit().putString(key, encoded).commit()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        synchronized(keyLock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            return generator.generateKey()
        }
    }

    private companion object {
        val keyLock = Any()
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opencode_profiles"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
