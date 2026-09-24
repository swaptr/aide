package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.InvalidToolInputError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.NoSuchToolError
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolCallRepairError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull

/** A call that survived validation, or the reason it did not. */
internal class ValidatedToolCall(val call: Content.ToolCall, val error: Throwable?)

/**
 * Decides whether a tool call may be executed, and gives a repair hook one chance to save one that may
 * not.
 *
 * A call arrives from the model as a name and a string, and three things can be wrong with it: the name
 * matches no tool it was offered, the string is not JSON, or the JSON omits something the tool requires.
 * Each is a distinct thing a caller can act on, which is why they are distinct errors and not one
 * "bad call".
 *
 * **Nothing here throws.** Every failure — including one the repair hook caused — comes back as a call
 * flagged [Content.ToolCall.invalid] carrying its reason. The loop replays it, so the turn stays
 * well-formed, and never runs it. A truncated `tool_use` block yields a partial argument string that
 * still parses often enough to be dangerous, and AIDE's device toolsets include write operations.
 */
internal suspend fun validateToolCall(
    call: Content.ToolCall,
    tools: List<Tool>?,
    repair: ToolCallRepair?,
): ValidatedToolCall {
    val firstError = try {
        return ValidatedToolCall(checkToolCall(call, tools), null)
    } catch (e: NoSuchToolError) {
        e
    } catch (e: InvalidToolInputError) {
        e
    }

    if (repair == null) return ValidatedToolCall(call.copy(invalid = true), firstError)

    val repaired = try {
        repair.repair(call, firstError)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        return ValidatedToolCall(
            call.copy(invalid = true),
            ToolCallRepairError(originalError = firstError, cause = e),
        )
    } ?: return ValidatedToolCall(call.copy(invalid = true), firstError)

    // One attempt only. A hook that keeps producing calls that fail validation would otherwise loop.
    return try {
        ValidatedToolCall(checkToolCall(repaired, tools), null)
    } catch (e: NoSuchToolError) {
        ValidatedToolCall(repaired.copy(invalid = true), e)
    } catch (e: InvalidToolInputError) {
        ValidatedToolCall(repaired.copy(invalid = true), e)
    }
}

/**
 * @return the call with its input normalised, or throws the reason it is not executable.
 *
 * A null [tools] is not "no tools exist" — it is "this call declared none", which a runtime driving tools
 * out of band does routinely. There is no set to be absent from, so the name check is skipped and the
 * input is still parsed. The reference is stricter here only because its `tools` is never absent; ours
 * rejecting every call in that case would break a caller that never made a mistake.
 */
private fun checkToolCall(call: Content.ToolCall, tools: List<Tool>?): Content.ToolCall {
    val tool = tools?.firstOrNull { it.name == call.toolName }

    if (tool == null) {
        // A provider-executed dynamic tool was never in our list and never will be: the vendor owns it,
        // and the runtime only has to be able to parse what it reports.
        if (tools == null || (call.providerExecuted && call.dynamic)) {
            return call.copy(input = call.parsedInput().toString())
        }
        throw NoSuchToolError(toolName = call.toolName, availableTools = tools.map { it.name })
    }

    val input = call.parsedInput()
    // A provider-defined tool's `args` is the VENDOR's configuration blob, not a schema for the model's
    // arguments, so there is nothing here to check them against.
    if (tool is Tool.Function) input.requireAll(tool.inputSchema.requiredProperties(), call)
    return call.copy(input = input.toString())
}

/**
 * The call's input as JSON.
 *
 * Blank becomes `{}` before anything else looks at it. Most models emit an empty string for a tool that
 * takes no arguments, and treating that as a parse failure fires the repair hook on a call that was
 * correct.
 */
private fun Content.ToolCall.parsedInput(): JsonObject {
    val text = input.trim().ifEmpty { "{}" }
    val parsed = parseJsonElementOrNull(text)
        ?: throw InvalidToolInputError(toolName, input, cause = JsonParseError(input))
    return parsed as? JsonObject
        ?: throw InvalidToolInputError(
            toolName,
            input,
            cause = JsonParseError(input, IllegalArgumentException("Tool input must be a JSON object.")),
        )
}

/**
 * The schema's top-level `required` names, and nothing else.
 *
 * This is deliberately not a JSON Schema validator. Presence of a required property is the one check that
 * is sound without interpreting the schema — no keyword can make a listed name optional — and it is
 * exactly the check that catches the failure that matters: a `tool_use` block truncated at `length`
 * leaves a prefix of the arguments that still parses. Per-property type checking would make this file a
 * schema interpreter, and one that drifts from whatever the vendor's own constrained decoder does.
 */
private fun JsonObject.requiredProperties(): List<String> =
    this["required"]?.let { required ->
        runCatching { required.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
    }.orEmpty()

private fun JsonObject.requireAll(required: List<String>, call: Content.ToolCall) {
    val missing = required.filterNot { it in keys }
    if (missing.isEmpty()) return
    throw InvalidToolInputError(
        toolName = call.toolName,
        toolInput = call.input,
        message = "Invalid input for tool ${call.toolName}: missing required " +
            "propert${if (missing.size == 1) "y" else "ies"} ${missing.joinToString()}.",
    )
}
