package com.sabreware.aide.core.domain.llm

/**
 * Per-turn policy for whether — and which — tool the model may call. Provider-agnostic: it rides in
 * [ChatGenerationConfig] and each remote codec maps it to its vendor vocabulary at request-build time
 * (OpenAI `auto`/`none`/`required`/named · Anthropic `auto`/`none`/`any`/`tool`). [Auto] is the
 * universal default and serializes to *nothing* (the wire field is omitted), so a turn that never sets
 * a choice is byte-for-byte identical to before this type existed.
 *
 * Enforcement fidelity varies by provider, by design — the contract is uniform, the mechanism is not:
 *  - **OpenAI / Anthropic**: all four cases map to a native `tool_choice` form.
 *  - **Ollama**: its chat API has no `tool_choice`; only [None] is enforceable (the codec drops the
 *    wire `tools`). [Required]/[Named] degrade to [Auto] (best-effort — steer via prompt).
 *  - **LiteRT (on-device)**: auto-only; the runtime exposes no force-tool knob.
 *
 * A choice only attaches when the turn actually has tools — forcing a call with an empty `tools[]`
 * is a 400 on every vendor, so the codecs gate on tool presence.
 */
sealed interface ToolChoice {
    /** Model decides freely whether to call a tool (the vendor default). */
    data object Auto : ToolChoice

    /** Model must not call any tool this turn, even when tools are offered. */
    data object None : ToolChoice

    /** Model must call some tool — any of the offered ones. */
    data object Required : ToolChoice

    /** Model must call exactly the tool named [name]. */
    data class Named(val name: String) : ToolChoice
}
