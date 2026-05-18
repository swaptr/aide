package com.swaptr.aide.data.secure

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Lazy: AeadConfig.register touches statics and AndroidKeysetManager does a sync keystore
// read — eager init at Hilt-graph build would block app startup.
@Singleton
class TinkAead @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cached: Aead? = null

    fun aead(): Aead {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            AeadConfig.register()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            val a = handle.getPrimitive(Aead::class.java)
            cached = a
            return a
        }
    }

    companion object {
        private const val PREF_FILE = "aide_tink_keyset"
        private const val KEYSET_NAME = "aide_keyset"
        private const val MASTER_KEY_URI = "android-keystore://aide_master_key"
    }
}
