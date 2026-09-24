package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.TypeValidationError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The reference's `elementStream` cases (`stream-object.test.ts:1333`): a list-shaped run surfaced as
 * one emission per COMPLETE element, never a revision. The property under test is exactly the one the
 * held-back last element buys — an element is emitted once, whole, and a consumer appending rows never
 * repaints one.
 */
class ElementStreamTest {

    private class Streaming(private val deltas: List<String>) : LanguageModel {
        override val provider: String = "t"
        override val modelId: String = "t-1"

        override suspend fun doStream(options: CallOptions): StreamResult = StreamResult(
            (
                listOf(StreamPart.TextStart("t")) +
                    deltas.map { StreamPart.TextDelta("t", it) } +
                    listOf(
                        StreamPart.TextEnd("t"),
                        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
                    )
                ).asFlow(),
        )

        override suspend fun doGenerate(options: CallOptions): GenerateResult =
            assembleGenerateResult(doStream(options).stream)
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("list them"))))
    private val itemSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("content", buildJsonObject { put("type", "string") }) })
    }

    @Test
    fun `elements are emitted individually, each exactly once, in order`() = runTest {
        val model = Streaming(
            listOf(
                """{"elements":[{"content":"element 1"},""",
                """{"content":"element 2"},""",
                """{"content":"element 3"},""",
                """{"content":"element 4"}]}""",
            ),
        )

        val elements = streamObject(model, prompt, ObjectOutput.Array(itemSchema))
            .elementStream()
            .toList()
            .map { it.jsonObject["content"]!!.jsonPrimitive.content }

        assertEquals(listOf("element 1", "element 2", "element 3", "element 4"), elements)
    }

    @Test
    fun `the held-back final element arrives with the finish`() = runTest {
        // A single element that only ever completes when the document closes: nothing streams early,
        // and the finish still delivers it — off the validated final list, not off a partial.
        val model = Streaming(listOf("""{"elements":[{"content":"only"}]}"""))

        val elements = streamObject(model, prompt, ObjectOutput.Array(itemSchema))
            .elementStream()
            .toList()
            .map { it.jsonObject["content"]!!.jsonPrimitive.content }

        assertEquals(listOf("only"), elements)
    }

    @Test
    fun `the element stream fails once the model has streamed past maxItems`() = runTest {
        val model = Streaming(
            listOf(
                """{"elements":[{"content":"one"},""",
                """{"content":"two"},""",
                """{"content":"three"},""",
                """{"content":"four"}]}""",
            ),
        )
        val received = mutableListOf<JsonElement>()

        // The reference errors its element stream at the element past the limit; ours fails the partial
        // read at the same moment, so the extra element is never published and then retracted.
        assertFailsWith<TypeValidationError> {
            streamObject(model, prompt, ObjectOutput.Array(itemSchema, maxItems = 2)).elementStream().toList(received)
        }
        assertEquals(listOf("one", "two"), received.map { it.jsonObject["content"]!!.jsonPrimitive.content })
    }
}
