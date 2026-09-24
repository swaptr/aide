package com.sabreware.aide.app.data.secure

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sabreware.aide.core.domain.secure.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.providerSecretsDataStore by preferencesDataStore(name = "provider_secrets")

// AEAD with key name as associated data so ciphertexts can't be migrated between keys.
// Decrypt failures return null (UI falls back to "re-enter API key" without crashing).
class EncryptedPreferences(
    private val context: Context,
    private val tink: TinkAead,
) : com.sabreware.aide.core.domain.secure.SecureStore {

    // Every write to the store re-emits `data` for every key. Compare the CIPHERTEXT first, so a write to
    // one secret neither re-decrypts every other observed key nor re-triggers their consumers (a provider
    // treats a config emission as a change and refetches its catalog); decrypt off the main thread.
    override fun observe(key: String): Flow<String?> {
        val pkey = stringPreferencesKey(key)
        return context.providerSecretsDataStore.data
            .map { prefs -> prefs[pkey] }
            .distinctUntilChanged()
            .map { ct -> ct?.let { decrypt(key, it) } }
            .flowOn(Dispatchers.Default)
    }

    override suspend fun write(changes: Map<String, String?>) {
        val encoded = changes.mapValues { (key, value) -> value?.let { encrypt(key, it) } }
        context.providerSecretsDataStore.edit { prefs ->
            encoded.forEach { (key, ct) ->
                val pkey = stringPreferencesKey(key)
                if (ct == null) prefs.remove(pkey) else prefs[pkey] = ct
            }
        }
    }

    override suspend fun put(key: String, value: String) {
        val encoded = encrypt(key, value)
        val pkey = stringPreferencesKey(key)
        context.providerSecretsDataStore.edit { it[pkey] = encoded }
    }

    override suspend fun remove(key: String) {
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
