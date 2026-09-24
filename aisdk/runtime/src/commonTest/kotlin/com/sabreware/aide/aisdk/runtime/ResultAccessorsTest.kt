package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The typed views over a step and a result. Each is a filter over `content`; what is pinned is which
 * parts each one admits, because the mistakes here are quiet — a dynamic call counted as static, a
 * reasoning file lost from the replay list, an empty assistant turn replayed as a 400.
 */
class ResultAccessorsTest {

    private val signature = mapOf("scripted" to buildJsonObject { put("signature", "sig") })

    private val step = Step(
        content = listOf(
            Content.Reasoning("think ", providerMetadata = signature),
            Content.ReasoningFile("image/png", FileData.Bytes(byteArrayOf(1))),
            Content.Reasoning("harder"),
            Content.Text("answer"),
            Content.File("image/png", FileData.Bytes(byteArrayOf(2))),
            Content.Source.Url("s1", "https://example.com", title = "Example"),
            Content.ToolCall("c1", "calendar", "{}"),
            Content.ToolCall("c2", "mcp_search", "{}", dynamic = true),
            Content.ToolResult("c3", "web_search", ToolOutput.Text("vendor ran this")),
        ),
        finishReason = FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use"),
        usage = Usage(),
        toolResults = listOf(
            ToolPart.Result("c1", "calendar", ToolOutput.Text("free")),
            ToolPart.Result("c2", "mcp_search", ToolOutput.Text("found")),
        ),
        callId = "call_1",
        stepNumber = 0,
    )

    @Test
    fun `reasoning is split into replayable parts and display text`() {
        assertEquals(3, step.reasoningParts.size)
        assertIs<Content.ReasoningFile>(step.reasoningParts[1])
        assertEquals(signature, step.reasoningParts[0].providerMetadata)
        // Joined without a separator, as the reference; files contribute nothing to the text.
        assertEquals("think harder", step.reasoningText)
        assertNull(step.copy(content = listOf(Content.Text("x"))).reasoningText)
    }

    @Test
    fun `files and sources come out by kind`() {
        assertEquals(listOf(byteArrayOf(2).toList()), step.files.map { (it.data as FileData.Bytes).bytes.toList() })
        assertEquals(1, step.reasoningFiles.size)
        assertEquals("https://example.com", (step.sources.single() as Content.Source.Url).url)
    }

    @Test
    fun `calls and results split by whether the tool was offered`() {
        assertEquals(listOf("c1"), step.staticToolCalls.map { it.toolCallId })
        assertEquals(listOf("c2"), step.dynamicToolCalls.map { it.toolCallId })
        assertEquals(listOf("c1"), step.staticToolResults.map { it.toolCallId })
        assertEquals(listOf("c2"), step.dynamicToolResults.map { it.toolCallId })
        // The vendor's own result lives in the content, not in the runtime's results.
        assertEquals(listOf("c3"), step.providerToolResults.map { it.toolCallId })
        assertEquals("tool_use", step.rawFinishReason)
    }

    @Test
    fun `responseMessages is the assistant turn then the tool turn, signature intact`() {
        val messages = step.responseMessages

        assertEquals(2, messages.size)
        val assistant = assertIs<ModelMessage.Assistant>(messages[0])
        assertEquals(signature, (assistant.content.first() as AssistantPart.Reasoning).providerOptions)
        assertIs<ModelMessage.Tool>(messages[1])
        // No empty assistant turn: a text-less, call-less round appends nothing.
        val silent = step.copy(content = emptyList(), toolResults = emptyList())
        assertEquals(emptyList(), silent.responseMessages)
    }

    @Test
    fun `a result aggregates across rounds and points at the last one`() {
        val first = step.copy(stepNumber = 0)
        val last = Step(
            content = listOf(Content.Reasoning("done thinking"), Content.Text("final")),
            finishReason = FinishReason(FinishReason.Unified.Stop, raw = "end_turn"),
            usage = Usage(),
            callId = "call_1",
            stepNumber = 1,
        )
        val result = RunResult(
            callId = "call_1",
            steps = listOf(first, last),
            usage = Usage(),
            finishReason = last.finishReason,
            messages = emptyList(),
        )

        assertEquals(last, result.finalStep)
        assertEquals("end_turn", result.rawFinishReason)
        assertEquals(first.content + last.content, result.content)
        // Reasoning is the LAST round's, like text; everything countable is every round's.
        assertEquals("done thinking", result.reasoningText)
        assertEquals(1, result.reasoningParts.size)
        assertEquals(1, result.files.size)
        assertEquals(1, result.sources.size)
        assertEquals(listOf("c1", "c2"), result.toolCalls.map { it.toolCallId })
        assertEquals(listOf("c1"), result.staticToolCalls.map { it.toolCallId })
        assertEquals(listOf("c2"), result.dynamicToolCalls.map { it.toolCallId })
        assertEquals(2, result.toolResults.size)
        assertEquals(listOf("c1"), result.staticToolResults.map { it.toolCallId })
        assertEquals(listOf("c2"), result.dynamicToolResults.map { it.toolCallId })
        assertEquals(listOf("c3"), result.providerToolResults.map { it.toolCallId })
    }

    @Test
    fun `a result with no rounds has no final step and nothing to aggregate`() {
        val empty = RunResult("call_0", emptyList(), Usage(), FinishReason(FinishReason.Unified.Other), emptyList())

        assertNull(empty.finalStep)
        assertNull(empty.reasoningText)
        assertEquals(emptyList(), empty.content)
        assertEquals(emptyList(), empty.toolCalls)
    }
}
