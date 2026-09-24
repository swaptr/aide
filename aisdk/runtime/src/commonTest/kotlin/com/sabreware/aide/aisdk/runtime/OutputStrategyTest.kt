package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoObjectGeneratedError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.TypeValidationError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Replies with [chunks] as text deltas, and records what it was asked for. */
private class Saying(private vararg val chunks: String) : LanguageModel {

    override val provider: String = "test"
    override val modelId: String = "test"
    var lastOptions: CallOptions? = null

    private fun parts(): List<StreamPart> = buildList {
        add(StreamPart.TextStart("t"))
        chunks.forEach { add(StreamPart.TextDelta("t", it)) }
        add(StreamPart.TextEnd("t"))
        add(StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)))
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        lastOptions = options
        return StreamResult(parts().asFlow())
    }

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        lastOptions = options
        return assembleGenerateResult(parts().asFlow())
    }
}

private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

private val itemSchema = buildJsonObject {
    put("type", "object")
    put("\$defs", buildJsonObject { put("Colour", buildJsonObject { put("type", "string") }) })
}

class ArrayOutputTest {

    @Test
    fun `the schema sent to the vendor wraps the item schema in an elements array`() {
        val schema = ObjectOutput.Array(itemSchema).requestSchema
        assertEquals(
            JsonPrimitive("array"),
            schema["properties"]?.jsonObject?.get("elements")?.jsonObject?.get("type"),
        )
        assertEquals(listOf(JsonPrimitive("elements")), schema["required"]?.jsonArray?.toList())
    }

    @Test
    fun `root definitions move to the wrapper so a root-relative ref still resolves`() {
        val schema = ObjectOutput.Array(itemSchema).requestSchema
        assertTrue(schema.containsKey("\$defs"))
        val items = schema["properties"]!!.jsonObject["elements"]!!.jsonObject["items"]!!.jsonObject
        assertNull(items["\$defs"])
    }

    @Test
    fun `the wrapper is unwrapped on the way back`() = runTest {
        val model = Saying("""{"elements":[{"a":1},{"a":2}]}""")

        val result = generateOutput(model, prompt, ObjectOutput.Array(itemSchema))

        assertEquals(2, result.value.size)
        assertEquals(JsonPrimitive(2), result.value[1].jsonObject["a"])
    }

    @Test
    fun `a response that is not the wrapper fails rather than being read as one element`() = runTest {
        val model = Saying("""{"a":1}""")

        assertFailsWith<NoObjectGeneratedError> {
            generateOutput(model, prompt, ObjectOutput.Array(itemSchema))
        }
    }

    @Test
    fun `a partial array holds back its last, half-written element`() {
        val half = parsePartialJson("""{"elements":[{"a":1},{"a":2},{"a":""").value!!
        assertEquals(2, ObjectOutput.Array(itemSchema).partial(half)?.size)
    }

    // --- bounds: the reference's minItems / maxItems cases ------------------------------------------

    private val stringSchema = buildJsonObject { put("type", "string") }

    @Test
    fun `the bounds go out on the schema`() {
        val elements = ObjectOutput.Array(itemSchema, minItems = 0, maxItems = 3)
            .requestSchema["properties"]!!.jsonObject["elements"]!!.jsonObject

        assertEquals(JsonPrimitive(0), elements["minItems"])
        assertEquals(JsonPrimitive(3), elements["maxItems"])
        assertNull(ObjectOutput.Array(itemSchema).requestSchema["properties"]!!.jsonObject["elements"]!!.jsonObject["minItems"])
    }

    @Test
    fun `a negative bound is refused`() {
        assertEquals("minItems", assertFailsWith<InvalidArgumentError> { ObjectOutput.Array(itemSchema, minItems = -1) }.argument)
        assertEquals("maxItems", assertFailsWith<InvalidArgumentError> { ObjectOutput.Array(itemSchema, maxItems = -1) }.argument)
    }

    @Test
    fun `a low bound above the high one is refused`() {
        val error = assertFailsWith<InvalidArgumentError> { ObjectOutput.Array(itemSchema, minItems = 3, maxItems = 2) }

        assertEquals("minItems must be less than or equal to maxItems", error.message)
        assertEquals("minItems", error.argument)
    }

    @Test
    fun `a response within the bounds is accepted`() = runTest {
        val result = generateOutput(
            Saying("""{"elements":["a","b"]}"""),
            prompt,
            ObjectOutput.Array(stringSchema, minItems = 2, maxItems = 3),
        )

        assertEquals(listOf(JsonPrimitive("a"), JsonPrimitive("b")), result.value)
    }

    @Test
    fun `a response outside the bounds is no object, with the length rule as the cause`() = runTest {
        val short = assertFailsWith<NoObjectGeneratedError> {
            generateOutput(Saying("""{"elements":["a"]}"""), prompt, ObjectOutput.Array(stringSchema, minItems = 2))
        }
        val long = assertFailsWith<NoObjectGeneratedError> {
            generateOutput(Saying("""{"elements":["a","b","c"]}"""), prompt, ObjectOutput.Array(stringSchema, maxItems = 2))
        }

        assertTrue(assertIs<TypeValidationError>(short.cause).message!!.contains("elements array must contain at least 2 items"))
        assertTrue(assertIs<TypeValidationError>(long.cause).message!!.contains("elements array must contain at most 2 items"))
    }

    @Test
    fun `a partial that has settled more elements than maxItems fails rather than publishing the extra one`() {
        val past = parsePartialJson("""{"elements":[{"a":1},{"a":2},{"a":""").value!!

        assertFailsWith<TypeValidationError> { ObjectOutput.Array(itemSchema, maxItems = 1).partial(past) }
        assertEquals(2, ObjectOutput.Array(itemSchema, maxItems = 2).partial(past)?.size)
    }
}

class EnumOutputTest {

    private val output = ObjectOutput.Enum(listOf("blue", "black", "red"))

    @Test
    fun `the schema asks for the value wrapped in a result property`() {
        val properties = output.requestSchema["properties"]!!.jsonObject
        assertEquals(
            listOf("blue", "black", "red").map(::JsonPrimitive),
            properties["result"]!!.jsonObject["enum"]!!.jsonArray.toList(),
        )
    }

    @Test
    fun `the wrapper is unwrapped on the way back`() = runTest {
        assertEquals("red", generateOutput(Saying("""{"result":"red"}"""), prompt, output).value)
    }

    @Test
    fun `a value outside the enum fails`() = runTest {
        val error = assertFailsWith<NoObjectGeneratedError> {
            generateOutput(Saying("""{"result":"green"}"""), prompt, output)
        }
        assertTrue(error.cause is TypeValidationError)
    }

    @Test
    fun `an ambiguous prefix is not yet an answer`() {
        assertNull(output.partial(buildJsonObject { put("result", "bl") }))
        assertEquals("red", output.partial(buildJsonObject { put("result", "r") }))
    }
}

class NoSchemaOutputTest {

    @Test
    fun `no schema is sent, and whatever the model returned comes back`() = runTest {
        val model = Saying("""{"anything":[1,2]}""")

        val result = generateOutput(model, prompt, ObjectOutput.NoSchema)

        assertNull((model.lastOptions?.responseFormat as ResponseFormat.Json).schema)
        assertEquals(2, result.value.jsonObject["anything"]?.jsonArray?.size)
    }
}

class InjectedSchemaTest {

    @Test
    fun `the schema is put in the prompt for vendors that ignore the response format`() = runTest {
        val model = Saying("{}")
        val schema = buildJsonObject { put("type", "object") }

        generateObjectJson(model, prompt, schema, injectSchemaIntoPrompt = true)

        val system = model.lastOptions?.prompt?.first() as ModelMessage.System
        assertTrue(system.content.contains("JSON schema:"), system.content)
        // Still sent as a response format: injection is a belt for vendors that ignore the braces, not
        // a replacement for constrained decoding on the ones that honour it.
        assertEquals(schema, (model.lastOptions?.responseFormat as ResponseFormat.Json).schema)
    }
}

class RepairTextTest {

    private val schema = buildJsonObject { put("type", "object") }

    @Test
    fun `a repair hook gets a second attempt at text that would not fit`() = runTest {
        val model = Saying("nope")

        val result = generateObjectJson(
            model, prompt, schema,
            repairText = { _, _ -> """{"ok":true}""" },
        )

        assertEquals(JsonPrimitive(true), result.value.jsonObject["ok"])
    }

    @Test
    fun `a repair hook that declines lets the original failure stand`() = runTest {
        assertFailsWith<NoObjectGeneratedError> {
            generateObjectJson(Saying("nope"), prompt, schema, repairText = { _, _ -> null })
        }
    }

    @Test
    fun `the hook fires on a shape mismatch, not only on unparseable text`() = runTest {
        var sawError: Throwable? = null

        generateOutput(
            Saying("""{"result":"green"}"""),
            prompt,
            ObjectOutput.Enum(listOf("red")),
            repairText = { _, error -> sawError = error; """{"result":"red"}""" },
        )

        assertTrue(sawError is TypeValidationError)
    }
}

class StreamObjectTest {

    private val schema = buildJsonObject { put("type", "object") }

    @Test
    fun `partial objects are emitted as the JSON arrives`() = runTest {
        val model = Saying("""{"na""", """me":"Ad""", """a","age":36}""")

        val partials = streamObject(model, prompt, ObjectOutput.Object(schema))
            .toList()
            .filterIsInstance<ObjectStreamEvent.Partial<*>>()
            .map { it.value }

        // The first delta closes to `{}`; the second to `{"name":"Ad"}`; the third completes it. What
        // matters is that a caller saw the name before the stream ended.
        assertTrue(partials.size >= 2, "expected progressive partials, got $partials")
        assertEquals("Ada", (partials.last() as kotlinx.serialization.json.JsonObject)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an identical reading is not re-emitted`() = runTest {
        val model = Saying("""{"a":1}""", "   ", "  ")

        val partials = streamObject(model, prompt, ObjectOutput.Object(schema))
            .toList()
            .filterIsInstance<ObjectStreamEvent.Partial<*>>()

        assertEquals(1, partials.size)
    }

    @Test
    fun `a stream cut off mid-object still yields the part that arrived`() = runTest {
        val model = Saying("""{"name":"Ada","age":3""")

        val finish = streamObject(model, prompt, ObjectOutput.Object(schema))
            .toList()
            .filterIsInstance<ObjectStreamEvent.Finish<*>>()
            .single()

        // Without the partial-JSON recovery this is a total loss; with it the caller keeps the name.
        assertEquals("Ada", (finish.result.value as kotlinx.serialization.json.JsonObject)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `text that is not JSON at all still fails, carrying what the run cost`() = runTest {
        val error = assertFailsWith<NoObjectGeneratedError> {
            streamObject(Saying("I'd rather not."), prompt, ObjectOutput.Object(schema)).toList()
        }
        assertEquals("I'd rather not.", error.text)
    }
}
