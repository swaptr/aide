package com.swaptr.aide.data.secure

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.providerSecretsDataStore by preferencesDataStore(name = "provider_secrets")

// AEAD with key name as associated data so ciphertexts can't be migrated between keys.
// Decrypt failures return null (UI falls back to "re-enter API key" without crashing).
@Singleton
class EncryptedPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tink: TinkAead,
) {

    fun observe(key: String): Flow<String?> {
        val pkey = stringPreferencesKey(key)
        return context.providerSecretsDataStore.data.map { prefs ->
            prefs[pkey]?.let { decrypt(key, it) }
        }
    }

    suspend fun put(key: String, value: String) {
        val encoded = encrypt(key, value)
        val pkey = stringPreferencesKey(key)
        context.providerSecretsDataStore.edit { it[pkey] = encoded }
    }

    suspend fun remove(key: String) {
        val pkey = stringPreferencesKey(key)
        context.providerSecretsDataStore.edit { it.remove(pkey) }
    }

    private fun encrypt(key: String, plaintext: String): String {
        val ct = tink.aead().encrypt(plaintext.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(key: String, base64Ct: String): String? = runCatching {
        val ct = Base64.decode(base64Ct, Base64.NO_WRAP)
        String(
            tink.aead().decrypt(ct, key.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8,
        )
    }.getOrNull()
}
