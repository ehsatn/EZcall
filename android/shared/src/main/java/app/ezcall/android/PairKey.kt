package app.ezcall.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class PairKey(context: Context) {
    private val preferences = context.getSharedPreferences("pair", Context.MODE_PRIVATE)
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    fun exists() = preferences.contains("secret")

    /** [slot] "next" holds a receiver's rotated key until the sender proves it switched. */
    fun load(slot: String = "secret"): ByteArray? {
        val encoded = preferences.getString(slot, null) ?: return null
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size == 60)
        val key = store.getKey(ALIAS, null) as SecretKey
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            doFinal(blob, 12, blob.size - 12)
        }
    }

    fun save(value: ByteArray, next: ByteArray? = null) {
        val changes = preferences.edit().putString("secret", encrypt(value)).remove("relayEnabled")
        if (next != null) changes.putString("next", encrypt(next))
        check(changes.commit())
    }

    private fun encrypt(value: ByteArray): String {
        require(value.size == 32)
        val key = store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }

    fun forget() {
        store.deleteEntry(ALIAS)
        check(preferences.edit().remove("secret").remove("next").remove("relayEnabled").commit())
    }

    companion object {
        private const val ALIAS = "xcall-pair-v1"
        fun random(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private const val QR_PREFIX = "xcall:v1:"

        fun parseQr(value: String): ByteArray? {
            if (!value.startsWith(QR_PREFIX) || value.length != QR_PREFIX.length + 43) return null
            val encoded = value.substring(QR_PREFIX.length)
            if (!encoded.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
            val secret = runCatching { java.util.Base64.getUrlDecoder().decode(encoded) }.getOrNull()
            return secret?.takeIf {
                it.size == 32 && java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) == encoded
            }
        }

        fun qr(value: ByteArray): String = QR_PREFIX + Base64.encodeToString(
            value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
