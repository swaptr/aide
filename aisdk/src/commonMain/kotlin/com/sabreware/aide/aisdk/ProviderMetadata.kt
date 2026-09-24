package com.sabreware.aide.aisdk

import kotlinx.serialization.json.JsonObject

/**
 * Provider-specific data flowing OUT of a provider, keyed first by provider id and then by that
 * provider's own key.
 *
 * This is the single most important type in the specification, and the reason the port exists.
 *
 * A provider-agnostic model has to decide what to do with data only one vendor emits — Anthropic's
 * thinking `signature`, its `redacted_thinking` payload, Gemini's `thoughtSignature`, cache-control
 * counters. The tempting answer is to model each one as a field on the neutral type; the honest answer
 * is that the neutral type can never keep up, and a field it does not have is data it silently drops.
 *
 * So the specification models NONE of it. A provider attaches whatever it likes, namespaced under its
 * own id, and the runtime carries it verbatim back into the next request. Nothing in between has to
 * understand it — which is precisely why nothing in between can lose it:
 *
 * ```
 * {
 *   "anthropic": { "signature": "Er4BCkYIBRgCKkA…" }
 * }
 * ```
 *
 * Round-tripping signed reasoning across tool rounds is the failure mode every LLM abstraction in every
 * language currently has an open bug for. It is a modelling problem, not an implementation problem, and
 * this type is the fix.
 */
public typealias ProviderMetadata = Map<String, JsonObject>

/**
 * Provider-specific data flowing IN to a provider, in the same shape as [ProviderMetadata].
 *
 * Two names for one structure, deliberately, because the direction is the interesting part: options are
 * an input the caller chooses, metadata is an output the provider produced. A provider reads the entry
 * under its own id and ignores every other — so passing options for three providers to one of them is
 * well-defined, and switching provider never requires rewriting the call.
 */
public typealias ProviderOptions = Map<String, JsonObject>

/** The metadata/options entry belonging to [providerId], or null when the map carries none. */
public fun ProviderMetadata.forProvider(providerId: String): JsonObject? = this[providerId]

/**
 * A non-fatal problem with a call: a setting the provider had to ignore, a deprecated option, anything
 * else worth telling the caller without failing the request.
 *
 * Emitted once per call — as the first stream part, or on the result — rather than logged, because
 * "your `topK` did nothing" is information a UI may want to show, and a log line nobody reads is how
 * that gets lost. A provider that cannot proceed at all throws [UnsupportedFunctionalityError] instead.
 *
 * The four variants mirror the reference implementation's `SharedV4Warning` exactly, so a provider
 * ported from it needs no reinterpretation.
 */
public sealed interface Warning {

    /** A feature this provider or model does not support, and therefore did not send. */
    public data class Unsupported(
        /** What was asked for and not sent. */
        val feature: String,
        /** More, when the provider chooses to say. */
        val details: String? = null,
    ) : Warning

    /** Supported, but not the way the caller asked — a value clamped, a mode substituted. */
    public data class Compatibility(
        /** What was adjusted. */
        val feature: String,
        /** What was actually done instead. */
        val details: String? = null,
    ) : Warning

    /** The setting still works but is on its way out. */
    public data class Deprecated(
        /** The setting on its way out. */
        val setting: String,
        /** What to use instead. */
        val message: String,
    ) : Warning

    /** Anything else the provider wants to say about the call. */
    public data class Other(val message: String) : Warning
}
