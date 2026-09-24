package com.sabreware.aide.aisdk.runtime.agent

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.runtime.ApprovalHandler
import com.sabreware.aide.aisdk.runtime.AssetDownloader
import com.sabreware.aide.aisdk.runtime.PrepareStep
import com.sabreware.aide.aisdk.runtime.RunEvent
import com.sabreware.aide.aisdk.runtime.RunResult
import com.sabreware.aide.aisdk.runtime.RunTimeouts
import com.sabreware.aide.aisdk.runtime.StopCondition
import com.sabreware.aide.aisdk.runtime.StreamRetries
import com.sabreware.aide.aisdk.runtime.ToolApprovals
import com.sabreware.aide.aisdk.runtime.ToolCallRepair
import com.sabreware.aide.aisdk.runtime.ToolExecutor
import com.sabreware.aide.aisdk.runtime.generateText
import com.sabreware.aide.aisdk.runtime.stepCountIs
import com.sabreware.aide.aisdk.runtime.streamText
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.withMiddleware
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

/**
 * A named, pre-configured run: a model, the tools it may reach, what it is for, and the policy around
 * all of it.
 *
 * Everything here is already a parameter of [streamText]. What an agent adds is that they are decided
 * ONCE, together, and checked: the loop takes fourteen parameters, and a caller that passes them at
 * every call site is a caller who will eventually pass a different set at one of them. AIDE has the
 * consumer this is for — the IME's saved prompts are preconfigured agents already, expressed as a row
 * in a table plus whatever the send path happened to be configured with.
 *
 * **The configuration is validated at construction, not at the first call.** A model offered a tool
 * nothing can execute, a `toolChoice` naming a tool that was not offered, two tools with the same name
 * — each of those is a failure that otherwise surfaces as a vendor 400 or, worse, as a conversation
 * where the model asks for something and is never answered. An agent is defined once and run many
 * times, so the check belongs at the definition.
 *
 * **Sampling settings are middleware, not more parameters.** `defaultSettings(temperature = 0.2)` says
 * it in the one place this codebase says it, and composes with whatever else the caller is layering.
 * Adding `temperature`, `topP`, `seed` and the rest here would be a second spelling of the same thing
 * that then has to agree with the first.
 *
 * **Lifecycle is the event flow, not a callback set.** [stream] emits every [RunEvent] the loop
 * produces, so observing an agent is `onEach`, and measuring one is `recordingTo` — see
 * `:aisdk:runtime`'s telemetry package. An `onStepFinish` parameter here would be a parallel surface
 * that composes with nothing.
 */
@Suppress("LongParameterList")
public class Agent(
    /** Identifies this agent in logs and telemetry. Not sent to the model. */
    public val name: String,
    private val model: LanguageModel,
    /** Becomes the leading system turn of every run. */
    public val instructions: String? = null,
    private val tools: List<Tool>? = null,
    /**
     * Runs whatever [tools] the model asks for.
     *
     * Required whenever tools are offered. Offering a tool with nothing to run it produces a round
     * where the model asks and gets no answer, and a model given no answer reissues the same call.
     */
    private val toolExecutor: ToolExecutor? = null,
    private val toolChoice: ToolChoice? = null,
    /** Defaults to one round, matching the runtime. An agent with tools almost always wants more. */
    private val stopWhen: StopCondition = stepCountIs(1),
    private val approveTool: ApprovalHandler? = null,
    /** The client-side approval gate — policy and signing secret; see [ToolApprovals]. */
    private val toolApprovals: ToolApprovals? = null,
    private val repairToolCall: ToolCallRepair? = null,
    private val prepareStep: PrepareStep? = null,
    private val timeouts: RunTimeouts = RunTimeouts(),
    private val retry: RetryPolicy = RetryPolicy.None,
    /** Recovery for a provider error received after a round's stream began; [stream] only. */
    private val streamRetries: StreamRetries? = null,
    private val downloadAssets: AssetDownloader? = null,
    /** Applied outermost-first, exactly as [withMiddleware] orders them. */
    private val middleware: List<LanguageModelMiddleware> = emptyList(),
    /**
     * Templates this agent per invocation — see [AgentPrepareCall].
     *
     * The definition stays one object defined once; what varies per call — a tool set narrowed to the
     * caller's permissions, instructions with the caller's locale folded in — is computed here from the
     * payload the call site passes to [stream]/[generate]. Without this hook, a templated agent is a
     * factory function returning a new Agent per call, which re-runs construction validation on every
     * message send.
     */
    private val prepareCall: AgentPrepareCall? = null,
) {

    private val configuredModel: LanguageModel = model.withMiddleware(*middleware.toTypedArray())

    init {
        validate()
    }

    /**
     * Streams a run. Every [RunEvent] the loop produces, in order.
     *
     * @param callOptions the per-invocation payload [prepareCall] reads; ignored without the hook.
     */
    public fun stream(prompt: Prompt, callOptions: JsonElement? = null): Flow<RunEvent> = flow {
        val plan = prepareCall?.prepare(callOptions)?.also { it.validateAgainst(name) }
        emitAll(
            streamText(
                model = configuredModel,
                prompt = prompt,
                options = callOptions(prompt, plan),
                toolExecutor = toolExecutor,
                stopWhen = plan?.stopWhen ?: stopWhen,
                repairToolCall = repairToolCall,
                approveTool = approveTool,
                toolApprovals = toolApprovals,
                prepareStep = prepareStep,
                timeouts = timeouts,
                retry = retry,
                streamRetries = streamRetries,
                instructions = plan?.instructions ?: instructions,
                downloadAssets = downloadAssets,
            ),
        )
    }

    /**
     * Runs to completion and returns the result. Uses `doGenerate`, where [stream] uses `doStream`.
     *
     * @param callOptions the per-invocation payload [prepareCall] reads; ignored without the hook.
     */
    public suspend fun generate(prompt: Prompt, callOptions: JsonElement? = null): RunResult {
        val plan = prepareCall?.prepare(callOptions)?.also { it.validateAgainst(name) }
        return generateText(
            model = configuredModel,
            prompt = prompt,
            options = callOptions(prompt, plan),
            toolExecutor = toolExecutor,
            stopWhen = plan?.stopWhen ?: stopWhen,
            repairToolCall = repairToolCall,
            approveTool = approveTool,
            toolApprovals = toolApprovals,
            prepareStep = prepareStep,
            timeouts = timeouts,
            retry = retry,
            instructions = plan?.instructions ?: instructions,
            downloadAssets = downloadAssets,
        )
    }

    /** The one-shot case: a single user turn, which is what a saved prompt actually is. */
    public fun stream(text: String): Flow<RunEvent> = stream(userTurn(text))

    /** @see stream */
    public suspend fun generate(text: String): RunResult = generate(userTurn(text))

    private fun userTurn(text: String): Prompt =
        listOf(ModelMessage.User(listOf(UserPart.Text(text))))

    private fun callOptions(prompt: Prompt, plan: AgentCallPlan? = null) = CallOptions(
        prompt = prompt,
        tools = plan?.tools ?: tools,
        toolChoice = plan?.toolChoice ?: toolChoice,
    )

    /**
     * A templated call re-runs the construction checks its overrides can invalidate.
     *
     * Construction validation cannot see a per-call tool set, so the same failures — duplicate names,
     * a forced tool that is not offered, client tools with nothing to run them — must be caught here,
     * at the call that introduced them, rather than surface as the vendor 400 the constructor exists
     * to pre-empt.
     */
    private fun AgentCallPlan.validateAgainst(agentName: String) {
        val effectiveTools = tools ?: this@Agent.tools
        val effectiveChoice = toolChoice ?: this@Agent.toolChoice
        val clientTools = effectiveTools.orEmpty().filterIsInstance<Tool.Function>()
        if (clientTools.isNotEmpty() && toolExecutor == null) {
            throw InvalidArgumentError(
                "Agent '$agentName' was called with ${clientTools.size} client tool(s) but has no executor.",
                "prepareCall",
            )
        }
        val duplicates = effectiveTools.orEmpty().groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw InvalidArgumentError(
                "Agent '$agentName' was called with more than one tool named ${duplicates.joinToString()}.",
                "prepareCall",
            )
        }
        if (effectiveChoice is ToolChoice.Specific &&
            effectiveTools.orEmpty().none { it.name == effectiveChoice.toolName }
        ) {
            throw InvalidArgumentError(
                "Agent '$agentName' was called forcing the tool '${effectiveChoice.toolName}', which this call does not offer.",
                "prepareCall",
            )
        }
    }

    private fun validate() {
        if (name.isBlank()) {
            throw InvalidArgumentError("An agent must have a name.", "name")
        }
        // Only client-side tools need one. A provider-defined tool runs on the vendor's servers, so an
        // agent offering nothing but web search legitimately has no executor.
        val clientTools = tools.orEmpty().filterIsInstance<Tool.Function>()
        if (clientTools.isNotEmpty() && toolExecutor == null) {
            throw InvalidArgumentError(
                "Agent '$name' offers ${clientTools.size} client tool(s) but has no executor to run them.",
                "toolExecutor",
            )
        }
        val duplicates = tools.orEmpty().groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            // Most vendors 400 on a duplicated tool name, and the ones that do not resolve a call to
            // whichever definition they saw first — so the second one silently never runs.
            throw InvalidArgumentError(
                "Agent '$name' declares more than one tool named ${duplicates.joinToString()}.",
                "tools",
            )
        }
        if (toolChoice is ToolChoice.Specific && tools.orEmpty().none { it.name == toolChoice.toolName }) {
            throw InvalidArgumentError(
                "Agent '$name' forces the tool '${toolChoice.toolName}', which it does not offer.",
                "toolChoice",
            )
        }
    }
}

/**
 * What one invocation of a templated agent changes about it. Null fields leave the definition alone.
 *
 * Only the four definition-shaped settings are templatable. Sampling belongs to middleware, and the
 * run-shaped hooks — executor, repair, approval — are the agent's identity: an agent whose executor
 * varies per call is two agents.
 */
public data class AgentCallPlan(
    /** Replaces the agent's instructions for this invocation. */
    val instructions: String? = null,
    /** Replaces the agent's tool set for this invocation — narrowed to a caller's permissions, say. */
    val tools: List<Tool>? = null,
    /** Replaces the agent's tool-choice mode for this invocation. */
    val toolChoice: ToolChoice? = null,
    /** Replaces the agent's stop condition for this invocation. */
    val stopWhen: StopCondition? = null,
)

/**
 * Templates an [Agent] from a per-invocation payload.
 *
 * [callOptions] is deliberately untyped: the reference validates its equivalent against a caller-supplied
 * schema, which is a zod habit with no Kotlin counterpart — a Kotlin call site owns the payload it passes
 * and decodes it with the serializer it already has. The overrides the hook returns are re-validated with
 * the constructor's own checks, so a templated call cannot smuggle in the tool-set mistakes construction
 * validation exists to catch.
 */
public fun interface AgentPrepareCall {

    /** @return the adjustments for this invocation, or null to run the agent exactly as defined. */
    public suspend fun prepare(callOptions: JsonElement?): AgentCallPlan?
}
