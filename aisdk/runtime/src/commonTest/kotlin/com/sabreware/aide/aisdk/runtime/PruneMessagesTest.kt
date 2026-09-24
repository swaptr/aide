package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compaction primitive, tested for the property that makes it safe to run mid-conversation:
 * a call and its result leave together or stay together, never half of each.
 */
class PruneMessagesTest {

    private fun user(text: String) = ModelMessage.User(listOf(UserPart.Text(text)))

    private fun assistantRound(callId: String, tool: String, reasoning: String? = null) =
        ModelMessage.Assistant(
            buildList {
                reasoning?.let { add(AssistantPart.Reasoning(it)) }
                add(AssistantPart.Text("calling $tool"))
                add(AssistantPart.ToolCall(callId, tool, "{}"))
            },
        )

    private fun toolRound(callId: String, tool: String) = ModelMessage.Tool(
        listOf(ToolPart.Result(callId, tool, ToolOutput.Text("done"))),
    )

    private val conversation: Prompt = listOf(
        user("find and fix"),
        assistantRound("c1", "search", reasoning = "Look first."),
        toolRound("c1", "search"),
        assistantRound("c2", "edit", reasoning = "Now edit."),
        toolRound("c2", "edit"),
        ModelMessage.Assistant(listOf(AssistantPart.Text("all done"))),
    )

    @Test
    fun `reasoning All strips every assistant turn`() {
        val pruned = pruneMessages(conversation, reasoning = PruneReasoning.All)

        assertTrue(
            pruned.filterIsInstance<ModelMessage.Assistant>()
                .flatMap { it.content }
                .none { it is AssistantPart.Reasoning },
        )
        // Nothing else moved: same message count, calls and results intact.
        assertEquals(conversation.size, pruned.size)
    }

    @Test
    fun `reasoning BeforeLastMessage spares the newest assistant turn`() {
        // A conversation that ENDS with an assistant turn — what post-run compaction sees. The
        // mid-run shape, which ends with a tool turn, is the test below.
        val midRound = conversation.dropLast(1) +
            ModelMessage.Assistant(
                listOf(AssistantPart.Reasoning("still thinking"), AssistantPart.Text("…")),
            )

        val pruned = pruneMessages(midRound, reasoning = PruneReasoning.BeforeLastMessage)

        val last = pruned.last() as ModelMessage.Assistant
        assertTrue(last.content.any { it is AssistantPart.Reasoning })
        assertTrue(
            pruned.dropLast(1).filterIsInstance<ModelMessage.Assistant>()
                .flatMap { it.content }
                .none { it is AssistantPart.Reasoning },
        )
    }

    @Test
    fun `mid-run, the signed block behind the tool turn survives BeforeLastMessage`() {
        // The shape the LOOP actually produces mid-round: the assistant turn, then the tool turn that
        // answers it. A positional "last message" rule exempts the tool turn — which holds no
        // reasoning — and strips the signature Anthropic requires beside that tool_result.
        val midRound: Prompt = listOf(
            user("find and fix"),
            assistantRound("c1", "search", reasoning = "Old thinking."),
            toolRound("c1", "search"),
            assistantRound("c2", "edit", reasoning = "Signed, and the round is still open."),
            toolRound("c2", "edit"),
        )

        val pruned = pruneMessages(midRound, reasoning = PruneReasoning.BeforeLastMessage)

        val newestAssistant = pruned.filterIsInstance<ModelMessage.Assistant>().last()
        assertEquals(
            "Signed, and the round is still open.",
            (newestAssistant.content.first() as AssistantPart.Reasoning).text,
        )
        // And the older round's reasoning is gone, which is the point of pruning at all.
        val older = pruned.filterIsInstance<ModelMessage.Assistant>().first()
        assertTrue(older.content.none { it is AssistantPart.Reasoning })
    }

    @Test
    fun `an approval exchange is pruned with the call it gates, never half of it`() {
        val gated: Prompt = listOf(
            user("write it"),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("c1", "write", "{}"),
                    AssistantPart.ApprovalRequest("apr_1", "c1"),
                ),
            ),
            ModelMessage.Tool(
                listOf(
                    ToolPart.ApprovalResponse("apr_1", approved = true),
                    ToolPart.Result("c1", "write", ToolOutput.Text("wrote")),
                ),
            ),
            ModelMessage.Assistant(listOf(AssistantPart.Text("done"))),
        )

        val pruned = pruneMessages(gated, toolCalls = listOf(PruneToolCalls()))

        // Request, response, call and result all go together: leaving an approval about a call that
        // is no longer there hands the vendor an orphan.
        assertTrue(
            pruned.flatMap { message ->
                when (message) {
                    is ModelMessage.Assistant -> message.content
                    is ModelMessage.Tool -> message.content
                    else -> emptyList()
                }
            }.none { it is AssistantPart.ApprovalRequest || it is ToolPart.ApprovalResponse },
        )
    }

    @Test
    fun `a tools-scoped rule prunes the approval response of the tool it names`() {
        val gated: Prompt = listOf(
            user("write and read"),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("c1", "write", "{}"),
                    AssistantPart.ApprovalRequest("apr_1", "c1"),
                ),
            ),
            ModelMessage.Tool(
                listOf(
                    ToolPart.ApprovalResponse("apr_1", approved = true),
                    ToolPart.Result("c1", "write", ToolOutput.Text("wrote")),
                ),
            ),
            assistantRound("c2", "read"),
            toolRound("c2", "read"),
        )

        val pruned = pruneMessages(gated, toolCalls = listOf(PruneToolCalls(tools = listOf("write"))))

        // Resolved response -> request -> call -> tool name, across messages. Before that resolution
        // existed, a tools-scoped rule kept every response and orphaned this one.
        assertTrue(
            pruned.filterIsInstance<ModelMessage.Tool>()
                .flatMap { it.content }
                .none { it is ToolPart.ApprovalResponse },
        )
        // read's exchange is untouched.
        assertEquals(
            listOf("read"),
            pruned.filterIsInstance<ModelMessage.Tool>().flatMap { it.content }
                .filterIsInstance<ToolPart.Result>().map { it.toolName },
        )
    }

    @Test
    fun `an unscoped tool rule drops calls and results together`() {
        val pruned = pruneMessages(conversation, toolCalls = listOf(PruneToolCalls()))

        // Both exchanges gone, in both halves; the tool turns emptied and therefore removed.
        assertTrue(pruned.none { it is ModelMessage.Tool })
        assertTrue(
            pruned.filterIsInstance<ModelMessage.Assistant>()
                .flatMap { it.content }
                .none { it is AssistantPart.ToolCall },
        )
        // The narration and the answer survive.
        assertEquals("all done", ((pruned.last() as ModelMessage.Assistant).content.last() as AssistantPart.Text).text)
    }

    @Test
    fun `the kept window keeps a whole exchange even when its call sits outside it`() {
        // Last 2 messages: the c2 TOOL turn and the final text. c2's CALL is in message index 3 —
        // outside the window — and must survive because its result is inside it.
        val pruned = pruneMessages(
            conversation,
            toolCalls = listOf(PruneToolCalls(keepLastMessages = 2)),
        )

        val keptCalls = pruned.filterIsInstance<ModelMessage.Assistant>()
            .flatMap { it.content }
            .filterIsInstance<AssistantPart.ToolCall>()
            .map { it.toolCallId }
        assertEquals(listOf("c2"), keptCalls)
        val keptResults = pruned.filterIsInstance<ModelMessage.Tool>()
            .flatMap { it.content }
            .filterIsInstance<ToolPart.Result>()
            .map { it.toolCallId }
        assertEquals(listOf("c2"), keptResults)
    }

    @Test
    fun `a tools-scoped rule leaves other tools' exchanges alone`() {
        val pruned = pruneMessages(
            conversation,
            toolCalls = listOf(PruneToolCalls(tools = listOf("search"))),
        )

        val keptCalls = pruned.filterIsInstance<ModelMessage.Assistant>()
            .flatMap { it.content }
            .filterIsInstance<AssistantPart.ToolCall>()
            .map { it.toolName }
        assertEquals(listOf("edit"), keptCalls)
    }

    @Test
    fun `keepEmptyMessages retains the husks pruning leaves`() {
        val pruned = pruneMessages(
            conversation,
            toolCalls = listOf(PruneToolCalls()),
            keepEmptyMessages = true,
        )

        // The two tool turns are now empty but still present.
        assertEquals(conversation.size, pruned.size)
        assertTrue(pruned.filterIsInstance<ModelMessage.Tool>().all { it.content.isEmpty() })
    }
}
