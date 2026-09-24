package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecret
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConnection
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeToolDefinition
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The bidi service an EPHEMERAL token may open. Constrained: the token carries the session's setup, so
 * the socket accepts only the model and configuration the token was minted for.
 */
private const val REALTIME_BIDI_PATH: String =
    "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"

/** Google's own default for how long a token may open a session; sent explicitly so the window is ours. */
private const val DEFAULT_OPEN_WINDOW_SECONDS: Int = 60

/** How much longer than the open window the token itself lives — room for the opened session to run. */
private const val TOKEN_LIFETIME_PADDING_MS: Long = 30L * 60 * 1000

private const val MS_PER_SECOND: Long = 1000

/** A model by its resource path. A caller may already have spelled it that way. */
internal fun googleModelPath(modelId: String): String = if ('/' in modelId) modelId else "models/$modelId"

/**
 * The base URL with its REST version segment — `/v1beta`, `/v1alpha` — removed.
 *
 * Live API paths carry their own version, so leaving it on produces `/v1beta/ws/…v1alpha…`, which 404s
 * at the upgrade: a socket that never opens, with nothing in the handshake to say why. The same rule the
 * translation socket applies.
 */
internal fun googleRealtimeRoot(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    val version = listOf("/v1beta", "/v1alpha").firstOrNull { trimmed.endsWith(it) }
    return (version?.let { trimmed.removeSuffix(it) } ?: trimmed).trimEnd('/')
}

/**
 * Gemini Live, as a realtime model.
 *
 * The credential story is Google's ephemeral-token flow. [doCreateClientSecret] runs where the real API
 * key lives and mints a token from `v1alpha/auth_tokens`, with the session's setup BAKED IN — the
 * constrained bidi endpoint only opens a session matching the setup the token was minted for, which is
 * why [RealtimeClientSecretOptions.sessionConfig] matters more here than on vendors that accept a
 * `session.update` afterwards. The socket is then opened by whatever holds the microphone, with the token
 * in the URL's `access_token` query parameter.
 *
 * Two token times ride the request. `newSessionExpireTime` is the window in which the token may OPEN a
 * session, which is the number a caller actually cares about, so `expiresAfterSeconds` maps to it.
 * `expireTime` is the token's overall lifetime and must be at least as long, so it is padded past the
 * open window to leave the opened session room to run. `uses: 0` lifts the per-token session limit —
 * the default of one breaks a reconnect within the session.
 *
 * **Frame translation is stateful, and this is the one model in the port where it is.** Gemini's frames
 * carry no response or item ids at all, so the neutral events' ids are synthesized from a turn counter —
 * the reference makes the same choice for the same reason. The contract's "no session state" holds for
 * every vendor that numbers its own items; for this one, construct ONE model per socket and do not share
 * it across sessions. See [GoogleRealtimeEventMapper].
 */
internal class GoogleRealtimeModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    private val apiKey: String,
    /** Sent on the token request. The key itself travels in the query string, as Google's docs show. */
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Epoch millis, injected so a test can pin the token times. */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RealtimeModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    private val mapper = GoogleRealtimeEventMapper()

    override suspend fun doCreateClientSecret(options: RealtimeClientSecretOptions): RealtimeClientSecret {
        if (apiKey.isBlank()) {
            throw InvalidArgumentError(
                message = "A Google API key is required for realtime token creation.",
                argument = "apiKey",
            )
        }
        val nowMs = now()
        val openWindowMs = (options.expiresAfterSeconds ?: DEFAULT_OPEN_WINDOW_SECONDS) * MS_PER_SECOND
        val body = buildJsonObject {
            put("uses", 0)
            put("expireTime", Instant.fromEpochMilliseconds(nowMs + openWindowMs + TOKEN_LIFETIME_PADDING_MS).toString())
            put("newSessionExpireTime", Instant.fromEpochMilliseconds(nowMs + openWindowMs).toString())
            put("bidiGenerateContentSetup", buildGoogleSessionConfig(options.sessionConfig, modelId))
        }

        val root = googleRealtimeRoot(baseUrl)
        val result = http.postJson(
            url = "$root/v1alpha/auth_tokens?key=" + apiKey.encodeURLParameter(),
            body = body,
            headers = extraHeaders,
        )
        val response = result.value as? JsonObject
        val token = response?.optString("name")
            ?: throw InvalidResponseDataError(message = "Google returned no auth token name.", data = result.value)

        return RealtimeClientSecret(
            token = token,
            url = webSocketUrl(root) + "/ws/" + REALTIME_BIDI_PATH,
            expiresAt = response.optString("expireTime")?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() },
        )
    }

    /** The token goes in the query string: Google's constrained endpoint reads `access_token` from it. */
    override fun webSocketConfig(token: String, url: String): RealtimeConnection =
        RealtimeConnection(url = "$url?access_token=" + token.encodeURLParameter())

    override fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> = mapper.parseServerEvent(raw)

    override fun serializeClientEvent(event: RealtimeClientEvent): JsonElement =
        mapper.serializeClientEvent(event, modelId)

    override fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement =
        buildGoogleSessionConfig(config, modelId)
}

/**
 * The Live API's setup frame — also the `bidiGenerateContentSetup` baked into an ephemeral token.
 *
 * `responseModalities` defaults to `["AUDIO"]` rather than being omitted: Google's default is text, and
 * a realtime session whose model answers silently is the wrong default for a voice contract.
 *
 * The provider options are read in two ways, and both are the reference's. Every key OTHER than the
 * `google` namespace is spread onto the setup as a raw Live API field — a caller writing
 * `sessionResumption` or a full `generationConfig` reaches the wire without this port naming the field.
 * Under `google`, `translationConfig` (Gemini Live Translation's target language) and `thinkingConfig`
 * are merged INTO `generationConfig`, including one the caller supplied raw, because that is where
 * Google reads them; `defaultToolBehavior` is stamped as `behavior` on every function declaration.
 *
 * A background-reasoning Live model (`gemini-3.8-live-extended-thinking`) requires exactly one of
 * `thinkingLevel` / `thinkingBudget` in its setup, so a session opened on one with no thinking config
 * is sent `thinkingLevel: "low"` — the lowest-latency level — rather than refused at the handshake.
 * Every other Live model rejects `thinkingConfig`, so the default is sent to those models and no other.
 */
internal fun buildGoogleSessionConfig(config: RealtimeSessionConfig?, modelId: String): JsonObject {
    val googleOptions = config?.providerOptions?.get(GOOGLE_PROVIDER_ID)
    val defaultToolBehavior = googleOptions?.optString("defaultToolBehavior")
    val generationConfig = buildJsonObject {
        putJsonArray("responseModalities") {
            (config?.outputModalities?.map { it.name.uppercase() } ?: listOf("AUDIO")).forEach { add(it) }
        }
        config?.voice?.let { voice ->
            putJsonObject("speechConfig") {
                putJsonObject("voiceConfig") {
                    putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                }
            }
        }
    }

    val setup = LinkedHashMap<String, JsonElement>()
    setup["model"] = JsonPrimitive(googleModelPath(modelId))
    setup["generationConfig"] = generationConfig
    config?.instructions?.let { instructions ->
        setup["systemInstruction"] = buildJsonObject {
            putJsonArray("parts") { add(buildJsonObject { put("text", instructions) }) }
        }
    }
    config?.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
        setup["tools"] = buildJsonArray {
            add(
                buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        tools.forEach { add(it.toDeclaration(defaultToolBehavior)) }
                    }
                },
            )
        }
    }
    // An empty object is what ENABLES transcription; omitting the key turns it off.
    if (config?.inputAudioTranscription != null) setup["inputAudioTranscription"] = JsonObject(emptyMap())
    if (config?.outputAudioTranscription != null) setup["outputAudioTranscription"] = JsonObject(emptyMap())

    config?.providerOptions?.let { providerOptions ->
        providerOptions.forEach { (key, value) -> if (key != GOOGLE_PROVIDER_ID) setup[key] = value }
        googleOptions?.get("translationConfig")?.takeIf { it !is JsonNull }?.let {
            setup.mergeIntoGenerationConfig("translationConfig", it, generationConfig)
        }
    }
    // Merged LAST, so the default survives a raw `generationConfig` the caller spread onto the setup.
    val thinkingConfig = googleOptions?.optObject("thinkingConfig")
        ?: buildJsonObject { put("thinkingLevel", "low") }.takeIf { isThinkingLiveModel(modelId) }
    thinkingConfig?.let { setup.mergeIntoGenerationConfig("thinkingConfig", it, generationConfig) }
    return JsonObject(setup)
}

/** One field into the setup's `generationConfig` — the built one, or one the caller supplied raw. */
private fun MutableMap<String, JsonElement>.mergeIntoGenerationConfig(key: String, value: JsonElement, built: JsonObject) {
    val target = this["generationConfig"] as? JsonObject ?: built
    this["generationConfig"] = JsonObject(target + (key to value))
}

/** Live models that reason in the background: `gemini-<major>.<minor>-live…thinking`, path prefix aside. */
private val THINKING_LIVE_MODEL = Regex("^gemini-\\d+\\.\\d+-live\\b.*thinking")

private fun isThinkingLiveModel(modelId: String): Boolean =
    THINKING_LIVE_MODEL.containsMatchIn(modelId.substringAfterLast('/').lowercase())

/**
 * One function declaration for the setup frame. The schema goes out verbatim under `parametersJsonSchema`,
 * as on the chat request: the Live `FunctionDeclaration` reads JSON Schema there, `$ref` and all.
 */
private fun RealtimeToolDefinition.toDeclaration(behavior: String?): JsonObject = buildJsonObject {
    put("name", name)
    description?.let { put("description", it) }
    put("parametersJsonSchema", parameters)
    behavior?.let { put("behavior", it) }
}
