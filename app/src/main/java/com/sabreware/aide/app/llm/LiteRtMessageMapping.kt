package com.sabreware.aide.app.llm
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.llm.toAnyMap

import android.util.Base64
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val LiteRtJson = Json { ignoreUnknownKeys = true }

fun AideMessage.toLiteRt(): Message {
    // Strip UI-only ToolCall parts from the Content list; LiteRT has no Content equivalent.
    val contents = Contents.of(parts.mapNotNull { it.toLiteRtOrNull() })
    return when (role) {
        AideRole.User -> Message.user(contents)
        // Replay tool calls on Message.toolCalls (where LiteRT carries them, NOT as a Content) so the
        // following tool Message's ToolResponse pairs with a real call instead of being orphaned (A2).
        AideRole.Model -> Message.model(contents, parts.toLiteRtToolCalls())
        AideRole.System -> Message.system(contents)
        AideRole.Tool -> Message.tool(contents)
    }
}

fun AidePart.toLiteRtOrNull(): Content? = when (this) {
    is AidePart.Text -> Content.Text(text)
    is AidePart.ImageBytes -> Content.ImageBytes(Base64.decode(bytesBase64, Base64.NO_WRAP))
    is AidePart.ImageFile -> Content.ImageFile(path)
    is AidePart.AudioFile -> Content.AudioFile(path)
    // Parse the stored result JSON into a Map so the on-device model sees the SAME structured value it
    // saw live (LiteRtLmChatSession passes envelope.toAnyMap()). Passing the raw String re-serialized it
    // as a quoted JSON literal on reload, silently changing history across a restart (A1).
    is AidePart.ToolResponse -> Content.ToolResponse(name, parseResponsePayload(json))
    is AidePart.ToolCall -> null // replayed via Message.toolCalls, not as a Content
    // Reasoning trace is UI-only; replaying it would let Gemma see (and continue) its own scratchpad on
    // the next turn. That covers signed and redacted blocks alike — a remote provider's encrypted payload
    // is meaningless to a local engine either way.
    is AidePart.Thinking -> null
    // Native binary documents (PDF) are gated OFF for local models at attach (documentIn=false), so this
    // part can only appear replaying history recorded against a remote model — skip, don't crash.
    is AidePart.DocumentFile -> null
}

private fun List<AidePart>.toLiteRtToolCalls(): List<ToolCall> =
    filterIsInstance<AidePart.ToolCall>().map { ToolCall(it.name, parseToMap(it.argsJson)) }

// JsonObject on success; the raw string on a parse miss (never crash a reload).
private fun parseResponsePayload(json: String): Any =
    runCatching { LiteRtJson.parseToJsonElement(json).jsonObject.toAnyMap() }.getOrDefault(json)

private fun parseToMap(json: String): Map<String, Any?> =
    runCatching { LiteRtJson.parseToJsonElement(json).jsonObject.toAnyMap() }.getOrDefault(emptyMap())

fun Message.textContent(): String {
    val parts = contents.contents
    if (parts.isEmpty()) return ""
    return parts.filterIsInstance<Content.Text>().joinToString("") { it.text }
}

fun Role.toAideRole(): AideRole = when (this) {
    Role.USER -> AideRole.User
    Role.MODEL -> AideRole.Model
    Role.SYSTEM -> AideRole.System
    Role.TOOL -> AideRole.Tool
}
