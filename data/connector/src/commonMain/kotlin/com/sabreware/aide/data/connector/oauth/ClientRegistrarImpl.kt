package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.AuthorizationServerMetadata
import com.sabreware.aide.core.domain.connector.oauth.ClientRegistrar
import com.sabreware.aide.core.domain.connector.oauth.OAuthClientStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Obtains an OAuth `client_id`: cached/pre-registered first, else Dynamic Client Registration (RFC 7591)
 * as a public native client (`token_endpoint_auth_method=none`). Returns null when neither is available —
 * the coordinator then surfaces a "needs manual registration" message. (CIMD would need a hosted client
 * metadata doc; not configured, so we fall through to DCR.)
 */
class ClientRegistrarImpl(
    private val http: HttpClient,
    private val clientStore: OAuthClientStore,
) : ClientRegistrar {

    override suspend fun clientId(asMetadata: AuthorizationServerMetadata, redirectUri: String): String? {
        // Key by redirect_uri too: a client_id registered for one redirect (e.g. a loopback port, or the
        // custom scheme) is invalid for another, so switching strategy / port must re-register.
        val key = asKey(asMetadata) + "|" + redirectUri
        clientStore.clientId(key)?.let { return it }
        val endpoint = asMetadata.registrationEndpoint ?: return null
        val id = runCatching { registerDynamic(endpoint, redirectUri) }.getOrNull() ?: return null
        clientStore.putClientId(key, id)
        return id
    }

    private suspend fun registerDynamic(endpoint: String, redirectUri: String): String? {
        val body = buildJsonObject {
            put("client_name", "Aide")
            put("application_type", "native")
            put("token_endpoint_auth_method", "none")
            putJsonArray("redirect_uris") { add(redirectUri) }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
        }
        val resp = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) return null
        return resp.body<JsonObject>()["client_id"]?.jsonPrimitive?.contentOrNull
    }

    private fun asKey(meta: AuthorizationServerMetadata): String = meta.issuer ?: meta.tokenEndpoint
}
