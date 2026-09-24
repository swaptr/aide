package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.isRuntimeMinted
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

/**
 * The client-side approval gate: policy outranks the tool's declaration, an automatic decision still
 * leaves a request/response pair in the transcript, and a question nothing in-process can answer ends
 * the run instead of guessing.
 */
class ApprovalsTest {

    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenPrompts = mutableListOf<Prompt>()

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private fun callRound(id: String, tool: String) = listOf(
        StreamPart.ToolCallPart(Content.ToolCall(id, tool, """{"path":"/tmp/x"}""")),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use")),
    )

    private fun textRound(text: String) = listOf(
        StreamPart.TextStart("t0"),
        StreamPart.TextDelta("t0", text),
        StreamPart.TextEnd("t0"),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "end_turn")),
    )

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("write the file"))))

    private fun tool(name: String, needsApproval: Boolean = false) = Tool.Function(
        name = name,
        inputSchema = buildJsonObject { },
        needsApproval = needsApproval,
    )

    @Test
    fun `a policy denial reaches the model as an execution-denied result with the reason`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("understood")))
        var ran = false

        val events = streamText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt = prompt, tools = listOf(tool("write"))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
            toolApprovals = ToolApprovals(
                policy = toolApprovalPolicy(mapOf("write" to ToolApprovalStatus.Denied("read-only session"))),
            ),
        ).toList()

        assertFalse(ran)
        val result = events.filterIsInstance<RunEvent.ToolResult>().single()
        val output = result.result.output as ToolOutput.ExecutionDenied
        assertEquals("read-only session", output.reason)
        // Policy decided alone, and the transcript still shows the gate ran.
        val approval = events.filterIsInstance<RunEvent.Approval>().single()
        assertEquals(true, approval.request.isAutomatic)
        assertFalse(approval.approved)
        // The loop continued: the model saw the denial and answered.
        assertEquals(2, model.seenPrompts.size)
    }

    @Test
    fun `needsApproval on the tool routes through the in-process handler`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("done")))
        var ran = false

        val events = streamText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt = prompt, tools = listOf(tool("write", needsApproval = true))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            approveTool = { _, _ -> true },
            stopWhen = stepCountIs(5),
        ).toList()

        assertTrue(ran)
        val approval = events.filterIsInstance<RunEvent.Approval>().single()
        assertTrue(approval.approved)
        // A human-answered request is not automatic.
        assertEquals(null, approval.request.isAutomatic)
    }

    @Test
    fun `policy outranks the tool's declaration`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("done")))
        var ran = false

        streamText(
            model = model,
            prompt = prompt,
            // The tool says ask; the policy says the caller already agreed.
            options = CallOptions(prompt = prompt, tools = listOf(tool("write", needsApproval = true))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
            toolApprovals = ToolApprovals(
                policy = toolApprovalPolicy(mapOf("write" to ToolApprovalStatus.Approved("session-approved"))),
            ),
        ).toList()

        assertTrue(ran)
    }

    @Test
    fun `an unanswerable question ends the run with the request pending`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("never reached")))
        var ran = false

        val events = streamText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt = prompt, tools = listOf(tool("write", needsApproval = true))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            // No approveTool: nothing in-process can answer.
            stopWhen = stepCountIs(5),
        ).toList()

        assertFalse(ran)
        assertTrue(events.filterIsInstance<RunEvent.ApprovalPending>().isNotEmpty())
        // The run STOPPED after the round — no second model call.
        assertEquals(1, model.seenPrompts.size)
        val result = events.filterIsInstance<RunEvent.Finish>().single().result
        assertEquals("c1", result.pendingApprovals.single().toolCallId)
    }

    @Test
    fun `a secret signs the request, binding it to the exact call`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("never reached")))
        val secret = "approval-secret".encodeToByteArray()

        val events = streamText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt = prompt, tools = listOf(tool("write", needsApproval = true))),
            toolExecutor = { _, _ -> ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
            toolApprovals = ToolApprovals(secret = secret),
        ).toList()

        val request = events.filterIsInstance<RunEvent.ApprovalPending>().single().request
        val call = events.filterIsInstance<RunEvent.ApprovalPending>().single().call
        assertTrue(verifyToolApproval(secret, request.approvalId, call, request.signature))
        // A different call must NOT verify against the same signature.
        val tampered = call.copy(input = """{"path":"/etc/passwd"}""")
        assertFalse(verifyToolApproval(secret, request.approvalId, tampered, request.signature))
    }

    // --- Cross-call resume ---------------------------------------------------------------------------

    /** What a host stores after a run stops on a pending approval, plus the human's later answer. */
    private fun storedConversation(
        approved: Boolean,
        signature: String? = null,
        input: String = """{"path":"/tmp/x"}""",
    ): Prompt = listOf(
        ModelMessage.User(listOf(UserPart.Text("write the file"))),
        ModelMessage.Assistant(
            listOf(
                AssistantPart.ToolCall("c1", "write", input),
                AssistantPart.ApprovalRequest("apr_1", "c1", signature = signature),
            ),
        ),
        ModelMessage.Tool(listOf(ToolPart.ApprovalResponse("apr_1", approved))),
    )

    @Test
    fun `a stored conversation with a pending approval is a valid prompt`() {
        // The shape a confirm-before-writing flow produces: a call, an unanswered request, no result.
        val pending: Prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("write the file"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("c1", "write", "{}"),
                    AssistantPart.ApprovalRequest("apr_1", "c1"),
                ),
            ),
        )

        // Would have been MissingToolResultsError before: waiting on a human read as a truncated turn.
        assertEquals(pending, standardizePrompt(pending))
    }

    @Test
    fun `an approval answered after the fact runs the call before the model is asked again`() = runTest {
        val model = ScriptedModel(listOf(textRound("written")))
        var ranWith: String? = null

        val result = generateText(
            model = model,
            prompt = storedConversation(approved = true),
            options = CallOptions(prompt = storedConversation(approved = true), tools = listOf(tool("write"))),
            toolExecutor = { call, _ -> ranWith = call.input; ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
        )

        assertEquals("""{"path":"/tmp/x"}""", ranWith)
        // The model's first call already carried the result — it asked, it was answered.
        val sent = model.seenPrompts.single()
        val results = sent.filterIsInstance<ModelMessage.Tool>().flatMap { it.content }
            .filterIsInstance<ToolPart.Result>()
        assertEquals("c1", results.single().toolCallId)
        assertEquals("written", result.text)
    }

    @Test
    fun `a denial answered after the fact produces a result rather than silence`() = runTest {
        val model = ScriptedModel(listOf(textRound("understood")))
        var ran = false

        generateText(
            model = model,
            prompt = storedConversation(approved = false),
            options = CallOptions(prompt = storedConversation(approved = false), tools = listOf(tool("write"))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
        )

        assertFalse(ran)
        val results = model.seenPrompts.single().filterIsInstance<ModelMessage.Tool>()
            .flatMap { it.content }.filterIsInstance<ToolPart.Result>()
        assertTrue(results.single().output is ToolOutput.ExecutionDenied)
    }

    @Test
    fun `an approval moved onto a different call does not execute it`() = runTest {
        val secret = "approval-secret".encodeToByteArray()
        val genuine = Content.ToolCall("c1", "write", """{"path":"/tmp/x"}""")
        val signature = signToolApproval(secret, "apr_1", genuine)
        // The stored conversation is edited between the ask and the answer: same ids, different path.
        val tampered = storedConversation(approved = true, signature = signature, input = """{"path":"/etc/passwd"}""")
        val model = ScriptedModel(listOf(textRound("refused")))
        var ran = false

        generateText(
            model = model,
            prompt = tampered,
            options = CallOptions(prompt = tampered, tools = listOf(tool("write"))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
            toolApprovals = ToolApprovals(secret = secret),
        )

        assertFalse(ran)
        val results = model.seenPrompts.single().filterIsInstance<ModelMessage.Tool>()
            .flatMap { it.content }.filterIsInstance<ToolPart.Result>()
        val denied = results.single().output as ToolOutput.ExecutionDenied
        assertTrue(denied.reason!!.contains("does not match"))
    }

    @Test
    fun `a call that already has a result is never re-run on resume`() = runTest {
        val settled: Prompt = storedConversation(approved = true) +
            ModelMessage.Tool(listOf(ToolPart.Result("c1", "write", ToolOutput.Text("wrote"))))
        val model = ScriptedModel(listOf(textRound("done")))
        var ran = false

        generateText(
            model = model,
            prompt = settled,
            options = CallOptions(prompt = settled, tools = listOf(tool("write"))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote again") },
            stopWhen = stepCountIs(5),
        )

        // Re-running would repeat an effect the user has already seen — the one failure this flow
        // must not have.
        assertFalse(ran)
    }

    @Test
    fun `a pending approval round-trips through the assistant turn it is stored in`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("never reached")))

        val result = generateText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt = prompt, tools = listOf(tool("write", needsApproval = true))),
            toolExecutor = { _, _ -> ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
        )

        // What a host persists is RunResult.messages; the request has to be in there or the answer
        // arrives against a conversation with nothing to attach it to.
        val stored = result.messages.filterIsInstance<ModelMessage.Assistant>()
            .flatMap { it.content }
            .filterIsInstance<AssistantPart.ApprovalRequest>()
        assertEquals("c1", stored.single().toolCallId)
    }

    @Test
    fun `a handler wired on the next invocation is consulted, not handed the question back`() = runTest {
        // Stored by a handler-less run; the host comes back with a handler.
        val stored: Prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("write the file"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("c1", "write", "{}"),
                    AssistantPart.ApprovalRequest("apr_1", "c1"),
                ),
            ),
        )
        val model = ScriptedModel(listOf(textRound("written")))
        var ran = false

        val result = generateText(
            model = model,
            prompt = stored,
            options = CallOptions(prompt = stored, tools = listOf(tool("write"))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            approveTool = { _, _ -> true },
            stopWhen = stepCountIs(5),
        )

        assertTrue(ran)
        // Progress, not the same question forever.
        assertTrue(result.pendingApprovals.isEmpty())
        assertEquals("written", result.text)
    }

    @Test
    fun `a question the user walked away from is denied so the new message gets answered`() = runTest {
        // The human answered by typing something else instead of confirming.
        val movedOn: Prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("write the file"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("c1", "write", "{}"),
                    AssistantPart.ApprovalRequest("apr_1", "c1"),
                ),
            ),
            ModelMessage.User(listOf(UserPart.Text("actually, never mind — what time is it?"))),
        )
        val model = ScriptedModel(listOf(textRound("half past four")))
        var ran = false

        val result = generateText(
            model = model,
            prompt = movedOn,
            options = CallOptions(prompt = movedOn, tools = listOf(tool("write"))),
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("wrote") },
            stopWhen = stepCountIs(5),
        )

        assertFalse(ran)
        // The run did NOT dead-end: the model saw the new message and answered it.
        assertEquals("half past four", result.text)
        assertTrue(result.pendingApprovals.isEmpty())
        val denied = model.seenPrompts.single().filterIsInstance<ModelMessage.Tool>()
            .flatMap { it.content }.filterIsInstance<ToolPart.Result>()
            .single().output as ToolOutput.ExecutionDenied
        assertTrue(denied.reason!!.contains("moved on"))
    }

    @Test
    fun `a runtime-minted approval exchange is tagged so providers can skip it`() = runTest {
        val model = ScriptedModel(listOf(callRound("c1", "write"), textRound("done")))

        val result = generateText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt = prompt, tools = listOf(tool("write", needsApproval = true))),
            toolExecutor = { _, _ -> ToolOutput.Text("wrote") },
            approveTool = { _, _ -> true },
            stopWhen = stepCountIs(5),
        )

        // Its approval id was minted here; replaying it to a vendor would name an item that vendor
        // never created, so both halves carry the runtime marker.
        val response = result.messages.filterIsInstance<ModelMessage.Tool>()
            .flatMap { it.content }.filterIsInstance<ToolPart.ApprovalResponse>().single()
        assertTrue(response.isRuntimeMinted)
        val request = result.messages.filterIsInstance<ModelMessage.Assistant>()
            .flatMap { it.content }.filterIsInstance<AssistantPart.ApprovalRequest>().single()
        assertTrue(request.isRuntimeMinted)
    }

    @Test
    fun `canonical digesting makes key order irrelevant to the signature`() {
        val secret = "s".encodeToByteArray()
        val a = Content.ToolCall("c1", "write", """{"a":1,"b":2}""")
        val b = Content.ToolCall("c1", "write", """{"b":2,"a":1}""")
        val c = Content.ToolCall("c1", "write", """{"a":1,"b":3}""")

        assertEquals(signToolApproval(secret, "apr_1", a), signToolApproval(secret, "apr_1", b))
        assertNotEquals(signToolApproval(secret, "apr_1", a), signToolApproval(secret, "apr_1", c))
    }
}
