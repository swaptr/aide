package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.providers.openai.MappedOutput
import com.sabreware.aide.aisdk.providers.openai.OpenAIOutputItem
import com.sabreware.aide.aisdk.providers.openai.PreparedTools
import com.sabreware.aide.aisdk.providers.openai.toContent
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Item types the shared Responses mappers understand; anything else on this wire is Perplexity's own. */
private val OPENAI_ITEM_TYPES: Set<String> = setOf(
    "message", "reasoning", "function_call", "custom_tool_call", "web_search_call", "file_search_call",
    "code_interpreter_call", "image_generation_call", "local_shell_call", "computer_call", "mcp_call",
    "mcp_approval_request", "compaction",
)

/** The `response.reasoning.*` family: the research loop narrating itself. */
internal const val REASONING_EVENT_PREFIX: String = "response.reasoning."

internal const val OUTPUT_ITEM_DONE: String = "response.output_item.done"

/**
 * What the Agent API adds to the Responses wire, mapped from the RAW items — the shared mappers decode
 * into a typed item that has no field for `contents`, `stdout` or a `thought_signature`, so anything
 * of Perplexity's has to be read before that decode throws it away.
 *
 * Three rules, each the answer to "where did that go":
 *
 * - **Every vendor item survives whole** as a `Content.Custom` of kind `perplexity.<type>` carrying
 *   the item verbatim under `providerMetadata.perplexity`. The API reference names six item types
 *   today and will name more; a type this port has never seen is carried, not dropped.
 * - **Every URL becomes a source, once.** `search_results` and `people_search_results` carry
 *   `results[].url`, `fetch_url_results` carries `contents[].url`, `finance_results` carries
 *   `results[].sources[]`, and the streamed `response.reasoning.search_results` event carries the
 *   same results a step earlier. The same page cited by a search, a fetch and an annotation is one
 *   source, keyed by URL, the rule the Chat Completions model already applied to Perplexity's repeated
 *   `citations`.
 * - **A `thought_signature` rides with its call.** Perplexity fronts Gemini among others, and a
 *   `function_call` replayed without the signature the model issued is the failure DESIGN.md names as
 *   the reason this library exists. It is filed under `perplexity.thoughtSignature` and sent back by
 *   the request builder.
 */
internal class PerplexityOutputMapper(
    private val tools: PreparedTools,
    private val generateId: () -> String,
) {

    private val seenUrls = mutableSetOf<String>()
    private val signatures = mutableMapOf<String, String>()
    private var reasoningBlocks = 0

    fun isVendorItem(item: JsonObject): Boolean = item.optString("type") !in OPENAI_ITEM_TYPES

    /** A complete `output` array, in order, through whichever mapper each item belongs to. */
    suspend fun map(output: JsonArray?): MappedOutput {
        val content = mutableListOf<Content>()
        var hasFunctionCall = false
        output.orEmpty().forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            if (isVendorItem(item)) {
                content += itemContent(item)
                return@forEach
            }
            noteSignature(item)
            val decoded = runCatching {
                ProviderJson.decodeFromJsonElement(OpenAIOutputItem.serializer(), item)
            }.getOrNull()
            if (decoded == null) {
                content += customItem(item)
                return@forEach
            }
            val mapped = listOf(decoded).toContent(tools, generateId, PERPLEXITY_PROVIDER_ID)
            if (mapped.hasFunctionCall) hasFunctionCall = true
            content += mapped.content.mapNotNull { adjust(it) }
        }
        return MappedOutput(content, hasFunctionCall)
    }

    /** Remembers a `function_call` item's `thought_signature` for the call the shared mapper emits. */
    fun noteSignature(item: JsonObject) {
        if (item.optString("type") != "function_call") return
        val callId = item.optString("call_id") ?: return
        item.optString("thought_signature")?.let { signatures[callId] = it }
    }

    /** One of Perplexity's own items: the item itself, then each URL it carries as a source. */
    fun itemContent(item: JsonObject): List<Content> {
        val type = item.optString("type") ?: "item"
        val sources = when (type) {
            "search_results", "people_search_results" -> item.optArray("results").objects().mapNotNull { result ->
                source(
                    url = result.optString("url"),
                    title = result.optString("title"),
                    metadata = buildJsonObject {
                        put("type", type)
                        item["queries"]?.let { put("queries", it) }
                        put("result", result)
                    },
                )
            }

            "fetch_url_results" -> item.optArray("contents").objects().mapNotNull { content ->
                source(
                    url = content.optString("url"),
                    title = content.optString("title"),
                    metadata = buildJsonObject {
                        put("type", type)
                        put("content", content)
                    },
                )
            }

            "finance_results" -> item.optArray("results").objects().flatMap { result ->
                result.optArray("sources").orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .mapNotNull { url ->
                        source(
                            url = url,
                            title = null,
                            metadata = buildJsonObject {
                                put("type", type)
                                put("result", result)
                            },
                        )
                    }
            }

            else -> emptyList()
        }
        return listOf(customItem(item)) + sources
    }

    /**
     * A `response.reasoning.*` event as stream parts.
     *
     * The narration (`thought`) and what the loop is doing (`queries`, `urls`) become one reasoning
     * block per event, so a UI can show "searching for X" while it happens; the results and fetched
     * pages become sources as soon as they are known rather than when the output item lands. Nothing
     * here is replayable and nothing needs to be — the request builder drops reasoning on replay.
     */
    fun reasoningParts(event: JsonObject): List<StreamPart> {
        val type = event.optString("type").orEmpty()
        val parts = mutableListOf<StreamPart>()
        val thought = event.optString("thought")
        val queries = event.optArray("queries")
        val urls = event.optArray("urls")
        if (thought != null || queries != null || urls != null) {
            val id = "reasoning-${reasoningBlocks++}"
            parts += StreamPart.ReasoningStart(id)
            if (!thought.isNullOrEmpty()) parts += StreamPart.ReasoningDelta(id, thought)
            parts += StreamPart.ReasoningEnd(
                id,
                providerMetadata = mapOf(
                    PERPLEXITY_PROVIDER_ID to buildJsonObject {
                        put("type", type)
                        queries?.let { put("queries", it) }
                        urls?.let { put("urls", it) }
                    },
                ),
            )
        }
        event.optArray("results").objects().forEach { result ->
            source(
                url = result.optString("url"),
                title = result.optString("title"),
                metadata = buildJsonObject {
                    put("type", type)
                    put("result", result)
                },
            )?.let { parts += StreamPart.SourcePart(it) }
        }
        event.optArray("contents").objects().forEach { content ->
            source(
                url = content.optString("url"),
                title = content.optString("title"),
                metadata = buildJsonObject {
                    put("type", type)
                    put("content", content)
                },
            )?.let { parts += StreamPart.SourcePart(it) }
        }
        return parts
    }

    /** A part the shared mapper produced, with this wire's corrections applied — or null to drop it. */
    fun adjust(part: StreamPart, usage: JsonObject?): StreamPart? = when (part) {
        is StreamPart.Finish -> part.copy(
            usage = perplexityAgentUsage(usage),
            providerMetadata = part.providerMetadata.withCost(usage.perplexityCost()),
        )
        is StreamPart.SourcePart -> part.takeIf { allow(part.source) }
        is StreamPart.ToolCallPart -> (adjust(part.toolCall) as? Content.ToolCall)?.let { part.copy(toolCall = it) }
        else -> part
    }

    private fun adjust(content: Content): Content? = when (content) {
        is Content.Source -> content.takeIf { allow(it) }
        is Content.ToolCall -> signatures[content.toolCallId]?.let { signature ->
            val own = content.providerMetadata?.get(PERPLEXITY_PROVIDER_ID).orEmpty()
            content.copy(
                providerMetadata = content.providerMetadata.orEmpty() +
                    (PERPLEXITY_PROVIDER_ID to JsonObject(own + (PERPLEXITY_THOUGHT_SIGNATURE_KEY to JsonPrimitive(signature)))),
            )
        } ?: content
        else -> content
    }

    private fun allow(source: Content.Source): Boolean =
        source !is Content.Source.Url || seenUrls.add(source.url)

    private fun source(url: String?, title: String?, metadata: JsonObject): Content.Source.Url? {
        if (url.isNullOrEmpty() || !seenUrls.add(url)) return null
        return Content.Source.Url(
            id = generateId(),
            url = url,
            title = title,
            providerMetadata = mapOf(PERPLEXITY_PROVIDER_ID to metadata),
        )
    }

    private fun customItem(item: JsonObject): Content.Custom = Content.Custom(
        kind = "$PERPLEXITY_PROVIDER_ID.${item.optString("type") ?: "item"}",
        providerMetadata = mapOf(PERPLEXITY_PROVIDER_ID to item),
    )
}

/** A vendor item's content as stream parts: only the two kinds [PerplexityOutputMapper.itemContent] makes. */
internal fun Content.asPerplexityStreamPart(): StreamPart? = when (this) {
    is Content.Custom -> StreamPart.CustomPart(this)
    is Content.Source -> StreamPart.SourcePart(this)
    else -> null
}

/** `{perplexity: {…, cost}}` — the finish metadata with the priced call added. */
internal fun Map<String, JsonObject>?.withCost(cost: JsonObject?): Map<String, JsonObject>? {
    if (cost == null) return this
    val own = this?.get(PERPLEXITY_PROVIDER_ID).orEmpty()
    return this.orEmpty() + (PERPLEXITY_PROVIDER_ID to JsonObject(own + ("cost" to cost)))
}

private fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>().orEmpty()
