package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.runtime.extractJson as locateJson
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Reduces a model's text to the JSON inside it.
 *
 * A model told to answer in JSON still fences it, still writes "Here you go:" in front, and still adds
 * a sentence afterwards — the habit is near-universal on anything trained on chat. Without this, every
 * consumer of a structured answer writes its own unwrapper, and each one is subtly different.
 *
 * The located JSON is re-serialized rather than sliced out of the original text, so what reaches the
 * caller is canonical: no fence, no leading newline, no trailing prose. A text part with no JSON in it
 * is passed through untouched, because failing here would lose an answer that a stricter parser
 * downstream can complain about with better context.
 *
 * The streaming half buffers each text block and emits the extraction as a single delta at the end.
 * That is deliberate and not a shortcut: JSON cannot be un-fenced incrementally without knowing where
 * the fence ends, and a consumer of this middleware is parsing a whole document anyway. A caller that
 * wants to render a structured answer as it arrives wants `streamObject`, which parses the partial
 * document rather than waiting for it.
 */
public fun extractJson(): LanguageModelMiddleware = object : LanguageModelMiddleware {

    override suspend fun wrapGenerate(
        params: CallOptions,
        model: LanguageModel,
        doGenerate: suspend () -> GenerateResult,
    ): GenerateResult {
        val result = doGenerate()
        return result.copy(
            content = result.content.map { part ->
                if (part is Content.Text) part.copy(text = unwrap(part.text)) else part
            },
        )
    }

    override suspend fun wrapStream(
        params: CallOptions,
        model: LanguageModel,
        doStream: suspend () -> StreamResult,
    ): StreamResult {
        val upstream = doStream()
        return upstream.copy(
            stream = flow {
                val buffers = mutableMapOf<String, StringBuilder>()
                upstream.stream.collect { part ->
                    when (part) {
                        is StreamPart.TextStart -> {
                            buffers[part.id] = StringBuilder()
                            emit(part)
                        }
                        // Held, not forwarded: the fence this middleware exists to remove is only
                        // identifiable once the block that contains it has ended.
                        is StreamPart.TextDelta -> buffers[part.id]
                            ?.append(part.delta)
                            ?: emit(part)
                        is StreamPart.TextEnd -> {
                            buffers.remove(part.id)?.toString()?.let { buffered ->
                                val unwrapped = unwrap(buffered)
                                if (unwrapped.isNotEmpty()) emit(StreamPart.TextDelta(part.id, unwrapped))
                            }
                            emit(part)
                        }
                        else -> emit(part)
                    }
                }
            },
        )
    }
}

/** The JSON the text contains, canonically serialized, or the text as it stands if there is none. */
private fun unwrap(text: String): String =
    locateJson(text)?.let { Json.encodeToString(JsonElement.serializer(), it) }
        ?: text.trim()
