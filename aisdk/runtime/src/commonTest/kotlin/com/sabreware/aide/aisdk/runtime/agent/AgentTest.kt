package com.sabreware.aide.aisdk.runtime.agent

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.runtime.ToolExecutor
import com.sabreware.aide.aisdk.runtime.middleware.defaultSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

/**
 * The checks an agent performs at definition, and that a defined one carries its configuration into
 * the call.
 *
 * Every rejection here is a misconfiguration that would otherwise surface hours later as a vendor 400
 * or as a model reissuing a call nothing ever answered — which is the case for defining an agent at all
 * rather than passing fourteen parameters at each call site.
 */
class AgentTest {

    private val search = Tool.Function(
        name = "search",
        description = "Searches.",
        inputSchema = JsonSchema(buildJsonObject { }),
    )

    @Test
    fun `a tool with nothing to run it is rejected at definition`() {
        val error = assertFailsWith<InvalidArgumentError> {
            Agent(name = "researcher", model = EchoModel(), tools = listOf(search))
        }

        assertEquals("toolExecutor", error.argument)
    }

    @Test
    fun `two tools with the same name are rejected`() {
        assertFailsWith<InvalidArgumentError> {
            Agent(
                name = "researcher",
                model = EchoModel(),
                tools = listOf(search, search.copy(description = "Also searches.")),
                toolExecutor = ToolExecutor { _, _ -> ToolOutput.Text("ok") },
            )
        }
    }

    @Test
    fun `forcing a tool the agent does not offer is rejected`() {
        assertFailsWith<InvalidArgumentError> {
            Agent(
                name = "researcher",
                model = EchoModel(),
                tools = listOf(search),
                toolExecutor = ToolExecutor { _, _ -> ToolOutput.Text("ok") },
                toolChoice = ToolChoice.Specific("summarise"),
            )
        }
    }

    @Test
    fun `instructions and tools reach the model on every run`() = runTest {
        val model = EchoModel()
        val agent = Agent(
            name = "researcher",
            model = model,
            instructions = "Answer with a citation.",
            tools = listOf(search),
            toolExecutor = ToolExecutor { _, _ -> ToolOutput.Text("ok") },
        )

        agent.generate("who wrote Ulysses?")

        assertEquals(ModelMessage.System("Answer with a citation."), model.received?.prompt?.first())
        assertEquals(listOf("search"), model.received?.tools?.map { it.name })
    }

    @Test
    fun `an agent's middleware wraps the model it runs`() = runTest {
        val model = EchoModel()
        val agent = Agent(
            name = "researcher",
            model = model,
            middleware = listOf(defaultSettings(temperature = 0.15)),
        )

        agent.generate("hello")

        // Configuration is expressed once, as middleware, rather than as a second set of sampling
        // parameters on this class that would then have to agree with the first.
        assertEquals(0.15, model.received?.temperature)
    }
}

private class EchoModel : LanguageModel {

    override val provider: String = "test"
    override val modelId: String = "echo-1"

    var received: CallOptions? = null
        private set

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        received = options
        return GenerateResult(
            content = listOf(Content.Text("ok")),
            finishReason = FinishReason(FinishReason.Unified.Stop),
            usage = Usage(),
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult =
        error("This test drives the generate path.")
}
