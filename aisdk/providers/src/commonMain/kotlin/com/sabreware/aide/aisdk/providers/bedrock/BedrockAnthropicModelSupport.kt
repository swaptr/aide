package com.sabreware.aide.aisdk.providers.bedrock

/** The one `modelFamily` a Bedrock chat model can be declared as when its id does not say. */
public const val BEDROCK_MODEL_FAMILY_ANTHROPIC: String = "anthropic"

/** The marker of an application inference profile ARN, which names no model family at all. */
internal const val BEDROCK_APPLICATION_INFERENCE_PROFILE: String = ":application-inference-profile/"

/**
 * Whether a Bedrock model speaks Anthropic's request shape.
 *
 * Three signals, in the reference's order: the caller declared the family outright; the id names
 * `anthropic`; or the id is an application inference profile ARN — which hides its model — and the
 * caller supplied an Anthropic-only thinking budget, the one option no other family reads.
 */
internal fun isBedrockAnthropicModel(
    modelId: String,
    modelFamily: String? = null,
    reasoningBudgetTokens: Int? = null,
): Boolean =
    modelFamily == BEDROCK_MODEL_FAMILY_ANTHROPIC ||
        "anthropic" in modelId ||
        (BEDROCK_APPLICATION_INFERENCE_PROFILE in modelId && reasoningBudgetTokens != null)

/**
 * Whether Bedrock accepts `strict` on a tool definition for this Claude model.
 *
 * Bedrock validates against its own copy of the Messages schema, which rejects `strict` for the newest
 * Claude families even though Anthropic's own endpoint takes it. The list is the reference's, verbatim.
 */
internal fun bedrockSupportsStrictTools(modelId: String): Boolean =
    MODELS_WITHOUT_STRICT_TOOL_SUPPORT.none { it in modelId }

/**
 * Whether `output_config.format` is worth sending to this Claude model on Bedrock.
 *
 * Wider than [bedrockSupportsStrictTools] on purpose: the models Bedrock's schema rejects the field for
 * are joined by two it accepts it for but serves unreliably — Sonnet 4.6 can fail to follow a complex
 * schema, and Haiku 4.5's support varies between Bedrock accounts. Both keep strict tools; both fall
 * back to the JSON tool for structured output unless the caller forces `outputFormat`.
 */
internal fun bedrockSupportsNativeStructuredOutput(modelId: String): Boolean =
    MODELS_WITHOUT_RELIABLE_NATIVE_STRUCTURED_OUTPUT.none { it in modelId }

private val MODELS_WITHOUT_STRICT_TOOL_SUPPORT = listOf(
    "claude-opus-4-7",
    "claude-opus-4-8",
    "claude-opus-5",
    "claude-fable-5",
    "claude-sonnet-5",
)

private val MODELS_WITHOUT_RELIABLE_NATIVE_STRUCTURED_OUTPUT =
    MODELS_WITHOUT_STRICT_TOOL_SUPPORT + listOf("claude-sonnet-4-6", "claude-haiku-4-5")
