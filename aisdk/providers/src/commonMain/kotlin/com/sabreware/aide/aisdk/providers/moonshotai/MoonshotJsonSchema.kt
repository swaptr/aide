package com.sabreware.aide.aisdk.providers.moonshotai

import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rewrites a JSON Schema into the subset Moonshot's MFJS validator accepts.
 *
 * Every tool's `inputSchema` and every structured-output schema goes through this before it reaches the
 * wire. Without it Moonshot rejects schemas that are perfectly ordinary JSON Schema elsewhere, and the
 * rejection names the schema rather than the keyword, so the caller has no way to tell which of their
 * fields was the problem.
 *
 * **This port follows Moonshot's published MFJS specification, and DIVERGES from the vendored
 * `ai@7.0.85` reference on the central point.** The reference converts a tuple-form `items` array into
 * `prefixItems`; the specification lists `prefixItems` among the keywords MFJS does not support —
 * *"No advanced array definitions: Tuple simulation using `prefixItems` or `unevaluatedItems` is not
 * supported"* — so the reference's output is itself invalid MFJS. Emitting what the spec prohibits in
 * order to match a snapshot of another client would be porting a bug with extra steps.
 *
 * What happens instead, and why each choice is the least-lossy one available:
 *
 * - **A tuple collapses into a union over its element schemas.** MFJS gives `items` a single schema
 *   applied to every element, so positional typing cannot survive at all. `[A, B]` becomes
 *   `items: {anyOf: [A, B]}`: an array of two different things still validates, which is weaker than
 *   the caller wrote but is the strongest statement the target language can make. Dropping `items`
 *   entirely would accept anything; keeping the tuple would be rejected outright.
 * - **Prohibited keywords are removed rather than left to fail.** See [MFJS_PROHIBITED_KEYS]. These are
 *   annotations and refinements MFJS does not implement, so removing them costs description, never
 *   correctness — and the schema the AI SDK validates results against is the caller's original, which
 *   this function never touches.
 * - **`type` beside `anyOf` moves into the branches.** The specification does not address the
 *   combination, so this follows the reference, whose own examples put the type inside each branch.
 *
 * The root must still describe an object. The specification does not require that of schemas in
 * general, but a function's `parameters` is an argument bag on every vendor, and a non-object root is
 * a caller mistake worth naming here rather than as a vendor 400 later.
 */
internal fun normalizeJsonSchemaForMfjs(schema: JsonElement): JsonElement =
    normalizeDefinition(schema, isRoot = true)

/**
 * Keywords MFJS does not implement, removed on the way out.
 *
 * `prefixItems` and `unevaluatedItems` are the tuple machinery; `format`, `title` and `$comment` are
 * annotations; the four numeric and array bounds are refinements the validator does not evaluate.
 */
private val MFJS_PROHIBITED_KEYS = setOf(
    "prefixItems",
    "unevaluatedItems",
    "format",
    "title",
    "\$comment",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "minContains",
    "maxContains",
)

/** Sub-schemas held in an array. `prefixItems` is absent deliberately — it is stripped, not recursed. */
private val SCHEMA_ARRAY_KEYS = listOf("allOf", "anyOf", "oneOf")

/** Sub-schemas held as the values of an object. */
private val SCHEMA_MAP_KEYS = listOf("properties", "patternProperties", "\$defs", "dependentSchemas")

/** Sub-schemas held directly. `items` is handled ahead of these, because a tuple changes shape. */
private val SCHEMA_SINGLE_KEYS =
    listOf("additionalProperties", "propertyNames", "contains", "not", "if", "then", "else")

private const val ROOT_MUST_BE_OBJECT =
    "tool parameters must be a JSON Schema object with type \"object\" for moonshotai (MFJS)"

private fun normalizeDefinition(definition: JsonElement, isRoot: Boolean): JsonElement {
    val obj = definition as? JsonObject
    if (obj == null) {
        // A boolean schema (`true`/`false`) is legal JSON Schema and meaningless as a parameter bag.
        if (isRoot) throw UnsupportedFunctionalityError(ROOT_MUST_BE_OBJECT)
        return definition
    }
    if (isRoot && (obj["type"] as? JsonPrimitive)?.contentOrNullIfNotString() != "object") {
        throw UnsupportedFunctionalityError(ROOT_MUST_BE_OBJECT)
    }

    val result = obj.toMutableMap()
    MFJS_PROHIBITED_KEYS.forEach(result::remove)

    // The tuple. `prefixItems` was just stripped, so anything positional the caller wrote is folded in
    // here first and the union is the only surviving expression of it.
    val positional = buildList {
        (obj["prefixItems"] as? JsonArray)?.let(::addAll)
        (obj["items"] as? JsonArray)?.let(::addAll)
    }
    when {
        positional.isNotEmpty() -> {
            val branches = positional.map { normalizeDefinition(it, isRoot = false) }
            result["items"] = if (branches.size == 1) {
                branches.single()
            } else {
                buildJsonObject { put("anyOf", JsonArray(branches)) }
            }
        }
        obj["items"] is JsonObject ->
            result["items"] = normalizeDefinition(obj.getValue("items"), isRoot = false)
    }

    // MFJS reads the type off each branch, so a type sitting beside the union has nowhere to apply.
    val parentType = (result["type"] as? JsonPrimitive)?.contentOrNullIfNotString()
    val union = result["anyOf"] as? JsonArray
    if (parentType != null && union != null) {
        result.remove("type")
        result["anyOf"] = buildJsonArray {
            union.forEach { branch ->
                val branchObject = branch as? JsonObject
                add(
                    if (branchObject != null && branchObject["type"] == null) {
                        // The parent's type goes first so a branch that names its own keeps winning.
                        JsonObject(mapOf("type" to JsonPrimitive(parentType)) + branchObject)
                    } else {
                        branch
                    },
                )
            }
        }
    }

    SCHEMA_ARRAY_KEYS.forEach { key ->
        (result[key] as? JsonArray)?.let { array ->
            result[key] = JsonArray(array.map { normalizeDefinition(it, isRoot = false) })
        }
    }
    SCHEMA_MAP_KEYS.forEach { key ->
        (result[key] as? JsonObject)?.let { map ->
            result[key] = JsonObject(map.mapValues { normalizeDefinition(it.value, isRoot = false) })
        }
    }
    SCHEMA_SINGLE_KEYS.forEach { key ->
        result[key]?.let { value ->
            if (value is JsonObject || value is JsonPrimitive) {
                result[key] = normalizeDefinition(value, isRoot = false)
            }
        }
    }

    return JsonObject(result)
}

private fun JsonPrimitive.contentOrNullIfNotString(): String? = takeIf { it.isString }?.content
