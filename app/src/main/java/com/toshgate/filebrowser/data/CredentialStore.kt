package com.toshgate.filebrowser.data

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val username: String, val password: String)

/**
 * Guarda o utilizador e a palavra-passe para login automático.
 *
 * A palavra-passe é cifrada com AES-256-GCM usando uma chave do Android Keystore: a chave
 * vive no hardware seguro do telemóvel, não pode ser exportada e não vai em backups
 * (além disso o manifest tem allowBackup="false"). O que fica nas SharedPreferences é só
 * o texto cifrado e o IV.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    /** Último utilizador, para pré-preencher o login mesmo sem palavra-passe guardada. */
    val username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""

    val hasPassword: Boolean
        get() = prefs.contains(KEY_CIPHERTEXT)

    fun save(username: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    /** Só o utilizador (o utilizador escolheu não manter a sessão). */
    fun saveUsernameOnly(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    /** null se não houver palavra-passe guardada ou se já não for possível decifrá-la. */
    fun load(): Credentials? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val password = String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
            Credentials(username, password)
        } catch (e: Exception) {
            // Chave apagada ou invalidada (ex.: dados restaurados noutro telemóvel): esquecer.
            forgetPassword()
            null
        }
    }

    /** Esquece a palavra-passe mas mantém o utilizador para pré-preencher o formulário. */
    fun forgetPassword() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "filebrowser_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_USERNAME = "username"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "password"
    }
}
