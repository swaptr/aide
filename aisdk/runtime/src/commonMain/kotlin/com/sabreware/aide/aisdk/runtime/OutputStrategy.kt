package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.TypeValidationContext
import com.sabreware.aide.aisdk.TypeValidationError
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What shape a structured call is asking for, and how to get it back out of what the model returned.
 *
 * [Array] and [Enum] exist because the thing a caller wants and the thing a model can reliably produce
 * are different: almost no model emits a bare top-level array or a bare enum value, and constrained
 * decoding on most vendors is only defined for an object. So both are asked for wrapped — `{"elements":
 * […]}`, `{"result": "…"}` — and unwrapped here. A caller that hand-rolled that wrapping would also have
 * to hand-roll the unwrapping, and would get the partial-parse case wrong, which is the case that
 * matters while a list is still streaming.
 */
public sealed interface ObjectOutput<T> {

    /** The schema sent to the vendor, which for a wrapped strategy is not the caller's own. */
    public val requestSchema: JsonSchema?

    /** Unwraps and checks a COMPLETE response. Throws [TypeValidationError] if it does not fit. */
    public fun validate(value: JsonElement): T

    /**
     * The best reading of a response that is still arriving, or null if there is nothing usable yet.
     *
     * Separate from [validate] because a partial document fails the checks a complete one must pass —
     * the last array element is half-written, the enum value is a prefix — and a strategy that reused
     * the strict path would render nothing until the stream closed, which is the whole thing progressive
     * structured output exists to avoid.
     */
    public fun partial(value: JsonElement): T?

    /** A single JSON object, the caller's schema sent verbatim. */
    public data class Object(override val requestSchema: JsonSchema?) : ObjectOutput<JsonElement> {

        override fun validate(value: JsonElement): JsonElement = value

        override fun partial(value: JsonElement): JsonElement = value
    }

    /**
     * A single object, decoded into [T].
     *
     * Decoding is part of validation rather than a step after it, so a response whose keys are right and
     * whose types are wrong reaches [RepairText] like any other mismatch. Split across two stages it
     * would not: the object would parse, the call would succeed, and the decode would throw somewhere
     * the repair hook cannot see.
     */
    public data class Decoded<T>(
        override val requestSchema: JsonSchema?,
        val deserializer: DeserializationStrategy<T>,
    ) : ObjectOutput<T> {

        override fun validate(value: JsonElement): T =
            runCatching { ProviderJson.decodeFromJsonElement(deserializer, value) }
                .getOrElse {
                    throw TypeValidationError(
                        value,
                        "Decoding failed: ${it.message}",
                        it,
                        context = TypeValidationContext(entityName = deserializer.descriptor.serialName),
                    )
                }

        // A half-written object decodes only by luck — a required field that has not arrived yet is a
        // failure, not a partial value — so there is nothing to show until it validates.
        override fun partial(value: JsonElement): T? =
            runCatching { ProviderJson.decodeFromJsonElement(deserializer, value) }.getOrNull()
    }

    /**
     * JSON of whatever shape the model chooses.
     *
     * Distinct from `Object(null)` only in intent, and worth naming for it: a caller reaches for this
     * when the answer's shape is genuinely open, and the absence of a schema then reads as a decision
     * rather than as an argument someone forgot.
     */
    public data object NoSchema : ObjectOutput<JsonElement> {

        override val requestSchema: JsonSchema? get() = null

        override fun validate(value: JsonElement): JsonElement = value

        override fun partial(value: JsonElement): JsonElement = value
    }

    /**
     * A list of [itemSchema]-shaped elements, asked for as `{"elements": […]}`.
     *
     * Root-level `definitions` and `$defs` move up to the wrapper, because a root-relative `$ref` inside
     * the item schema resolves against the document root — nest the definitions under `items` and every
     * reference in a schema that used them dangles.
     *
     * [minItems] and [maxItems] bound the list: they go out on the schema, where a constrained decoder
     * honours them, and are checked on the way back, where one that does not gets caught. A response
     * outside them is no object — the same failure as a shape mismatch, with the length rule as its
     * cause — and a PARTIAL that has already settled more elements than [maxItems] fails as soon as it
     * does, which is where the reference fails its element stream: an element past the limit must not
     * be published and then retracted.
     */
    public data class Array(
        val itemSchema: JsonSchema,
        /** The fewest elements a valid response may hold. Null leaves the low end open. */
        val minItems: Int? = null,
        /** The most elements a valid response may hold. Null leaves the high end open. */
        val maxItems: Int? = null,
    ) : ObjectOutput<List<JsonElement>> {

        init {
            minItems?.let { requireNonNegative("minItems", it) }
            maxItems?.let { requireNonNegative("maxItems", it) }
            if (minItems != null && maxItems != null && minItems > maxItems) {
                throw InvalidArgumentError("minItems must be less than or equal to maxItems", "minItems")
            }
        }

        override val requestSchema: JsonSchema get() = buildJsonObject {
            put("\$schema", JsonPrimitive(JSON_SCHEMA_DRAFT))
            itemSchema[DEFINITIONS]?.let { put(DEFINITIONS, it) }
            itemSchema[DEFS]?.let { put(DEFS, it) }
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject(ELEMENTS) {
                    put("type", JsonPrimitive("array"))
                    put("items", JsonObject(itemSchema - "\$schema" - DEFINITIONS - DEFS))
                    minItems?.let { put("minItems", JsonPrimitive(it)) }
                    maxItems?.let { put("maxItems", JsonPrimitive(it)) }
                }
            }
            putJsonArray("required") { add(JsonPrimitive(ELEMENTS)) }
            put("additionalProperties", JsonPrimitive(false))
        }

        override fun validate(value: JsonElement): List<JsonElement> {
            val elements = (value as? JsonObject)?.get(ELEMENTS) as? JsonArray
                ?: throw TypeValidationError(
                    value,
                    "Expected an object with an \"$ELEMENTS\" array.",
                    context = TypeValidationContext(field = ELEMENTS),
                )
            lengthViolation(elements, minItems, maxItems)?.let { throw it }
            return elements
        }

        // The final element of a partial array is by definition half-written, so it is held back: an
        // element that appears with two of its five fields and then gains the rest re-renders as a
        // different row, which reads as the model changing its mind.
        override fun partial(value: JsonElement): List<JsonElement>? {
            val settled = ((value as? JsonObject)?.get(ELEMENTS) as? JsonArray)?.dropLast(1) ?: return null
            lengthViolation(JsonArray(settled), minItems = null, maxItems = maxItems)?.let { throw it }
            return settled
        }
    }

    /**
     * One of [values], asked for as `{"result": "…"}`.
     *
     * A partial read resolves a prefix only where exactly one value can still match — `"b"` against
     * `["blue", "black"]` is not yet an answer, and showing either one would be a guess the caller
     * cannot tell from a decision.
     */
    public data class Enum(val values: List<String>) : ObjectOutput<String> {

        override val requestSchema: JsonSchema get() = buildJsonObject {
            put("\$schema", JsonPrimitive(JSON_SCHEMA_DRAFT))
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject(RESULT) {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                }
            }
            putJsonArray("required") { add(JsonPrimitive(RESULT)) }
            put("additionalProperties", JsonPrimitive(false))
        }

        override fun validate(value: JsonElement): String {
            val result = value.resultString()
                ?: throw TypeValidationError(
                    value,
                    "Expected an object with a string \"$RESULT\".",
                    context = TypeValidationContext(field = RESULT),
                )
            return result.takeIf { it in values }
                ?: throw TypeValidationError(
                    value,
                    "\"$result\" is not one of: ${values.joinToString()}.",
                    context = TypeValidationContext(field = RESULT),
                )
        }

        override fun partial(value: JsonElement): String? {
            val prefix = value.resultString()?.takeIf { it.isNotEmpty() } ?: return null
            return values.filter { it.startsWith(prefix) }.singleOrNull()
        }

        private fun JsonElement.resultString(): String? =
            ((this as? JsonObject)?.get(RESULT) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** The reference's bound check, minus the integer half — an [Int] cannot fail it. */
private fun requireNonNegative(name: String, value: Int) {
    if (value < 0) throw InvalidArgumentError("$name must be greater than or equal to 0", name)
}

/** The reference's length rule, as the validation error its `NoObjectGeneratedError` carries as cause. */
private fun lengthViolation(elements: JsonArray, minItems: Int?, maxItems: Int?): TypeValidationError? {
    val rule = when {
        minItems != null && elements.size < minItems -> "elements array must contain at least $minItems items"
        maxItems != null && elements.size > maxItems -> "elements array must contain at most $maxItems items"
        else -> return null
    }
    return TypeValidationError(elements, rule, context = TypeValidationContext(field = ELEMENTS))
}

private const val ELEMENTS = "elements"
private const val RESULT = "result"
private const val DEFINITIONS = "definitions"
private const val DEFS = "\$defs"
private const val JSON_SCHEMA_DRAFT = "http://json-schema.org/draft-07/schema#"
