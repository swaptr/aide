package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Closes whatever a truncated JSON document left open, so the prefix parses.
 *
 * A structured answer arrives a character at a time, and until the model emits the final brace there is
 * nothing a JSON parser will accept — which is why structured output normally cannot be rendered until it
 * is complete, and why a response cut short by a token cap is a total loss rather than a partial answer.
 * This is the fix for both: one linear scan tracking what is open, then the matching closers appended.
 *
 * The scan tracks the last index at which the input was still a valid prefix and truncates there, so a
 * half-written key, a dangling comma or a number stopped at its exponent is dropped rather than closed
 * into something that parses to the wrong value. A partially typed `t`, `tr`, `tru` is completed to
 * `true`, because those three are only ever a prefix of one literal.
 *
 * Genuinely invalid JSON is not the target and is left to fail: the result goes to a real parser, which
 * is a better judge of malformed input than a repair pass guessing at intent.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
public fun fixJson(input: String): String {
    val stack = ArrayDeque(listOf(State.Root))
    var lastValidIndex = -1
    var literalStart = -1
    var unicodeEscapeDigits = 0

    fun processValueStart(char: Char, index: Int, swapState: State) {
        when (char) {
            '"' -> {
                lastValidIndex = index
                stack.removeLast()
                stack.addLast(swapState)
                stack.addLast(State.InsideString)
            }
            'f', 't', 'n' -> {
                lastValidIndex = index
                literalStart = index
                stack.removeLast()
                stack.addLast(swapState)
                stack.addLast(State.InsideLiteral)
            }
            '-' -> {
                stack.removeLast()
                stack.addLast(swapState)
                stack.addLast(State.InsideNumber)
            }
            in '0'..'9' -> {
                lastValidIndex = index
                stack.removeLast()
                stack.addLast(swapState)
                stack.addLast(State.InsideNumber)
            }
            '{' -> {
                lastValidIndex = index
                stack.removeLast()
                stack.addLast(swapState)
                stack.addLast(State.InsideObjectStart)
            }
            '[' -> {
                lastValidIndex = index
                stack.removeLast()
                stack.addLast(swapState)
                stack.addLast(State.InsideArrayStart)
            }
        }
    }

    fun processAfterObjectValue(char: Char, index: Int) {
        when (char) {
            ',' -> {
                stack.removeLast()
                stack.addLast(State.InsideObjectAfterComma)
            }
            '}' -> {
                lastValidIndex = index
                stack.removeLast()
            }
        }
    }

    fun processAfterArrayValue(char: Char, index: Int) {
        when (char) {
            ',' -> {
                stack.removeLast()
                stack.addLast(State.InsideArrayAfterComma)
            }
            ']' -> {
                lastValidIndex = index
                stack.removeLast()
            }
        }
    }

    for ((index, char) in input.withIndex()) {
        when (stack.last()) {
            State.Root -> processValueStart(char, index, State.Finish)

            State.InsideObjectStart -> when (char) {
                '"' -> {
                    stack.removeLast()
                    stack.addLast(State.InsideObjectKey)
                }
                '}' -> {
                    lastValidIndex = index
                    stack.removeLast()
                }
            }

            State.InsideObjectAfterComma -> if (char == '"') {
                stack.removeLast()
                stack.addLast(State.InsideObjectKey)
            }

            State.InsideObjectKey -> if (char == '"') {
                stack.removeLast()
                stack.addLast(State.InsideObjectAfterKey)
            }

            State.InsideObjectAfterKey -> if (char == ':') {
                stack.removeLast()
                stack.addLast(State.InsideObjectBeforeValue)
            }

            State.InsideObjectBeforeValue ->
                processValueStart(char, index, State.InsideObjectAfterValue)

            State.InsideObjectAfterValue -> processAfterObjectValue(char, index)

            State.InsideString -> when (char) {
                '"' -> {
                    stack.removeLast()
                    lastValidIndex = index
                }
                '\\' -> stack.addLast(State.InsideStringEscape)
                else -> lastValidIndex = index
            }

            State.InsideArrayStart -> if (char == ']') {
                lastValidIndex = index
                stack.removeLast()
            } else {
                lastValidIndex = index
                processValueStart(char, index, State.InsideArrayAfterValue)
            }

            State.InsideArrayAfterValue -> when (char) {
                ',' -> {
                    stack.removeLast()
                    stack.addLast(State.InsideArrayAfterComma)
                }
                ']' -> {
                    lastValidIndex = index
                    stack.removeLast()
                }
                else -> lastValidIndex = index
            }

            State.InsideArrayAfterComma ->
                processValueStart(char, index, State.InsideArrayAfterValue)

            State.InsideStringEscape -> {
                stack.removeLast()
                if (char == 'u') {
                    unicodeEscapeDigits = 0
                    stack.addLast(State.InsideStringUnicodeEscape)
                } else {
                    lastValidIndex = index
                }
            }

            // A half-written `\uD8` closed with a quote is an invalid escape, not a shorter string, so
            // the index only advances once all four digits are in.
            State.InsideStringUnicodeEscape -> if (char.isHexDigit()) {
                unicodeEscapeDigits++
                if (unicodeEscapeDigits == HEX_DIGITS_PER_ESCAPE) {
                    stack.removeLast()
                    lastValidIndex = index
                }
            }

            State.InsideNumber -> when (char) {
                // A number is only valid where it has a digit: `1.` and `1e` are prefixes, not values.
                in '0'..'9' -> lastValidIndex = index
                'e', 'E', '-', '.' -> Unit
                ',' -> {
                    stack.removeLast()
                    when (stack.last()) {
                        State.InsideArrayAfterValue -> processAfterArrayValue(char, index)
                        State.InsideObjectAfterValue -> processAfterObjectValue(char, index)
                        else -> Unit
                    }
                }
                '}' -> {
                    stack.removeLast()
                    if (stack.last() == State.InsideObjectAfterValue) processAfterObjectValue(char, index)
                }
                ']' -> {
                    stack.removeLast()
                    if (stack.last() == State.InsideArrayAfterValue) processAfterArrayValue(char, index)
                }
                else -> stack.removeLast()
            }

            State.InsideLiteral -> {
                val partial = input.substring(literalStart, index + 1)
                if (LITERALS.none { it.startsWith(partial) }) {
                    stack.removeLast()
                    when (stack.last()) {
                        State.InsideObjectAfterValue -> processAfterObjectValue(char, index)
                        State.InsideArrayAfterValue -> processAfterArrayValue(char, index)
                        else -> Unit
                    }
                } else {
                    lastValidIndex = index
                }
            }

            State.Finish -> Unit
        }
    }

    return buildString {
        append(input, 0, lastValidIndex + 1)
        for (state in stack.reversed()) {
            when (state) {
                State.InsideString -> append('"')
                State.InsideObjectKey,
                State.InsideObjectAfterKey,
                State.InsideObjectAfterComma,
                State.InsideObjectStart,
                State.InsideObjectBeforeValue,
                State.InsideObjectAfterValue,
                -> append('}')
                State.InsideArrayStart,
                State.InsideArrayAfterComma,
                State.InsideArrayAfterValue,
                -> append(']')
                State.InsideLiteral -> {
                    val partial = input.substring(literalStart)
                    LITERALS.firstOrNull { it.startsWith(partial) }?.let { append(it.substring(partial.length)) }
                }
                else -> Unit
            }
        }
    }
}

/** How a partial parse turned out; see [parsePartialJson]. */
public enum class PartialJsonState {
    /** The text parsed as it stood — the document is complete. */
    Parsed,

    /** The text was a truncated prefix, and [fixJson] closed it. */
    Repaired,

    /** Not JSON, even as a prefix. */
    Failed,
}

/** A parse attempt over a possibly-incomplete document: the value if one parsed, and how it did. */
public data class PartialJson(val value: JsonElement?, val state: PartialJsonState)

/**
 * Parses JSON that may still be arriving.
 *
 * The complete document is tried first, so a finished response never pays for the repair pass and never
 * risks it: [fixJson] on already-valid input is a no-op, but a caller that cannot tell a repaired parse
 * from a real one cannot tell a truncated answer from a complete one either, which is what
 * [PartialJsonState] is for.
 */
public fun parsePartialJson(text: String?): PartialJson {
    if (text == null) return PartialJson(null, PartialJsonState.Failed)
    parseJsonValueOrNull(text)?.let { return PartialJson(it, PartialJsonState.Parsed) }
    parseJsonValueOrNull(fixJson(text))?.let { return PartialJson(it, PartialJsonState.Repaired) }
    return PartialJson(null, PartialJsonState.Failed)
}

/**
 * A parse that rejects what `JSON.parse` would.
 *
 * kotlinx reads a bare top-level token as an unquoted primitive even with `isLenient` off, so prose
 * parses: *"no results found"* comes back as the string `no`, and a model that narrated instead of
 * answering would be reported as having produced a value. The literals and numbers JSON does allow
 * unquoted are let through; everything else is what it looks like, which is not JSON.
 */
internal fun parseJsonValueOrNull(text: String): JsonElement? {
    val parsed = parseJsonElementOrNull(text) ?: return null
    val primitive = parsed as? JsonPrimitive ?: return parsed
    if (primitive.isString) return parsed
    return parsed.takeIf { primitive.content in LITERALS || primitive.content.toDoubleOrNull() != null }
}

private const val HEX_DIGITS_PER_ESCAPE = 4

private val LITERALS = listOf("true", "false", "null")

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** The JSON grammar positions [fixJson] tracks, one per place a document can be cut off. */
private enum class State {
    Root,
    Finish,
    InsideString,
    InsideStringEscape,
    InsideStringUnicodeEscape,
    InsideLiteral,
    InsideNumber,
    InsideObjectStart,
    InsideObjectKey,
    InsideObjectAfterKey,
    InsideObjectBeforeValue,
    InsideObjectAfterValue,
    InsideObjectAfterComma,
    InsideArrayStart,
    InsideArrayAfterValue,
    InsideArrayAfterComma,
}
