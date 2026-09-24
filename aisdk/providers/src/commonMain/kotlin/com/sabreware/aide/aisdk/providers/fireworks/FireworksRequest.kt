package com.sabreware.aide.aisdk.providers.fireworks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one correction Fireworks' chat body needs, and a note on the one it does NOT.
 *
 * **Validated against Fireworks' own API reference (`docs.fireworks.ai/api-reference/post-chatcompletions`,
 * checked 2026-09-01), not against the vendored port.** Its `reasoning_effort` schema accepts
 * `low` · `medium` · `high` · `xhigh` · `max` · `none` · `adaptive`, plus an integer token budget and a
 * boolean. `minimal` — which OpenAI accepts and this library's shared mapper therefore emits — is not on
 * that list, so it is the single value that has to move. It becomes `low`, the nearest level Fireworks
 * serves.
 *
 * **Where the vendored reference is now stale:** it clamps `xhigh` down to `high` on the premise that
 * "Fireworks supports only three of OpenAI's five levels". Fireworks accepts `xhigh` today, so that clamp
 * would silently downgrade a request a caller paid for. It is deliberately not ported.
 *
 * Note that this library's shared mapper already collapses `XHigh` to `high` for OpenAI's narrower
 * vocabulary, and by the time a body reaches here the two are indistinguishable — so a caller who wants
 * Fireworks' `xhigh`, `max` or `adaptive` sets `reasoning_effort` through `providerOptions["fireworks"]`,
 * which is spread after the model's own fields and therefore wins.
 *
 * Nothing renames a caller's own keys. This port spreads `providerOptions` verbatim, so a key spelled the
 * way Fireworks documents it already arrives correctly; the reference's camelCase-to-snake_case pass
 * exists to repair its own camelCase spread and would, here, rename keys that were already right.
 */
internal fun fireworksRequestBody(body: JsonObject): JsonObject {
    val effort = (body["reasoning_effort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (effort != "minimal") return body
    return JsonObject(body + ("reasoning_effort" to JsonPrimitive("low")))
}
