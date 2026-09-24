package com.sabreware.aide.core.domain.llm.dispatch

import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What the idempotency cache may and may not skip.
 *
 * The cache exists to absorb a model re-issuing the SAME call inside ONE turn. It used to do much more than
 * that: `cacheable` defaulted to **true**, so every tool that did not think about it was memoized, and the
 * key was `"${tool.name}:$argsHash"` with no chat, surface or turn in it. Asking to text Bob "running late"
 * in one chat and again in another four minutes later returned the first call's success envelope — the SMS
 * handler never ran and the model reported success.
 *
 * Two properties hold that shut: a tool is memoized only if it declares itself read-only, and the key is
 * scoped to the turn.
 */
class ToolDispatcherCacheTest {

    private fun dispatcher() = ToolDispatcher(
        idempotency = IdempotencyCache(),
        rateLimiter = RateLimiter(),
        tracer = Tracer(),
        confirmGate = WriteConfirmGate(),
        userPrefs = FakePreferenceStore(),
        // Inline, so the test scheduler stays in control of virtual time.
        handlerDispatcher = Dispatchers.Unconfined,
    )

    /** A tool that counts how many times it actually ran. */
    private class CountingTool(readOnly: Boolean) {
        var calls = 0
            private set

        val tool: AideTool.Function = AideTool.Function(
            name = "do_thing",
            description = "counts its invocations",
            parametersSchema = buildJsonObject {},
            readOnly = readOnly,
            handler = {
                calls++
                ToolEnvelope.success { put("calls", calls) }
            },
            surfaces = setOf(Surface.CHAT),
        )
    }

    private fun context(turnId: String, surface: Surface = Surface.CHAT) =
        ToolDispatcher.Context(surface = surface, modelId = "m", turnId = turnId)

    private val args: JsonObject = buildJsonObject { put("to", "bob") }

    @Test
    fun `a read-only tool repeated in one turn runs once`() = runTest {
        val counting = CountingTool(readOnly = true)
        val dispatcher = dispatcher()
        val ctx = context("turn-1")

        val first = dispatcher.dispatch(counting.tool, "do_thing", args, ctx)
        val second = dispatcher.dispatch(counting.tool, "do_thing", args, ctx)

        assertEquals(1, counting.calls, "the second identical call inside one turn should be served from cache")
        assertEquals("true", first["ok"]?.jsonPrimitive?.content)
        assertEquals(true, second["idempotent_hit"]?.jsonPrimitive?.content?.toBoolean())
    }

    /** The C4 regression. A tool that changes the world runs every time it is asked to. */
    @Test
    fun `a mutating tool is never served from cache`() = runTest {
        val counting = CountingTool(readOnly = false)
        val dispatcher = dispatcher()
        val ctx = context("turn-1")

        dispatcher.dispatch(counting.tool, "do_thing", args, ctx)
        dispatcher.dispatch(counting.tool, "do_thing", args, ctx)

        assertEquals(2, counting.calls, "a tool that is not read-only must run every time it is dispatched")
    }

    /** The other half of C4: the key carries the turn, so a later turn is a different question. */
    @Test
    fun `a read-only tool repeated in a different turn runs again`() = runTest {
        val counting = CountingTool(readOnly = true)
        val dispatcher = dispatcher()

        dispatcher.dispatch(counting.tool, "do_thing", args, context("turn-1"))
        dispatcher.dispatch(counting.tool, "do_thing", args, context("turn-2"))

        assertEquals(2, counting.calls, "the cache is turn-scoped; another turn must not read turn-1's answer")
    }

    /** And the surface, so the keyboard and the chat never answer each other's questions. */
    @Test
    fun `a read-only tool repeated on a different surface runs again`() = runTest {
        val counting = CountingTool(readOnly = true)
        val dispatcher = dispatcher()

        dispatcher.dispatch(counting.tool, "do_thing", args, context("turn-1", Surface.CHAT))
        dispatcher.dispatch(counting.tool, "do_thing", args, context("turn-1", Surface.IME))

        assertEquals(2, counting.calls, "the cache is surface-scoped as well as turn-scoped")
    }

    @Test
    fun `different arguments are different questions`() = runTest {
        val counting = CountingTool(readOnly = true)
        val dispatcher = dispatcher()
        val ctx = context("turn-1")

        dispatcher.dispatch(counting.tool, "do_thing", buildJsonObject { put("to", "bob") }, ctx)
        dispatcher.dispatch(counting.tool, "do_thing", buildJsonObject { put("to", "alice") }, ctx)

        assertEquals(2, counting.calls)
    }

    /**
     * The rate limiter's shape: the cap is per turn, and exceeding it is a refusal the model can act on
     * rather than a silent drop. Read-only is off here so the cache cannot mask the limiter.
     */
    @Test
    fun `exceeding the per-turn cap is refused with a retryable code`() = runTest {
        val counting = CountingTool(readOnly = false)
        val capped = counting.tool.copy(maxCallsPerTurn = 2)
        val dispatcher = dispatcher()
        val ctx = context("turn-1")

        dispatcher.dispatch(capped, "do_thing", args, ctx)
        dispatcher.dispatch(capped, "do_thing", args, ctx)
        val refused = dispatcher.dispatch(capped, "do_thing", args, ctx)

        assertEquals(2, counting.calls, "the handler must not run past its cap")
        assertEquals("false", refused["ok"]?.jsonPrimitive?.content)
        assertEquals("RATE_LIMITED", refused["errorCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the cap is per turn, so the next turn starts fresh`() = runTest {
        val counting = CountingTool(readOnly = false)
        val capped = counting.tool.copy(maxCallsPerTurn = 1)
        val dispatcher = dispatcher()

        dispatcher.dispatch(capped, "do_thing", args, context("turn-1"))
        dispatcher.resetTurn("turn-2")
        dispatcher.dispatch(capped, "do_thing", args, context("turn-2"))

        assertEquals(2, counting.calls)
    }
}
