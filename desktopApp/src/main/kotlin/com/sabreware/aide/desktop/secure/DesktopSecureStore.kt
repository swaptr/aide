package com.sabreware.aide.desktop.secure

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.sabreware.aide.core.domain.secure.SecureStore
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.desktop.storage.DesktopAppDirs
import java.io.File
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop [SecureStore] — the JVM peer of the android `EncryptedPreferences` + `TinkAead`. Tink-JVM AEAD
 * (AES256-GCM) with the key name as associated data (so a ciphertext can't be migrated between keys), over
 * a plain `.properties` file of base64 ciphertexts in the host data dir. The AEAD keyset itself is persisted
 * (cleartext JSON — dev backstop; a pre-release desktop build has no OS keystore wired) beside it.
 *
 * Reactive: an in-mem `StateFlow` of the ciphertext map drives [observe]; [put]/[remove] update both the
 * flow and the file under a mutex. Decrypt failures resolve to null (UI falls back to "re-enter API key").
 */
class DesktopSecureStore(dirs: DesktopAppDirs) : SecureStore {

    private val secretsFile = File(dirs.data, "provider_secrets.properties")
    private val keysetFile = File(dirs.data, "aide_keyset.json")

    private val fileLock = Mutex()

    // key -> base64 ciphertext. Seeded from disk; kept in sync on every put/remove.
    private val state = MutableStateFlow(loadCiphertexts())

    // Lazily built AEAD (register + keyset read touch statics/disk — avoid at DI-graph build time).
    @Volatile
    private var cachedAead: Aead? = null

    // Ciphertext compared before decrypting: a write to one secret does not re-decrypt every observed key.
    override fun observe(key: String): Flow<String?> =
        state
            .map { it[key] }
            .distinctUntilChanged()
            .map { ct -> ct?.let { decrypt(key, it) } }

    override suspend fun put(key: String, value: String) {
        val encoded = encrypt(key, value)
        fileLock.withLock {
            state.update { it + (key to encoded) }
            persist(state.value)
        }
    }

    override suspend fun write(changes: Map<String, String?>) {
        val encoded = changes.mapValues { (key, value) -> value?.let { encrypt(key, it) } }
        fileLock.withLock {
            state.update { current ->
                encoded.entries.fold(current) { acc, (key, ct) -> if (ct == null) acc - key else acc + (key to ct) }
            }
            persist(state.value)
        }
    }

    override suspend fun remove(key: String) {
        fileLock.withLock {
            state.update { it - key }
            persist(state.value)
        }
    }

    private fun encrypt(key: String, plaintext: String): String {
        val ct = aead().encrypt(plaintext.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ct)
    }

    private fun decrypt(key: String, base64Ct: String): String? = runCatching {
        val ct = Base64.getDecoder().decode(base64Ct)
        String(aead().decrypt(ct, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }.getOrNull()

    private fun aead(): Aead {
        cachedAead?.let { return it }
        synchronized(this) {
            cachedAead?.let { return it }
            AeadConfig.register()
            val handle = loadOrCreateKeyset()
            val a = handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            cachedAead = a
            return a
        }
    }

    private fun loadOrCreateKeyset(): KeysetHandle {
        if (keysetFile.exists()) {
            runCatching {
                return TinkJsonProtoKeysetFormat.parseKeyset(keysetFile.readText(), InsecureSecretKeyAccess.get())
            }.onFailure { AideLog.w(TAG, "keyset parse failed; regenerating: ${it.message}") }
        }
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        runCatching {
            keysetFile.writeText(TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get()))
        }.onFailure { AideLog.w(TAG, "keyset write failed: ${it.message}") }
        return handle
    }

    private fun loadCiphertexts(): Map<String, String> {
        if (!secretsFile.exists()) return emptyMap()
        return runCatching {
            val props = Properties().apply { secretsFile.inputStream().use { load(it) } }
            props.entries.associate { (k, v) -> k.toString() to v.toString() }
        }.getOrElse {
            AideLog.w(TAG, "secrets read failed; starting empty: ${it.message}")
            emptyMap()
        }
    }

    private fun persist(map: Map<String, String>) {
        runCatching {
            val props = Properties().apply { map.forEach { (k, v) -> setProperty(k, v) } }
            secretsFile.outputStream().use { props.store(it, "aide provider secrets (encrypted)") }
        }.onFailure { AideLog.w(TAG, "secrets write failed: ${it.message}") }
    }

    private companion object { const val TAG = "DesktopSecureStore" }
}
