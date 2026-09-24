package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.JsonSchema

/**
 * Instructions telling a model to answer as JSON, appended to a system prompt.
 *
 * For servers with no structured-output mode: the schema goes in the prompt because there is nowhere
 * else to put it. Strictly worse than constrained decoding — the model may still deviate — which is why
 * this is a fallback a caller opts into rather than something applied automatically.
 *
 * [schema] is nullable because "JSON, shape unspecified" is a real mode rather than a degenerate one:
 * the runtime's no-schema object generation has nothing to inject and still needs the model told to
 * answer in JSON at all. With no schema the prefix is omitted entirely rather than replaced by a line
 * saying there is none — a sentence about the absence of a schema is one more thing for the model to
 * misread as content.
 *
 * The wording is the reference's, verbatim, because a prompt is a wire format: a model tuned against
 * these exact words behaves differently against a paraphrase, and "improved" phrasing here is an
 * untestable regression.
 */
public fun jsonSchemaInstruction(schema: JsonSchema?, prompt: String? = null): String =
    listOfNotNull(
        prompt?.takeIf { it.isNotEmpty() },
        prompt?.takeIf { it.isNotEmpty() }?.let { "" },
        schema?.let { SCHEMA_PREFIX },
        schema?.toString(),
        if (schema != null) SCHEMA_SUFFIX else GENERIC_SUFFIX,
    ).joinToString("\n")

private const val SCHEMA_PREFIX = "JSON schema:"
private const val SCHEMA_SUFFIX = "You MUST answer with a JSON object that matches the JSON schema above."
private const val GENERIC_SUFFIX = "You MUST answer with JSON."
