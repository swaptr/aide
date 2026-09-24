package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

/**
 * The P2 long tail on the entry points: per-tool timeouts, tool ordering, system turns in messages,
 * and the warning logger. Each is small; each is a place a wrong default is silent.
 */
class LoopOptionsTest {

    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenOptions = mutableListOf<CallOptions>()

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenOptions.size
            seenOptions += options
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = seenOptions.size
            seenOptions += options
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

    private fun toolRound(id: String, name: String) = listOf(
        StreamPart.ToolCallPart(Content.ToolCall(id, name, "{}")),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
    )

    private fun answer(text: String, warnings: List<Warning> = emptyList()) = listOf(
        StreamPart.StreamStart(warnings),
        StreamPart.TextStart("t"),
        StreamPart.TextDelta("t", text),
        StreamPart.TextEnd("t"),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
    )

    private fun tool(name: String) = Tool.Function(name, inputSchema = buildJsonObject {})

    // ---- per-tool timeouts -------------------------------------------------------------------------

    @Test
    fun `a tool's own limit outranks the shared one`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "slow"), answer("done")))

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> CompletableDeferred<ToolOutput>().await() },
            stopWhen = stepCountIs(5),
            // The shared limit would wait an hour; the tool's own entry is what fires.
            timeouts = RunTimeouts(toolMs = 3_600_000, tools = mapOf("slow" to 50)),
        )

        assertIs<ToolOutput.ErrorText>(result.steps[0].toolResults.single().output)
        assertEquals("done", result.text)
    }

    @Test
    fun `a tool with its own generous limit is exempt from a tighter shared one`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "browser"), answer("done")))

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ ->
                delay(200)
                ToolOutput.Text("page")
            },
            stopWhen = stepCountIs(5),
            timeouts = RunTimeouts(toolMs = 50, tools = mapOf("browser" to 5_000)),
        )

        // A calculator that takes a second is broken; a browser that takes a minute is working.
        assertEquals("page", assertIs<ToolOutput.Text>(result.steps[0].toolResults.single().output).value)
    }

    @Test
    fun `a tool the map does not name falls back to the shared limit`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "slow"), answer("done")))

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> CompletableDeferred<ToolOutput>().await() },
            stopWhen = stepCountIs(5),
            timeouts = RunTimeouts(toolMs = 50, tools = mapOf("other" to 5_000)),
        )

        assertIs<ToolOutput.ErrorText>(result.steps[0].toolResults.single().output)
        assertEquals(50, RunTimeouts(toolMs = 50, tools = mapOf("other" to 5_000)).toolTimeoutMs("slow"))
        assertEquals(null, RunTimeouts().toolTimeoutMs("slow"))
    }

    // ---- toolOrder ---------------------------------------------------------------------------------

    @Test
    fun `listed tools go first in listed order and the rest follow alphabetically`() = runTest {
        val model = ScriptedModel(listOf(answer("done")))
        val tools = listOf(tool("zeta"), tool("alpha"), tool("mid"))

        generateText(model, prompt, options = CallOptions(prompt, tools = tools), toolOrder = listOf("mid"))

        assertEquals(listOf("mid", "alpha", "zeta"), model.seenOptions.single().tools?.map { it.name })
    }

    @Test
    fun `a null order keeps the caller's own order`() = runTest {
        val model = ScriptedModel(listOf(answer("done")))
        val tools = listOf(tool("zeta"), tool("alpha"), tool("mid"))

        generateText(model, prompt, options = CallOptions(prompt, tools = tools))

        // Sorting away an order the caller chose would be a change nobody asked for.
        assertEquals(listOf("zeta", "alpha", "mid"), model.seenOptions.single().tools?.map { it.name })
    }

    @Test
    fun `a step plan's order outranks the run's for its round`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "mid"), answer("done")))
        val tools = listOf(tool("zeta"), tool("alpha"), tool("mid"))

        generateText(
            model = model,
            prompt = prompt,
            options = CallOptions(prompt, tools = tools),
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            toolOrder = listOf("mid"),
            prepareStep = { context -> if (context.stepIndex == 1) StepPlan(toolOrder = listOf("zeta")) else null },
        )

        assertEquals(listOf("mid", "alpha", "zeta"), model.seenOptions[0].tools?.map { it.name })
        assertEquals(listOf("zeta", "alpha", "mid"), model.seenOptions[1].tools?.map { it.name })
    }

    // ---- allowSystemInMessages ---------------------------------------------------------------------

    @Test
    fun `a system turn in the messages is refused when the caller disallows it`() = runTest {
        val model = ScriptedModel(listOf(answer("done")))
        val withSystem = listOf(ModelMessage.System("be terse")) + prompt

        // Permitted by default: a stored conversation arrives with the system turn it was run under.
        generateText(model, withSystem)

        val error = assertFailsWith<InvalidPromptError> {
            generateText(model, withSystem, allowSystemInMessages = false)
        }
        assertEquals(
            "System messages are not allowed in the prompt or messages fields. Use the instructions option instead.",
            error.message,
        )
        // Instructions are the sanctioned channel, and are unaffected by the switch.
        generateText(model, prompt, instructions = "be terse", allowSystemInMessages = false)
        assertEquals(ModelMessage.System("be terse"), model.seenOptions.last().prompt.first())
    }

    @Test
    fun `standardizePrompt exposes the switch directly`() {
        val withSystem = listOf(ModelMessage.System("s")) + prompt

        assertEquals(withSystem, standardizePrompt(withSystem))
        assertFailsWith<InvalidPromptError> { standardizePrompt(withSystem, allowSystemInMessages = false) }
        assertEquals(
            listOf(ModelMessage.System("i")) + prompt,
            standardizePrompt(prompt, instructions = "i", allowSystemInMessages = false),
        )
    }

    // ---- logWarnings -------------------------------------------------------------------------------

    @Test
    fun `each round's warnings reach the logger, attributed to the model that raised them`() = runTest {
        val topK = Warning.Unsupported("topK", "ignored by this model")
        val model = ScriptedModel(listOf(toolRound("c1", "t"), answer("done", warnings = listOf(topK))))
        val logged = mutableListOf<Triple<List<Warning>, String?, String?>>()

        generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            logWarnings = { warnings, provider, modelId -> logged += Triple(warnings, provider, modelId) },
        )

        // Once, for the round that warned; a round with nothing to say does not reach the logger.
        val expected: Triple<List<Warning>, String?, String?> = Triple(listOf(topK), "scripted", "scripted-1")
        assertEquals(listOf(expected), logged)
    }

    @Test
    fun `generateText says up front that a stream-gap limit will not be honoured`() = runTest {
        val model = ScriptedModel(listOf(answer("done")))
        val logged = mutableListOf<Warning>()

        generateText(
            model = model,
            prompt = prompt,
            timeouts = RunTimeouts(firstChunkMs = 1_000, chunkMs = 100),
            logWarnings = { warnings, _, _ -> logged += warnings },
        )

        assertEquals(listOf("timeout.firstChunkMs", "timeout.chunkMs"), logged.map { (it as Warning.Unsupported).feature })
    }

    @Test
    fun `warnings are formatted in the reference's wording`() {
        assertEquals(
            "AI SDK Warning (p / m): The feature \"topK\" is not supported. ignored",
            formatWarning(Warning.Unsupported("topK", "ignored"), "p", "m"),
        )
        assertEquals(
            "AI SDK Warning: The feature \"x\" is used in a compatibility mode.",
            formatWarning(Warning.Compatibility("x")),
        )
        assertEquals(
            "AI SDK Warning: Deprecated: \"maxTokens\". use maxOutputTokens",
            formatWarning(Warning.Deprecated("maxTokens", "use maxOutputTokens")),
        )
        assertEquals("AI SDK Warning: hello", formatWarning(Warning.Other("hello")))
        // Half a scope is no scope: the reference prints the pair or nothing.
        assertEquals("AI SDK Warning: hello", formatWarning(Warning.Other("hello"), provider = "p"))
    }
}
