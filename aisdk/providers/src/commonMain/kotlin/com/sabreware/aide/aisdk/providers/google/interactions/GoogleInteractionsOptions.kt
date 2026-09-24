package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.google.boolOrNull
import com.sabreware.aide.aisdk.providers.google.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The provider id this model reports; its metadata and options stay under [GOOGLE_PROVIDER_ID]. */
public const val GOOGLE_INTERACTIONS_PROVIDER_ID: String = "$GOOGLE_PROVIDER_ID.interactions"

/**
 * The per-block signature, and the id of the interaction a part came from.
 *
 * Both ride `providerMetadata.google` on every output part and are read back from
 * `providerOptions.google` on the replayed part. The signature is what lets the API accept a `thought`
 * or `function_call` step it issued; the interaction id is what lets the converter DROP that step from
 * a `previous_interaction_id` call, because the server already holds it.
 */
public const val GOOGLE_INTERACTIONS_SIGNATURE_KEY: String = "signature"

public const val GOOGLE_INTERACTIONS_ID_KEY: String = "interactionId"

/** The id of an agentic-video `processing_call` step, on a `google.processing_call` custom part. */
public const val GOOGLE_INTERACTIONS_PROCESSING_ID_KEY: String = "processingId"

/** The processing call a `google.processing_result` custom part answers. */
public const val GOOGLE_INTERACTIONS_PROCESSING_CALL_ID_KEY: String = "processingCallId"

/**
 * What an Interactions call is addressed to: a model, or one of Google's server-side agents.
 *
 * The request carries `model` OR `agent`, never both, and the two branches accept different
 * configuration — an agent takes `agent_config` and an `environment` and refuses `generation_config`
 * outright. [Agent] and [ManagedAgent] are wire-identical; the reference keeps them apart only so its
 * type system can allow-list the four preset agent names while leaving user-created agents open.
 */
public sealed interface GoogleInteractionsTarget {

    /** A Gemini model id, e.g. `gemini-2.5-flash`. */
    public data class Model(val modelId: String) : GoogleInteractionsTarget

    /** One of Google's preset agents — see [GoogleInteractionsAgents]. */
    public data class Agent(val name: String) : GoogleInteractionsTarget

    /** An agent the caller created through the `/agents` endpoint, addressed by the name it gave. */
    public data class ManagedAgent(val name: String) : GoogleInteractionsTarget
}

/** The preset agent names the reference allow-lists; a newer one is just a string. */
public object GoogleInteractionsAgents {
    public const val DEEP_RESEARCH_PRO_PREVIEW_12_2025: String = "deep-research-pro-preview-12-2025"
    public const val DEEP_RESEARCH_PREVIEW_04_2026: String = "deep-research-preview-04-2026"
    public const val DEEP_RESEARCH_MAX_PREVIEW_04_2026: String = "deep-research-max-preview-04-2026"
    public const val ANTIGRAVITY_PREVIEW_05_2026: String = "antigravity-preview-05-2026"
}

/**
 * The call's `providerOptions["google"]`, as the Interactions surface reads it.
 *
 * Read from the same namespace the classic model reads — a caller files Gemini options under `google`
 * whichever surface serves them — but the keys are this surface's own: stateful chaining, the agent
 * configuration, the polymorphic `responseFormat` list. A field is read where it changes the request's
 * shape; the blobs (`agentConfig`, `environment`, the `responseFormat` entries) are kept raw and
 * translated at the one place that knows their wire spelling.
 */
internal class GoogleInteractionsOptions private constructor(private val raw: JsonObject) {

    /** The server-held interaction to continue; the converter drops the turns it already holds. */
    val previousInteractionId: String? = raw.stringOrNull("previousInteractionId")

    /** False for a fully stateless call: no record on the server, no `id` in the response. */
    val store: Boolean? = raw.boolOrNull("store")

    /** `{type: "dynamic"}` or `{type: "deep-research", thinkingSummaries?, visualization?, collaborativePlanning?}`. */
    val agentConfig: JsonObject? = raw["agentConfig"] as? JsonObject

    /** `minimal` | `low` | `medium` | `high`; wins over the neutral reasoning effort. */
    val thinkingLevel: String? = raw.stringOrNull("thinkingLevel")

    /** `auto` | `none`. */
    val thinkingSummaries: String? = raw.stringOrNull("thinkingSummaries")

    /** Output-format entries appended to `response_format` after the call-level JSON entry. */
    val responseFormat: List<JsonObject>? = (raw["responseFormat"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.takeIf { it.isNotEmpty() }

    /** The deprecated image shorthand; translated into a `response_format` image entry with a warning. */
    val imageConfig: JsonObject? = raw["imageConfig"] as? JsonObject

    /** `low` | `medium` | `high` | `ultra_high`, stamped on every image and video input block. */
    val mediaResolution: String? = raw.stringOrNull("mediaResolution")

    val responseModalities: List<String>? = (raw["responseModalities"] as? JsonArray)
        ?.mapNotNull { it.stringOrNull() }
        ?.takeIf { it.isNotEmpty() }

    val serviceTier: String? = raw.stringOrNull("serviceTier")

    /** An alternative to a system message; the message wins when both are set. */
    val systemInstruction: String? = raw.stringOrNull("systemInstruction")

    /** How long to poll a background interaction before giving up. Defaults to thirty minutes. */
    val pollingTimeoutMs: Long? = (raw["pollingTimeoutMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    /** Run server-side and return at once; the result is polled or streamed from `GET /interactions/{id}`. */
    val background: Boolean? = raw.boolOrNull("background")

    /**
     * The agent sandbox: `"remote"` for a fresh one, an environment id to fork one, or the object form
     * with `sources` and `network`. Agent calls only; a model call warns and drops it.
     */
    val environment: JsonElement? = raw["environment"]?.let { value ->
        when {
            value is JsonObject -> value
            value is JsonPrimitive && value.isString -> value
            else -> null
        }
    }

    companion object {
        fun of(options: CallOptions): GoogleInteractionsOptions =
            GoogleInteractionsOptions(options.providerOptions?.get(GOOGLE_PROVIDER_ID) ?: JsonObject(emptyMap()))
    }
}
