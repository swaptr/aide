package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.RealtimeCapabilities
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecret
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConnection
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.RealtimeServerConnection
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeServerEventParser
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.util.webSocketUrl
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI's Live API — `wss://…/v1/live/sessions`, a CONTINUOUS conversation rather than the Realtime
 * API's turns.
 *
 * The same [RealtimeModel] contract as [OpenAIRealtimeModel], on the other side of every optional
 * member: it declares [capabilities] (continuous, server-owned socket, `session.start` opens and
 * `session.close` ends), it answers [serverWebSocketConfig] with the API key in the headers — this half
 * runs where the key is, and its answer must never reach a client — and it refuses
 * [doCreateClientSecret], because the Live API mints no short-lived credential. A session layer reads
 * the capabilities BEFORE connecting and drives the session with the continuous-conversation events
 * (`session-start`, `context-append`, mute/unmute, `session-close`); the turn-based commands are
 * refused at [serializeClientEvent] rather than silently dropped.
 *
 * The reference also offers WebRTC (`doCreateWebRTCSession`, a data-channel label); the contract here
 * has no WebRTC hook, so the model declares only the WebSocket transport it can actually open, and the
 * `client.dataChannel` startup options that exist for WebRTC are refused the way the reference refuses
 * them outside WebRTC startup. Frame translation lives in `OpenAILiveEventMapper.kt` and the startup
 * payload in `OpenAILiveSessionConfig.kt`, mirroring the reference's split.
 */
public class OpenAILiveModel(
    override val modelId: String,
    baseUrl: String = OpenAIResponsesLanguageModel.DEFAULT_BASE_URL,
    private val headers: Map<String, String> = emptyMap(),
) : RealtimeModel {

    /** The direct form: a key, for a caller that is not going through `OpenAIProvider`. */
    public constructor(
        modelId: String,
        apiKey: String,
        baseUrl: String = OpenAIResponsesLanguageModel.DEFAULT_BASE_URL,
        extraHeaders: Map<String, String> = emptyMap(),
    ) : this(
        modelId = modelId,
        baseUrl = baseUrl,
        headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            putAll(extraHeaders)
        },
    )

    override val provider: String = OPENAI_PROVIDER_ID

    private val baseUrl = baseUrl.trimEnd('/')

    override val capabilities: RealtimeCapabilities = RealtimeCapabilities(
        conversation = RealtimeCapabilities.Conversation.Continuous,
        transports = listOf(RealtimeCapabilities.Transport.WebSocket),
        connections = listOf(RealtimeCapabilities.Connection.ServerWebSocket),
        startup = RealtimeCapabilities.Startup.SessionStart,
        finalization = RealtimeCapabilities.Finalization.SessionClose,
    )

    /** No model query parameter: the model rides in the `session.start` frame, not the URL. */
    override suspend fun serverWebSocketConfig(): RealtimeServerConnection = RealtimeServerConnection(
        url = webSocketUrl("$baseUrl/live/sessions"),
        headers = headers,
    )

    /**
     * The Live parser holds no state, so a fresh one per connection costs an allocation and nothing
     * else — an anonymous object rather than a lambda, because a non-capturing lambda is one shared
     * instance and the contract promises a parser per connection.
     */
    override fun createServerEventParser(): RealtimeServerEventParser = object : RealtimeServerEventParser {
        override fun parse(raw: JsonElement): List<RealtimeServerEvent> = parseOpenAILiveServerEvent(raw)
    }

    override suspend fun doCreateClientSecret(options: RealtimeClientSecretOptions): RealtimeClientSecret =
        throw UnsupportedFunctionalityError(
            "Short-lived OpenAI credentials for the Live API. Use server WebSocket setup via " +
                "serverWebSocketConfig() with a server-side API key instead.",
        )

    override fun webSocketConfig(token: String, url: String): RealtimeConnection =
        throw UnsupportedFunctionalityError("browser WebSocket connections to the OpenAI Live API")

    override fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> = parseOpenAILiveServerEvent(raw)

    override fun serializeClientEvent(event: RealtimeClientEvent): JsonElement =
        serializeOpenAILiveClientEvent(event, modelId)

    override fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement =
        buildOpenAILiveSessionConfig(config, modelId)
}
