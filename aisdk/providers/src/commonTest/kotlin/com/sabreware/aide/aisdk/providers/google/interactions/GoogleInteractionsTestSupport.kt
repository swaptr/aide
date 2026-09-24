package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.testing.TestResponse
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The reference's test constants, so an assertion here reads like the one it was ported from. */
internal const val INTERACTIONS_URL: String = "https://generativelanguage.googleapis.com/v1beta/interactions"
internal const val TEST_MODEL: String = "gemini-2.5-flash"
internal const val TEST_AGENT: String = GoogleInteractionsAgents.DEEP_RESEARCH_PRO_PREVIEW_12_2025

internal val TEST_PROMPT: Prompt = userPrompt("Hello, how are you?")

internal fun userPrompt(text: String): Prompt = listOf(ModelMessage.User(listOf(UserPart.Text(text))))

/**
 * A model over [server], as `GoogleProvider` would build one: the transport and the raw client share
 * the server's engine, and the poll-budget clock is pinned unless a test moves it.
 */
internal fun interactionsModel(
    server: TestServer,
    target: GoogleInteractionsTarget = GoogleInteractionsTarget.Model(TEST_MODEL),
    now: () -> Long = { TestServer.FIXED_NOW },
    reconnect: GoogleInteractionsReconnectPolicy = GoogleInteractionsReconnectPolicy(retryDelayMillis = 1),
): GoogleInteractionsLanguageModel = GoogleInteractionsLanguageModel(
    target = target,
    http = server.http(),
    client = HttpClient(server.engine()),
    headers = { mapOf("x-goog-api-key" to "test-api-key") },
    ids = IdGenerator(prefix = "test-id-", random = Random(1)),
    now = now,
    reconnect = reconnect,
)

/** A `.chunks.txt` fixture as the SSE body the reference's `prepareChunksFixtureResponse` serves. */
internal fun sseOf(chunks: List<String>): TestResponse = TestServer.sse(*chunks.map { "data: $it\n\n" }.toTypedArray())

internal fun googleMeta(vararg pairs: Pair<String, String>): ProviderMetadata =
    mapOf(GOOGLE_PROVIDER_ID to buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } })

internal fun googleOptions(builder: JsonObjectBuilder.() -> Unit): ProviderOptions =
    mapOf(GOOGLE_PROVIDER_ID to buildJsonObject(builder))

internal val WEATHER_TOOL: Tool.Function = Tool.Function(
    name = "getWeather",
    description = "Get the current weather in a location",
    inputSchema = parseJsonObject(
        """{"type":"object","properties":{"location":{"type":"string",""" +
            """"description":"The location to get the weather for"}},"required":["location"]}""",
    ),
)

internal val GOOGLE_SEARCH_TOOL: Tool.ProviderDefined = Tool.ProviderDefined(
    name = "google_search",
    id = "google.google_search",
    args = JsonObject(emptyMap()),
    providerExecuted = true,
)
