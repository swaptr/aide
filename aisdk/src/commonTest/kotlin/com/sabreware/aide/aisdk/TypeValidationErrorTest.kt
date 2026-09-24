package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The reference's `TypeValidationError` message and `wrap` rules, pinned against its own snapshot
 * strings — see [TypeValidationErrorFixtures] for where each came from.
 *
 * The prefix is the half of the message a reader acts on. A failure in a conversation of forty tool
 * calls that says only "Type validation failed" sends whoever is debugging it to the wrong call; one
 * that says which field, which tool and which id does not. Each combination of the three is a case
 * here because each renders differently, and a Node host and a Kotlin host must render them the same.
 */
class TypeValidationErrorTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    // -----------------------------------------------------------------------------------------------
    // The prefix rule
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `no context renders the bare prefix`() {
        val error = TypeValidationError(json("""{"cities":"San Francisco"}"""), "expected string")

        assertEquals(
            "${TypeValidationErrorFixtures.BARE_PREFIX}: Value: {\"cities\":\"San Francisco\"}.\n" +
                "Error message: expected string",
            error.message,
        )
    }

    @Test
    fun `a field and an id render without an entity name`() {
        val error = TypeValidationError(
            json("""{"foo":123}"""),
            TypeValidationErrorFixtures.ZOD_REASON,
            context = TypeValidationContext(field = "messages[0].metadata", entityId = "1"),
        )

        assertEquals(TypeValidationErrorFixtures.METADATA_WITH_ID, error.message)
    }

    @Test
    fun `an entity name alone is parenthesised without an id`() {
        val error = TypeValidationError(
            json("""{"foo":123}"""),
            TypeValidationErrorFixtures.ZOD_REASON,
            context = TypeValidationContext(field = "messages[0].parts[0].data", entityName = "foo"),
        )

        assertEquals(TypeValidationErrorFixtures.DATA_WITH_NAME, error.message)
    }

    @Test
    fun `an entity name and an id are comma-joined inside the parentheses`() {
        val error = TypeValidationError(
            json("""{"foo":123}"""),
            TypeValidationErrorFixtures.ZOD_REASON,
            context = TypeValidationContext(
                field = "messages[0].parts[0].input",
                entityName = "foo",
                entityId = "1",
            ),
        )

        assertEquals(TypeValidationErrorFixtures.INPUT_WITH_NAME_AND_ID, error.message)
    }

    @Test
    fun `an entity without a field still gets its parentheses`() {
        // The reference appends the parenthesised part on `entityName || entityId`, independently of
        // `field`; a context that names the tool but not the path is the shape a tool-input check makes.
        val prefix = TypeValidationContext(entityName = "weather", entityId = "call-1").messagePrefix()

        assertEquals("Type validation failed (weather, id: \"call-1\")", prefix)
    }

    @Test
    fun `an empty string is as absent as a null, as in the reference's truthiness checks`() {
        val prefix = TypeValidationContext(field = "", entityName = "", entityId = "").messagePrefix()

        assertEquals(TypeValidationErrorFixtures.BARE_PREFIX, prefix)
    }

    @Test
    fun `a value that never parsed renders as undefined, which is what JSON stringify says of one`() {
        val error = TypeValidationError(null, "Expected JSON")

        assertEquals("Type validation failed: Value: undefined.\nError message: Expected JSON", error.message)
    }

    @Test
    fun `the error name and the cause are kept`() {
        val cause = IllegalStateException("boom")
        val error = TypeValidationError(json("1"), "not a string", cause)

        assertEquals("AI_TypeValidationError", error.errorName)
        assertSame(cause, error.cause)
        assertEquals(json("1"), error.value)
    }

    // -----------------------------------------------------------------------------------------------
    // wrap
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `wrap returns a cause that already reports this value and context`() {
        val context = TypeValidationContext(field = "tool context", entityName = "weather")
        val inner = TypeValidationError(json("""{"a":1}"""), "expected string", context = context)

        val wrapped = TypeValidationError.wrap(json("""{"a":1}"""), inner, context)

        // Nesting it would say the same thing twice, with the useful half on the inside.
        assertSame(inner, wrapped)
    }

    @Test
    fun `wrap treats an absent context and an empty one as the same context`() {
        val inner = TypeValidationError(json("[]"), "expected object", context = TypeValidationContext())

        assertSame(inner, TypeValidationError.wrap(json("[]"), inner, context = null))
        assertSame(inner, TypeValidationError.wrap(json("[]"), inner, context = TypeValidationContext()))
    }

    @Test
    fun `wrap nests a cause that reports a different value or context`() {
        val inner = TypeValidationError(
            json("""{"a":1}"""),
            "expected string",
            context = TypeValidationContext(field = "elements"),
        )
        val outerContext = TypeValidationContext(field = "response.text", entityName = "Array")

        val wrapped = TypeValidationError.wrap(json("""{"a":1}"""), inner, outerContext)

        assertNotSame(inner, wrapped)
        assertSame(inner, wrapped.cause)
        assertEquals(outerContext, wrapped.context)
        // The inner error's whole message becomes the reason, so nothing it said is lost.
        assertTrue(wrapped.message!!.endsWith("Error message: ${inner.message}"), wrapped.message!!)
        assertNotSame(wrapped, TypeValidationError.wrap(json("""{"a":2}"""), inner, inner.context))
    }

    @Test
    fun `wrap turns any other throwable into the reason`() {
        val cause = IllegalArgumentException("Expected a string.")

        val wrapped = TypeValidationError.wrap(json("1"), cause, TypeValidationContext(field = "name"))

        assertSame(cause, wrapped.cause)
        assertEquals(
            "Type validation failed for name: Value: 1.\nError message: Expected a string.",
            wrapped.message,
        )
    }

    @Test
    fun `wrap without a cause names the absence rather than the string null`() {
        val wrapped = TypeValidationError.wrap(json("1"), cause = null)

        assertEquals("Type validation failed: Value: 1.\nError message: unknown error", wrapped.message)
    }
}

/**
 * The reference's own inline snapshots, byte for byte.
 *
 * `ZOD_REASON` is the zod issue list every one of them ends with; only the prefix differs between
 * them, which is exactly what the tests above vary.
 */
private object TypeValidationErrorFixtures {

    const val BARE_PREFIX = "Type validation failed"

    // The `Error message:` half of every snapshot below — a zod issue list, kept verbatim.
    val ZOD_REASON = """
        |[
        |  {
        |    "expected": "string",
        |    "code": "invalid_type",
        |    "path": [
        |      "foo"
        |    ],
        |    "message": "Invalid input: expected string, received number"
        |  }
        |]
    """.trimMargin()

    // ai/src/ui/validate-ui-messages.test.ts — "should throw type validation error for invalid metadata"
    val METADATA_WITH_ID =
        "Type validation failed for messages[0].metadata (id: \"1\"): Value: {\"foo\":123}.\n" +
            "Error message: $ZOD_REASON"

    // ai/src/ui/validate-ui-messages.test.ts — the data-part schema case
    val DATA_WITH_NAME =
        "Type validation failed for messages[0].parts[0].data (foo): Value: {\"foo\":123}.\n" +
            "Error message: $ZOD_REASON"

    // ai/src/ui/validate-ui-messages.test.ts — the tool-input schema case
    val INPUT_WITH_NAME_AND_ID =
        "Type validation failed for messages[0].parts[0].input (foo, id: \"1\"): Value: {\"foo\":123}.\n" +
            "Error message: $ZOD_REASON"
}
