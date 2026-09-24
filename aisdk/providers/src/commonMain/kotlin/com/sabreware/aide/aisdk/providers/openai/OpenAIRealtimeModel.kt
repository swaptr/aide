package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecret
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConnection
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Realtime API — the GA wire (`session.type: "realtime"`, `audio.input` / `audio.output`,
 * `response.output_text.*`), not the 2024 beta it replaced.
 *
 * OpenAI is the vendor [RealtimeModel]'s shape was drawn around. [doCreateClientSecret] runs where the
 * API key lives and mints an `ek_…` token; the socket is opened somewhere else — a browser, a phone's
 * audio layer — and authenticates by **subprotocol** (`realtime`, `openai-insecure-api-key.<token>`),
 * because the browser WebSocket API cannot set a request header and that field is the only one such a
 * client can populate. `ProviderSocket` joins the list into one `Sec-WebSocket-Protocol` header for
 * exactly this reason.
 *
 * Every session payload carries `type` and `model`, whether it is baked into the token request or sent
 * later as `session.update`: the endpoint serves transcription sessions on the same path and reads the
 * type to tell them apart, and a `session.update` without the model is applied to whatever model the
 * socket URL named — which is the one place the caller's choice can silently go missing, so it is
 * stated on every frame that could carry it.
 *
 * Frame translation lives in `OpenAIRealtimeEventMapper.kt`, mirroring the reference's split.
 */
public class OpenAIRealtimeModel(
    override val modelId: String,
    http: ProviderHttp,
    baseUrl: String = OpenAIResponsesLanguageModel.DEFAULT_BASE_URL,
    private val headers: Map<String, String> = emptyMap(),
) : RealtimeModel {

    /**
     * The direct form: a client and a key, for a caller that is not going through `OpenAIProvider`.
     *
     * [extraHeaders] is where `OpenAI-Organization` / `OpenAI-Project` go, and anything a gateway in
     * front of OpenAI requires; they reach the secret request, never the socket.
     */
    public constructor(
        modelId: String,
        client: HttpClient,
        apiKey: String,
        baseUrl: String = OpenAIResponsesLanguageModel.DEFAULT_BASE_URL,
        extraHeaders: Map<String, String> = emptyMap(),
    ) : this(
        modelId = modelId,
        http = ProviderHttp(client),
        baseUrl = baseUrl,
        headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            putAll(extraHeaders)
        },
    )

    override val provider: String = OPENAI_PROVIDER_ID

    private val http = http.withErrorStructure(OpenAIErrorStructure)

    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun doCreateClientSecret(
        options: RealtimeClientSecretOptions,
    ): RealtimeClientSecret {
        val body = buildJsonObject {
            // The session is part of the token: what is configured here is what the socket opens with,
            // so a caller that hands over a config gets it baked in and one that does not still gets the
            // type and model the endpoint needs to know which kind of session it is minting for.
            put(
                "session",
                options.sessionConfig?.let { buildOpenAISessionConfig(it, modelId) }
                    ?: buildJsonObject {
                        put("type", "realtime")
                        put("model", modelId)
                    },
            )
            options.expiresAfterSeconds?.let { seconds ->
                putJsonObject("expires_after") {
                    // `created_at` is the only anchor the endpoint accepts, and the reference found the
                    // request rejected without it. The docs now list it as the default; sending it costs
                    // nothing and stays correct under both readings.
                    put("anchor", "created_at")
                    put("seconds", seconds)
                }
            }
        }
        val result = http.postJson("$baseUrl/realtime/client_secrets", body, headers)
        val response = result.value as? JsonObject
        val token = response?.optString("value")
            ?: throw NoContentGeneratedError("OpenAI returned no client secret value.")

        return RealtimeClientSecret(
            token = token,
            // The socket lives beside the REST base at `/realtime`. Derived from the base rather than
            // hardcoded to `wss://<host>/v1/realtime` as the reference does, so a proxy or a gateway
            // that mounts OpenAI under a path prefix still resolves; on the default base the bytes are
            // identical.
            url = webSocketUrl("$baseUrl/realtime") + "?model=" + modelId.encodeURLParameter(),
            expiresAt = (response["expires_at"] as? JsonPrimitive)?.content?.toLongOrNull(),
        )
    }

    override fun webSocketConfig(token: String, url: String): RealtimeConnection = RealtimeConnection(
        url = url,
        // Authentication, not content negotiation — see SUBPROTOCOL_HEADER. The bare `realtime` entry
        // comes first because it is the one the server echoes back as the agreed protocol.
        protocols = listOf("realtime", "openai-insecure-api-key.$token"),
    )

    // One frame, one event: OpenAI never packs two things into a message, so the list is a singleton.
    override fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> =
        listOf(parseOpenAIRealtimeServerEvent(raw))

    override fun serializeClientEvent(event: RealtimeClientEvent): JsonElement =
        serializeOpenAIRealtimeClientEvent(event, modelId)

    override fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement =
        buildOpenAISessionConfig(config, modelId)
}
