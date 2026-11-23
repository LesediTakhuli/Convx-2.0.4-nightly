/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the user's Teq/ElevenLabs secret encrypted with a key held by Android Keystore. */
object TeqVoiceCredentialStore {
    private const val STORE = "convx_teq_voice"
    private const val API_KEY = "api_key_enc"
    private const val VOICE_ID = "voice_id_enc"
    private const val KEY_ALIAS = "convx.teq.voice.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    data class Credentials(val apiKey: String, val voiceId: String)

    fun isConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return prefs.contains(API_KEY) && prefs.contains(VOICE_ID)
    }

    fun save(context: Context, apiKey: String, voiceId: String) {
        require(apiKey.isNotBlank()) { "Enter an ElevenLabs API key for Teq." }
        require(voiceId.isNotBlank()) { "Enter the ElevenLabs voice ID for Teq." }
        val editor = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        editor.putString(API_KEY, encrypt(apiKey.trim()))
        editor.putString(VOICE_ID, encrypt(voiceId.trim()))
        check(editor.commit()) { "Could not save Teq voice settings." }
    }

    fun read(context: Context): Credentials? = runCatching {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val api = prefs.getString(API_KEY, null) ?: return null
        val voice = prefs.getString(VOICE_ID, null) ?: return null
        Credentials(decrypt(api), decrypt(voice))
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        require(raw.size > 12) { "Stored Teq credential is invalid." }
        val iv = raw.copyOfRange(0, 12)
        val encrypted = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
