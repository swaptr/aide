package com.sabreware.aide.core.domain.llm.dispatch

import kotlinx.coroutines.Dispatchers
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.tools.ToolPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Per-tool "always allow": a remembered tool skips the confirm gate; an un-remembered one still gates. */
class ToolDispatcherConfirmTest {

    private val tool = AideTool.Function(
        name = "do_thing",
        description = "",
        parametersSchema = buildJsonObject {},
        handler = { ToolEnvelope.success {} },
        surfaces = setOf(Surface.CHAT),
    )

    private fun dispatcher(alwaysAllowed: Set<String> = emptySet(), denied: Set<String> = emptySet()) =
        ToolDispatcher(
            IdempotencyCache(), RateLimiter(), Tracer(), WriteConfirmGate(),
            FakePreferenceStore(ToolPrefs.AlwaysAllowed to alwaysAllowed, ToolPrefs.Denied to denied),
            // Handlers run inline so the test scheduler stays in control of virtual time.
            Dispatchers.Unconfined,
        )

    private val ctx = ToolDispatcher.Context(
        surface = Surface.CHAT, modelId = "m", turnId = "t", askBeforeEachTool = true,
    )

    @Test
    fun alwaysAllowedToolSkipsTheConfirmGate() = runTest {
        val out = dispatcher(setOf("do_thing")).dispatch(tool, "do_thing", buildJsonObject {}, ctx)
        assertEquals(true, out["ok"]?.jsonPrimitive?.boolean, "remembered tool runs without prompting")
    }

    @Test
    fun deniedToolIsBlockedOutright() = runTest {
        val out = dispatcher(denied = setOf("do_thing")).dispatch(tool, "do_thing", buildJsonObject {}, ctx)
        assertEquals(false, out["ok"]?.jsonPrimitive?.boolean)
        assertEquals("TOOL_DENIED", out["errorCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun unrememberedToolStillGates() = runTest {
        // No confirm emitter is bound, so nothing could have approved this — the tool is NOT run.
        //
        // The code is CONFIRM_UNAVAILABLE rather than CONFIRM_TIMEOUT: the gate used to emit into a no-op
        // and then suspend for the full sixty seconds to reach the same answer, which is what made a chat
        // turn's unbind silently freeze a concurrent voice turn's tool. "Nobody can answer" is knowable
        // immediately and is a different fact from "nobody answered in time".
        val out: JsonObject = dispatcher(emptySet()).dispatch(tool, "do_thing", buildJsonObject {}, ctx)
        assertEquals(false, out["ok"]?.jsonPrimitive?.boolean)
        assertEquals("CONFIRM_UNAVAILABLE", out["errorCode"]?.jsonPrimitive?.content)
    }
}
