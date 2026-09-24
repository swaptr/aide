package com.sabreware.aide.core.domain.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Locks the persisted-config serialization (the repo stores this blob in the encrypted prefs). */
class McpServerConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(McpServerConfig.serializer())

    @Test
    fun roundTrips_acrossAllAuthVariants() {
        val list = listOf(
            McpServerConfig(url = "https://a.test/mcp", enabled = true), // McpAuth.None default
            McpServerConfig(
                url = "https://b.test/mcp",
                auth = McpAuth.Header(name = "X-Api-Key", value = "secret"),
                enabled = false,
            ),
            McpServerConfig(
                url = "https://c.test/mcp",
                auth = McpAuth.OAuth(
                    accessToken = "at",
                    refreshToken = "rt",
                    expiresAtEpochSec = 123L,
                    tokenEndpoint = "https://as.test/token",
                    clientId = "cid",
                    scope = "files:read",
                    resource = "https://c.test/mcp",
                ),
            ),
        )
        val blob = json.encodeToString(serializer, list)
        assertEquals(list, json.decodeFromString(serializer, blob))
    }

    @Test
    fun toleratesUnknownKeys_fromAFutureSchema() {
        val blob = """[{"url":"https://a.test/mcp","enabled":true,"futureField":42}]"""
        val back = json.decodeFromString(serializer, blob)
        assertEquals("https://a.test/mcp", back.single().url)
        assertEquals(McpAuth.None, back.single().auth)
    }
}
