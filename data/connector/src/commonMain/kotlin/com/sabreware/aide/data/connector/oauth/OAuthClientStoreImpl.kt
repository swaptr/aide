package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.OAuthClientStore
import com.sabreware.aide.core.domain.secure.SecureStore
import kotlinx.coroutines.flow.first

/**
 * Per-(authorization-server, redirect-uri) `client_id` cache over the Tink-encrypted prefs, so Dynamic
 * Client Registration isn't repeated. OAuth tokens themselves are NOT stored here — they live inside the
 * installed `McpServerConfig.auth` (also encrypted), which reconnect/refresh read.
 */
class OAuthClientStoreImpl(
    private val secrets: SecureStore,
) : OAuthClientStore {

    override suspend fun clientId(key: String): String? = secrets.observe(prefKey(key)).first()

    override suspend fun putClientId(key: String, clientId: String) = secrets.put(prefKey(key), clientId)

    private fun prefKey(key: String) = "mcp.oauth.client.$key"
}
