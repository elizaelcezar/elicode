package com.elicode.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores secrets (GitHub tokens, provider API keys) encrypted with a key
 * that lives in the Android Keystore. Ciphertexts go to private
 * SharedPreferences — never plaintext.
 */
class KeystoreStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("elicode_secrets", Context.MODE_PRIVATE)

    private val alias = "elicode_master_v1"

    @Synchronized
    fun put(key: String, plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$key.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("$key.ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun get(key: String): String? {
        val ivB64 = prefs.getString("$key.iv", null) ?: return null
        val ctB64 = prefs.getString("$key.ct", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            val pt = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
            String(pt, Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    fun remove(key: String) {
        prefs.edit().remove("$key.iv").remove("$key.ct").apply()
    }

    fun has(key: String): Boolean = prefs.contains("$key.ct")

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(false)
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    companion object {
        const val GITHUB_TOKEN = "github_token"
        const val OPENCODE_AUTH = "opencode_auth"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
