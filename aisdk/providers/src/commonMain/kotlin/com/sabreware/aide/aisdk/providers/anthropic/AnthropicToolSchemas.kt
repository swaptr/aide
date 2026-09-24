package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.JsonSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The input and output shapes of Anthropic's own tools, as JSON Schema.
 *
 * Ported from the zod schemas in the reference's `tool/` directory and rendered the way its `zodSchema`
 * helper renders them — draft-7, `anyOf` for a union and for a nullable, `const` for a literal,
 * `additionalProperties: false` on every object — so a caller comparing the two documents sees the same
 * one. A tuple is the draft-7 spelling (`items` as a list, bounded by `minItems`/`maxItems`).
 *
 * Nothing here is validated against: a schema is cargo in this library, and narrowing one is how a field
 * the vendor adds next month becomes a validation failure of ours. They exist so a runtime can check a
 * call the model made against what the vendor promised, and so a consumer decoding a result knows what
 * to expect. The wire body of the tool ENTRY is a different thing entirely and is built by
 * [prepareAnthropicTools] from the argument spellings on [AnthropicProviderTool].
 *
 * Field names are the reference's, which are the vendor's camelCase where the reference re-spells a
 * result (`pageAge`, `encryptedContent`, `retrievedAt`, `toolName`) and the vendor's snake_case where it
 * does not (`return_code`, `file_id`, `is_file_update`). Kept as-is: this is the shape the reference's
 * consumers were written against, and the decode step is where the wire spelling is reconciled.
 */
internal object AnthropicToolSchemas {

    // --- the vocabulary the zod schemas use -----------------------------------------------------

    private class ObjectShape {
        val properties = linkedMapOf<String, JsonObject>()
        val required = mutableListOf<String>()

        fun req(name: String, schema: JsonObject) {
            properties[name] = schema
            required += name
        }

        fun opt(name: String, schema: JsonObject) {
            properties[name] = schema
        }
    }

    private fun obj(shape: ObjectShape.() -> Unit): JsonObject {
        val built = ObjectShape().apply(shape)
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(built.properties))
            if (built.required.isNotEmpty()) put("required", JsonArray(built.required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
    }

    private fun str(): JsonObject = buildJsonObject { put("type", "string") }
    private fun int(): JsonObject = buildJsonObject { put("type", "integer") }
    private fun num(): JsonObject = buildJsonObject { put("type", "number") }
    private fun bool(): JsonObject = buildJsonObject { put("type", "boolean") }

    private fun literal(value: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("const", value)
    }

    private fun enumOf(values: List<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun arrayOf(items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun tuple(vararg items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", JsonArray(items.toList()))
        put("minItems", items.size)
        put("maxItems", items.size)
    }

    private fun anyOf(schemas: List<JsonObject>): JsonObject = buildJsonObject { put("anyOf", JsonArray(schemas)) }
    private fun anyOf(vararg schemas: JsonObject): JsonObject = anyOf(schemas.toList())
    private fun nullable(schema: JsonObject): JsonObject = anyOf(schema, buildJsonObject { put("type", "null") })
    private fun JsonObject.withDefault(value: JsonElement): JsonObject = JsonObject(this + ("default" to value))

    // --- advisor --------------------------------------------------------------------------------

    /**
     * `advisor_20260301` takes no input: the executor emits a `server_tool_use` with an empty object and
     * the server assembles the advisor's view from the transcript itself.
     */
    val advisorInput: JsonSchema = obj { }

    /** Advice in the clear, advice the server encrypted (round-trip it verbatim), or why there is none. */
    val advisorOutput: JsonSchema = anyOf(
        obj {
            req("type", literal("advisor_result"))
            req("text", str())
            opt("stopReason", str())
        },
        obj {
            req("type", literal("advisor_redacted_result"))
            req("encryptedContent", str())
            opt("stopReason", str())
        },
        obj {
            req("type", literal("advisor_tool_result_error"))
            req("errorCode", str())
        },
    )

    // --- bash -----------------------------------------------------------------------------------

    /** A command to run, or `restart: true` to reset the session instead. Both bash versions share it. */
    val bashInput: JsonSchema = obj {
        req("command", str())
        opt("restart", bool())
    }

    // --- code execution -------------------------------------------------------------------------

    /** The Python-only `code_execution_20250522`: the model sends the code and nothing else. */
    val pythonCodeExecutionInput: JsonSchema = obj { req("code", str()) }

    val pythonCodeExecutionOutput: JsonSchema = codeExecutionResult()

    /**
     * `code_execution_20250825` and later: one entry serves Python (via programmatic tool calling), bash,
     * and a three-command file editor, so the input is a union discriminated on `type` — and the editor
     * arm is a union of its own, discriminated on `command`, exactly as the reference nests them.
     */
    val codeExecutionInput: JsonSchema = anyOf(
        obj {
            req("type", literal("programmatic-tool-call"))
            req("code", str())
        },
        obj {
            req("type", literal("bash_code_execution"))
            req("command", str())
        },
        anyOf(
            obj {
                req("type", literal("text_editor_code_execution"))
                req("command", literal("view"))
                req("path", str())
            },
            obj {
                req("type", literal("text_editor_code_execution"))
                req("command", literal("create"))
                req("path", str())
                opt("file_text", nullable(str()))
            },
            obj {
                req("type", literal("text_editor_code_execution"))
                req("command", literal("str_replace"))
                req("path", str())
                req("old_str", str())
                req("new_str", str())
            },
        ),
    )

    /**
     * Every result block a `code_execution_20250825`-or-later call can answer with.
     *
     * [encryptedResults] adds the `encrypted_code_execution_result` arm that `code_execution_20260120`
     * introduced: stdout the server encrypted so it can be replayed without being read.
     */
    fun codeExecutionOutput(encryptedResults: Boolean): JsonSchema = anyOf(
        listOfNotNull(
            codeExecutionResult(),
            if (encryptedResults) {
                obj {
                    req("type", literal("encrypted_code_execution_result"))
                    req("encrypted_stdout", str())
                    req("stderr", str())
                    req("return_code", num())
                    opt("content", outputFiles("code_execution_output").withDefault(JsonArray(emptyList())))
                }
            } else {
                null
            },
            obj {
                req("type", literal("bash_code_execution_result"))
                req("content", outputFiles("bash_code_execution_output"))
                req("stdout", str())
                req("stderr", str())
                req("return_code", num())
            },
            obj {
                req("type", literal("bash_code_execution_tool_result_error"))
                req("error_code", str())
            },
            obj {
                req("type", literal("text_editor_code_execution_tool_result_error"))
                req("error_code", str())
            },
            obj {
                req("type", literal("text_editor_code_execution_view_result"))
                req("content", str())
                req("file_type", str())
                req("num_lines", nullable(num()))
                req("start_line", nullable(num()))
                req("total_lines", nullable(num()))
            },
            obj {
                req("type", literal("text_editor_code_execution_create_result"))
                req("is_file_update", bool())
            },
            obj {
                req("type", literal("text_editor_code_execution_str_replace_result"))
                req("lines", nullable(arrayOf(str())))
                req("new_lines", nullable(num()))
                req("new_start", nullable(num()))
                req("old_lines", nullable(num()))
                req("old_start", nullable(num()))
            },
        ),
    )

    private fun codeExecutionResult(): JsonObject = obj {
        req("type", literal("code_execution_result"))
        req("stdout", str())
        req("stderr", str())
        req("return_code", num())
        opt("content", outputFiles("code_execution_output").withDefault(JsonArray(emptyList())))
    }

    private fun outputFiles(itemType: String): JsonObject = arrayOf(
        obj {
            req("type", literal(itemType))
            req("file_id", str())
        },
    )

    // --- computer use ---------------------------------------------------------------------------

    /** `computer_20241022`: the original ten actions, with the coordinate as a free-form integer list. */
    val computerInput20241022: JsonSchema = obj {
        req(
            "action",
            enumOf(
                listOf(
                    "key", "type", "mouse_move", "left_click", "left_click_drag", "right_click",
                    "middle_click", "double_click", "screenshot", "cursor_position",
                ),
            ),
        )
        opt("coordinate", arrayOf(int()))
        opt("text", str())
    }

    /** `computer_20250124`: sixteen actions, typed coordinate pairs, scrolling, holding and waiting. */
    val computerInput20250124: JsonSchema = computer(zoom = false)

    /** `computer_20251124`: the same plus `zoom`, which takes a `region` of four integers. */
    val computerInput20251124: JsonSchema = computer(zoom = true)

    private fun computer(zoom: Boolean): JsonObject = obj {
        req(
            "action",
            enumOf(
                listOf(
                    "key", "hold_key", "type", "cursor_position", "mouse_move", "left_mouse_down", "left_mouse_up",
                    "left_click", "left_click_drag", "right_click", "middle_click", "double_click", "triple_click",
                    "scroll", "wait", "screenshot",
                ) + listOfNotNull("zoom".takeIf { zoom }),
            ),
        )
        opt("coordinate", tuple(int(), int()))
        opt("duration", num())
        if (zoom) opt("region", tuple(int(), int(), int(), int()))
        opt("scroll_amount", num())
        opt("scroll_direction", enumOf(listOf("up", "down", "left", "right")))
        opt("start_coordinate", tuple(int(), int()))
        opt("text", str())
    }

    // --- memory ---------------------------------------------------------------------------------

    /** Six file commands over the memory directory, discriminated on `command`. */
    val memoryInput: JsonSchema = anyOf(
        obj {
            req("command", literal("view"))
            req("path", str())
            opt("view_range", tuple(num(), num()))
        },
        obj {
            req("command", literal("create"))
            req("path", str())
            req("file_text", str())
        },
        obj {
            req("command", literal("str_replace"))
            req("path", str())
            req("old_str", str())
            req("new_str", str())
        },
        obj {
            req("command", literal("insert"))
            req("path", str())
            req("insert_line", num())
            req("insert_text", str())
        },
        obj {
            req("command", literal("delete"))
            req("path", str())
        },
        obj {
            req("command", literal("rename"))
            req("old_path", str())
            req("new_path", str())
        },
    )

    // --- text editor ----------------------------------------------------------------------------

    /** `text_editor_20241022` and `_20250124`: five commands, `undo_edit` among them. */
    val textEditorInputWithUndo: JsonSchema = textEditor(undoEdit = true)

    /** `text_editor_20250429` and `_20250728`: Claude 4 dropped `undo_edit`, so four commands. */
    val textEditorInput: JsonSchema = textEditor(undoEdit = false)

    private fun textEditor(undoEdit: Boolean): JsonObject = obj {
        req(
            "command",
            enumOf(listOf("view", "create", "str_replace", "insert") + listOfNotNull("undo_edit".takeIf { undoEdit })),
        )
        req("path", str())
        opt("file_text", str())
        opt("insert_line", int())
        opt("new_str", str())
        opt("insert_text", str())
        opt("old_str", str())
        opt("view_range", arrayOf(int()))
    }

    // --- tool search ----------------------------------------------------------------------------

    /** A Python `re.search` pattern, at most 200 characters, plus an optional cap on matches. */
    val toolSearchRegexInput: JsonSchema = obj {
        req("pattern", str())
        opt("limit", num())
    }

    /** A natural-language query for BM25 ranking, plus an optional cap on matches. */
    val toolSearchBm25Input: JsonSchema = obj {
        req("query", str())
        opt("limit", num())
    }

    /** References the API expands into full definitions; the name is all a client sees. */
    val toolSearchOutput: JsonSchema = arrayOf(
        obj {
            req("type", literal("tool_reference"))
            req("toolName", str())
        },
    )

    // --- web fetch ------------------------------------------------------------------------------

    val webFetchInput: JsonSchema = obj { req("url", str()) }

    /** The fetched page as a document: text in the clear, or a PDF as base64. Shared by every version. */
    val webFetchOutput: JsonSchema = obj {
        req("type", literal("web_fetch_result"))
        req("url", str())
        req(
            "content",
            obj {
                req("type", literal("document"))
                req("title", nullable(str()))
                opt("citations", obj { req("enabled", bool()) })
                req(
                    "source",
                    anyOf(
                        obj {
                            req("type", literal("base64"))
                            req("mediaType", literal("application/pdf"))
                            req("data", str())
                        },
                        obj {
                            req("type", literal("text"))
                            req("mediaType", literal("text/plain"))
                            req("data", str())
                        },
                    ),
                )
            },
        )
        req("retrievedAt", nullable(str()))
    }

    // --- web search -----------------------------------------------------------------------------

    val webSearchInput: JsonSchema = obj { req("query", str()) }

    /** One entry per hit. `encryptedContent` has to go back verbatim for citations to survive a turn. */
    val webSearchOutput: JsonSchema = arrayOf(
        obj {
            req("url", str())
            req("title", nullable(str()))
            req("pageAge", nullable(str()))
            req("encryptedContent", str())
            req("type", literal("web_search_result"))
        },
    )

    // --- toolsets -------------------------------------------------------------------------------

    /**
     * A toolset member's, or an MCP tool's, own parameters.
     *
     * Deliberately open. A client toolset is one entry standing for a fixed set of members whose
     * schemas the dated `type` fixes and the vendor does not publish as JSON Schema, and an MCP tool's
     * input is whatever that server declared. Restating either here would be inventing it.
     */
    val memberInput: JsonSchema = buildJsonObject { put("type", "object") }

    /** An `mcp_tool_result`: text blocks or a bare string, with `is_error` when the server refused. */
    val mcpToolsetOutput: JsonSchema = obj {
        opt("is_error", bool())
        req(
            "content",
            anyOf(
                str(),
                arrayOf(
                    obj {
                        req("type", literal("text"))
                        req("text", str())
                    },
                ),
            ),
        )
    }
}
