package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ToolPart

// ---------------------------------------------------------------------------------------------------
// Typed views over a Step and a RunResult.
//
// Both hold their content as one ordered list, because order is the contract (see Content). These are
// the reference's `StepResult` / `GenerateTextResult` getters, kept as extensions rather than members so
// Steps.kt stays the shape of the data and this file stays the shape of the questions asked of it.
// Nothing here is a second source of truth: every accessor is a filter over `content` or `steps`.
// ---------------------------------------------------------------------------------------------------

/** The vendor's own finish string, for the log line — see [com.sabreware.aide.aisdk.FinishReason.raw]. */
public val Step.rawFinishReason: String? get() = finishReason.raw

/**
 * The reasoning blocks and reasoning-time files, in order, metadata intact.
 *
 * This is the list to persist or replay; [reasoning] and [reasoningText] are for display, and a signature
 * survives neither.
 */
public val Step.reasoningParts: List<Content>
    get() = content.filter { it is Content.Reasoning || it is Content.ReasoningFile }

/**
 * Every reasoning block's text joined, or null when the round had none.
 *
 * Null rather than empty so a consumer can tell "the model did not think" from "it thought and the
 * vendor hid the text" — the difference between reasoning off and a model that returns signatures with
 * no prose. Joined without a separator, as the reference joins; [reasoning] keeps its newline join.
 */
public val Step.reasoningText: String?
    get() = content.filterIsInstance<Content.Reasoning>().joinToString("") { it.text }.takeIf { it.isNotEmpty() }

/** Files the model generated this round. */
public val Step.files: List<Content.File> get() = content.filterIsInstance<Content.File>()

/** Files the model generated while reasoning — a chart drawn mid-thought. */
public val Step.reasoningFiles: List<Content.ReasoningFile> get() = content.filterIsInstance<Content.ReasoningFile>()

/** The sources the model cited this round. */
public val Step.sources: List<Content.Source> get() = content.filterIsInstance<Content.Source>()

/** Calls to tools the round OFFERED — the ones whose input shape a typed executor knows. */
public val Step.staticToolCalls: List<Content.ToolCall> get() = toolCalls.filterNot { it.dynamic }

/** Calls to tools discovered at run time — MCP, typically — whose input shape is the call's alone. */
public val Step.dynamicToolCalls: List<Content.ToolCall> get() = toolCalls.filter { it.dynamic }

/**
 * Results the VENDOR produced for tools it ran itself.
 *
 * Distinct from [Step.toolResults], which this runtime produced and replays in the tool turn: these ride
 * inside the assistant turn, and replaying them anywhere else sends them to the model twice.
 */
public val Step.providerToolResults: List<Content.ToolResult> get() = content.filterIsInstance<Content.ToolResult>()

/** [Step.toolResults] for the calls in [staticToolCalls]. */
public val Step.staticToolResults: List<ToolPart.Result>
    get() = toolResults.filterNot { it.toolCallId in dynamicToolCallIds() }

/** [Step.toolResults] for the calls in [dynamicToolCalls]. */
public val Step.dynamicToolResults: List<ToolPart.Result>
    get() = toolResults.filter { it.toolCallId in dynamicToolCallIds() }

private fun Step.dynamicToolCallIds(): Set<String> = dynamicToolCalls.mapTo(mutableSetOf()) { it.toolCallId }

/**
 * The turns this round appended to the conversation: the assistant turn, then the tool turn — exactly
 * what the loop replayed for the next round, and what a caller persists to continue the conversation in
 * a later process. An empty assistant turn is omitted, because it is a 400 everywhere.
 */
public val Step.responseMessages: List<ModelMessage>
    get() = listOfNotNull(toAssistantMessage().takeIf { it.content.isNotEmpty() }, toToolMessage())

// ---- RunResult ------------------------------------------------------------------------------------

/** The last round — the one whose text is [RunResult.text]. Null on a run that ended before its first. */
public val RunResult.finalStep: Step? get() = steps.lastOrNull()

/** The vendor's own finish string for the last round. */
public val RunResult.rawFinishReason: String? get() = finishReason.raw

/** Every round's content, in order. */
public val RunResult.content: List<Content> get() = steps.flatMap { it.content }

/** The last round's reasoning text, or null — see [Step.reasoningText]. */
public val RunResult.reasoningText: String? get() = finalStep?.reasoningText

/** The last round's reasoning parts — see [Step.reasoningParts]. */
public val RunResult.reasoningParts: List<Content> get() = finalStep?.reasoningParts.orEmpty()

/** Every file any round generated. */
public val RunResult.files: List<Content.File> get() = steps.flatMap { it.files }

/** Every file any round generated while reasoning. */
public val RunResult.reasoningFiles: List<Content.ReasoningFile> get() = steps.flatMap { it.reasoningFiles }

/** Every source any round cited. */
public val RunResult.sources: List<Content.Source> get() = steps.flatMap { it.sources }

/** Every tool call any round made, valid or not, provider-executed or not. */
public val RunResult.toolCalls: List<Content.ToolCall> get() = steps.flatMap { it.toolCalls }

/** Every call to an offered tool — see [Step.staticToolCalls]. */
public val RunResult.staticToolCalls: List<Content.ToolCall> get() = steps.flatMap { it.staticToolCalls }

/** Every call to a run-time-discovered tool — see [Step.dynamicToolCalls]. */
public val RunResult.dynamicToolCalls: List<Content.ToolCall> get() = steps.flatMap { it.dynamicToolCalls }

/** Every result this runtime produced, across rounds. */
public val RunResult.toolResults: List<ToolPart.Result> get() = steps.flatMap { it.toolResults }

/** See [Step.staticToolResults]. */
public val RunResult.staticToolResults: List<ToolPart.Result> get() = steps.flatMap { it.staticToolResults }

/** See [Step.dynamicToolResults]. */
public val RunResult.dynamicToolResults: List<ToolPart.Result> get() = steps.flatMap { it.dynamicToolResults }

/** Every vendor-produced result, across rounds — see [Step.providerToolResults]. */
public val RunResult.providerToolResults: List<Content.ToolResult> get() = steps.flatMap { it.providerToolResults }
