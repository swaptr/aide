package com.sabreware.aide.core.domain.llm.gates

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The turn a tool handler is running for, carried on the coroutine context so gates deep inside a handler
 * ([WriteConfirmGate], [com.sabreware.aide.core.domain.tools.phone.ContactPickGate]) can route their prompt
 * to the host bound by THAT turn rather than to whichever surface bound most recently. `ToolDispatcher`
 * installs it around every dispatch; nothing else should.
 *
 * Threading it as a context element instead of a parameter keeps `AideTool.Function.handler` at
 * `(JsonObject) -> JsonObject` — the alternative was adding a turn parameter to every handler on every
 * toolset for the benefit of the two that prompt.
 */
class ToolTurnId(val id: String) : AbstractCoroutineContextElement(ToolTurnId) {
    companion object Key : CoroutineContext.Key<ToolTurnId>
}
