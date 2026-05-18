package com.swaptr.aide.data.chat

import android.util.Base64
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role

fun AideMessage.toLiteRt(): Message {
    // Strip UI-only ToolCall parts; LiteRT has no Content equivalent (lives on Message.toolCalls).
    val wireParts = parts.mapNotNull { it.toLiteRtOrNull() }
    val contents = Contents.of(wireParts)
    return when (role) {
        AideRole.User -> Message.user(contents)
        AideRole.Model -> Message.model(contents)
        AideRole.System -> Message.system(contents)
        AideRole.Tool -> Message.tool(contents)
    }
}

fun AidePart.toLiteRtOrNull(): Content? = when (this) {
    is AidePart.Text -> Content.Text(text)
    is AidePart.ImageBytes -> Content.ImageBytes(Base64.decode(bytesBase64, Base64.NO_WRAP))
    is AidePart.ImageFile -> Content.ImageFile(path)
    is AidePart.AudioBytes -> Content.AudioBytes(Base64.decode(bytesBase64, Base64.NO_WRAP))
    is AidePart.AudioFile -> Content.AudioFile(path)
    is AidePart.ToolResponse -> Content.ToolResponse(name, json)
    is AidePart.ToolCall -> null
    // Reasoning trace is UI-only; replaying it would let Gemma see (and continue) its own
    // scratchpad on the next turn.
    is AidePart.Thinking -> null
}

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
