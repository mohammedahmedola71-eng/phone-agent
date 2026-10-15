package ai.vox.app.data

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
 * Stores the gateway app token encrypted with a non-exportable Android Keystore key (AES-256-GCM).
 * This is the *app* token for your own gateway - AI provider keys never exist on the device.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("vox_secrets", Context.MODE_PRIVATE)

    fun getToken(): String {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return ""
        return runCatching { decrypt(stored) }.getOrDefault("")
    }

    fun setToken(token: String) {
        if (token.isBlank()) { prefs.edit().remove(KEY_TOKEN).apply(); return }
        prefs.edit().putString(KEY_TOKEN, encrypt(token.trim())).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vox_gateway_token"
        const val KEY_TOKEN = "gateway_token"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
