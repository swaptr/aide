package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import kotlinx.coroutines.flow.flow

/**
 * Splits tag-delimited reasoning out of a model's text into real reasoning parts.
 *
 * The open-weight reasoning models — DeepSeek R1 and everything distilled from it, QwQ, the reasoning
 * Qwen and Llama fine-tunes — emit their chain of thought inline, wrapped in `<think>…</think>`, because
 * the OpenAI-compatible endpoints they are served behind have no field for it. A consumer that does not
 * split it renders the model's private deliberation to the user as if it were the answer, and a
 * structured-output caller hands a parser a JSON document with an essay in front of it.
 *
 * `openaicompatible`'s `ReasoningTagSplitter` does this inside one provider, behind an opt-in flag, and
 * stays there: a provider that knows its endpoint does this should not make every caller ask. This is
 * the reusable form, for the model served through a gateway or a proxy that the provider cannot know
 * about. The duplication is forced — `:aisdk:providers` sits below this module and must not be imported
 * from it.
 *
 * The streaming half is where the work is. A tag arrives split across chunks (`<thi` then `nk>`) often
 * enough that a splitter which only looks at whole deltas leaks half a tag into the answer, so the
 * buffer holds back any suffix that could still become one.
 *
 * @param tagName the element the model wraps its reasoning in — `think` matches `<think>…</think>`.
 * @param separator joins two segments of the same channel that were split apart by the other one.
 * @param startWithReasoning for a model whose opening tag is in its prompt template and therefore never
 *   appears in the output — the completion begins mid-thought and the first `</think>` is the switch.
 */
public fun extractReasoning(
    tagName: String = "think",
    separator: String = "\n",
    startWithReasoning: Boolean = false,
): LanguageModelMiddleware = ExtractReasoningMiddleware(tagName, separator, startWithReasoning)

private class ExtractReasoningMiddleware(
    private val tagName: String,
    private val separator: String,
    private val startWithReasoning: Boolean,
) : LanguageModelMiddleware {

    private val openingTag = "<$tagName>"
    private val closingTag = "</$tagName>"

    override suspend fun wrapGenerate(
        params: CallOptions,
        model: LanguageModel,
        doGenerate: suspend () -> GenerateResult,
    ): GenerateResult {
        val result = doGenerate()
        return result.copy(content = result.content.flatMap { part -> splitPart(part) })
    }

    override suspend fun wrapStream(
        params: CallOptions,
        model: LanguageModel,
        doStream: suspend () -> StreamResult,
    ): StreamResult {
        val upstream = doStream()
        return upstream.copy(
            stream = flow {
                val blocks = mutableMapOf<String, ReasoningTagExtractor>()
                upstream.stream.collect { part ->
                    when (part) {
                        // Held back rather than forwarded: a reasoning block that opens immediately must
                        // not be preceded by the start of a text block that has produced no text yet.
                        is StreamPart.TextStart -> blocks[part.id] = newExtractor(part)
                        is StreamPart.TextDelta ->
                            blocks.getOrPut(part.id) { newExtractor(null) }
                                .push(part.id, part.delta)
                                .forEach { emit(it) }
                        is StreamPart.TextEnd -> {
                            blocks.remove(part.id)?.flush(part.id)?.forEach { emit(it) }
                            emit(part)
                        }
                        else -> emit(part)
                    }
                }
            },
        )
    }

    private fun newExtractor(start: StreamPart.TextStart?) =
        ReasoningTagExtractor(openingTag, closingTag, separator, startWithReasoning, start)

    private fun splitPart(part: Content): List<Content> {
        if (part !is Content.Text) return listOf(part)

        val text = if (startWithReasoning) openingTag + part.text else part.text
        val matches = Regex(
            "${Regex.escape(openingTag)}(.*?)${Regex.escape(closingTag)}",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(text).toList()
        if (matches.isEmpty()) return listOf(part)

        val reasoning = matches.joinToString(separator) { it.groupValues[1] }
        // Right to left, so each removal's indices are still valid against the string being cut.
        val remaining = matches.foldRight(text) { match, carried ->
            val before = carried.substring(0, match.range.first)
            val after = carried.substring(match.range.last + 1)
            before + (if (before.isNotEmpty() && after.isNotEmpty()) separator else "") + after
        }
        return listOf(
            Content.Reasoning(reasoning),
            Content.Text(remaining, providerMetadata = part.providerMetadata),
        )
    }
}

/**
 * The streaming splitter for one text block.
 *
 * State rather than a fold because the interesting case is the one that spans two chunks: the buffer
 * keeps whatever trailing text could still turn out to be the beginning of a tag, and everything before
 * it goes out immediately — so a `<think>` split as `<thi` + `nk>` is a switch, not four characters of
 * leaked answer, and a `<` that turns out to be prose is emitted rather than eaten.
 */
private class ReasoningTagExtractor(
    private val openingTag: String,
    private val closingTag: String,
    private val separator: String,
    startWithReasoning: Boolean,
    private var pendingTextStart: StreamPart.TextStart?,
) {

    private var buffer = ""
    private var inReasoning = startWithReasoning
    private var afterSwitch = false
    private var firstReasoning = true
    private var firstText = true
    private var reasoningIndex = 0

    fun push(textId: String, delta: String): List<StreamPart> {
        buffer += delta
        val out = mutableListOf<StreamPart>()
        while (true) {
            val tag = if (inReasoning) closingTag else openingTag
            val at = buffer.potentialStartIndex(tag)
            if (at == null) {
                publish(textId, buffer, out)
                buffer = ""
                break
            }
            publish(textId, buffer.substring(0, at), out)
            if (at + tag.length > buffer.length) {
                // Only a prefix of the tag so far. Hold it: the rest may be in the next chunk.
                buffer = buffer.substring(at)
                break
            }
            buffer = buffer.substring(at + tag.length)
            if (inReasoning) {
                // `<think></think>` published nothing, so the block was never opened. It is still a
                // reasoning block, and a consumer that sees an end with no start has to guess.
                if (firstReasoning) out += StreamPart.ReasoningStart("reasoning-$reasoningIndex")
                out += StreamPart.ReasoningEnd("reasoning-$reasoningIndex")
                reasoningIndex++
                firstReasoning = false
            }
            inReasoning = !inReasoning
            afterSwitch = true
        }
        return out
    }

    /**
     * Whatever is left when the block ends.
     *
     * The remainder can only be a partial tag the model never completed — a literal `<thi` at the end of
     * the answer. It is published rather than dropped: text the model wrote is text the user asked for.
     */
    fun flush(textId: String): List<StreamPart> {
        val out = mutableListOf<StreamPart>()
        publish(textId, buffer, out)
        buffer = ""
        pendingTextStart?.let { out += it }
        pendingTextStart = null
        return out
    }

    private fun publish(textId: String, text: String, out: MutableList<StreamPart>) {
        if (text.isEmpty()) return
        val firstOfChannel = if (inReasoning) firstReasoning else firstText
        val prefix = if (afterSwitch && !firstOfChannel) separator else ""

        if (inReasoning) {
            if (afterSwitch || firstReasoning) out += StreamPart.ReasoningStart("reasoning-$reasoningIndex")
            out += StreamPart.ReasoningDelta("reasoning-$reasoningIndex", prefix + text)
            firstReasoning = false
        } else {
            pendingTextStart?.let { out += it }
            pendingTextStart = null
            out += StreamPart.TextDelta(textId, prefix + text)
            firstText = false
        }
        afterSwitch = false
    }
}

/**
 * Where [tag] starts in this string, counting a trailing partial match.
 *
 * The partial case is the whole point: `"answer: <thi"` returns 8, so the four characters that might be
 * the start of `<think>` stay buffered instead of being emitted and then regretted.
 */
private fun String.potentialStartIndex(tag: String): Int? {
    val full = indexOf(tag)
    if (full >= 0) return full
    for (length in minOf(length, tag.length - 1) downTo 1) {
        if (regionMatches(this.length - length, tag, 0, length)) return this.length - length
    }
    return null
}
