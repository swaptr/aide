package com.sabreware.aide.core.domain.llm.dispatch

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.gates.ToolTurnId
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.tools.ToolPrefs
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.ByteString.Companion.encodeUtf8

class ToolDispatcher(
    private val idempotency: IdempotencyCache,
    private val rateLimiter: RateLimiter,
    private val tracer: Tracer,
    private val confirmGate: WriteConfirmGate,
    private val userPrefs: PreferenceStore,
    /**
     * Where tool handlers run. Injected because commonMain cannot name `Dispatchers.IO`, and because this
     * is exactly the "policy is data" shape the rest of the graph uses.
     *
     * It matters: every device tool is a binder round-trip into a content provider, and those were running
     * on whatever dispatcher the streaming session happened to use — `Dispatchers.Default`, the CPU pool.
     * A blocking provider call there occupies a worker sized to the core count, and one slow OEM provider
     * can stall unrelated CPU work. Deciding it HERE also means every tool on every platform gets it,
     * rather than each toolset remembering.
     */
    private val handlerDispatcher: CoroutineDispatcher,
) {

    data class Context(
        val surface: Surface,
        val modelId: String,
        val turnId: String,
        val askBeforeEachTool: Boolean = false,
    )

    fun resetTurn(turnId: String) {
        rateLimiter.resetTurn(turnId)
    }

    fun forgetTurn(turnId: String) {
        rateLimiter.forget(turnId)
    }

    suspend fun dispatch(
        tool: AideTool.Function?,
        toolName: String,
        args: JsonObject,
        ctx: Context,
    ): JsonObject {
        val started = Clock.System.now().toEpochMilliseconds()
        val argsHash = canonicalArgsHash(args)

        if (tool == null) {
            val envelope = ToolEnvelope.failure("UNKNOWN_TOOL", "no tool named '$toolName'")
            tracer.record(
                buildSpan(
                    toolName = toolName, argsHash = argsHash, envelope = envelope,
                    durationMs = Clock.System.now().toEpochMilliseconds() - started,
                    ctx = ctx, idempotentHit = false, rateLimited = false,
                ),
            )
            return envelope
        }

        // Hard-deny: tools the user marked "never allow" are blocked outright, even with the gate off.
        if (userPrefs.flow(ToolPrefs.Denied).first().contains(tool.name)) {
            val envelope = ToolEnvelope.failure("TOOL_DENIED", "tool '${tool.name}' is set to never allow")
            tracer.record(
                buildSpan(
                    toolName = tool.name, argsHash = argsHash, envelope = envelope,
                    durationMs = Clock.System.now().toEpochMilliseconds() - started,
                    ctx = ctx, idempotentHit = false, rateLimited = false,
                ),
            )
            return envelope
        }

        // Confirm gate must precede cache + rate limiter — a cached envelope or denial
        // served without consent would defeat the toggle. CHAT-only: IME has no dialog host.
        // Tools the user marked "always allow" skip the prompt entirely.
        val alwaysAllowed = ctx.askBeforeEachTool && userPrefs.flow(ToolPrefs.AlwaysAllowed).first().contains(tool.name)
        if (ctx.askBeforeEachTool && ctx.surface == Surface.CHAT && !alwaysAllowed) {
            val prompt = WriteConfirmGate.Prompt(
                opId = "ask:${ctx.turnId}:${tool.name}:${argsHash.take(8)}",
                toolName = tool.name,
                summary = "Run ${tool.name}?",
                details = argsAsKeyValues(args),
                severity = WriteConfirmGate.Severity.INFO,
            )
            when (val r = withContext(ToolTurnId(ctx.turnId)) { confirmGate.await(prompt) }) {
                is WriteConfirmGate.Result.Approved -> {
                    // Persist "always allow this tool" so future turns skip the prompt.
                    if (r.remember) {
                        userPrefs.set(ToolPrefs.AlwaysAllowed, userPrefs.flow(ToolPrefs.AlwaysAllowed).first() + tool.name)
                    }
                }
                is WriteConfirmGate.Result.Denied -> {
                    // "Don't ask again" on a denial = never-allow this tool from now on.
                    if (r.remember && r.reason == WriteConfirmGate.Result.Reason.USER_REJECTED) {
                        userPrefs.set(ToolPrefs.Denied, userPrefs.flow(ToolPrefs.Denied).first() + tool.name)
                    }
                    val code = when (r.reason) {
                        WriteConfirmGate.Result.Reason.USER_REJECTED -> "USER_CANCELLED"
                        WriteConfirmGate.Result.Reason.TIMEOUT -> "CONFIRM_TIMEOUT"
                        WriteConfirmGate.Result.Reason.CANCELLED_BY_STOP -> "CANCELLED_BY_USER"
                        WriteConfirmGate.Result.Reason.NO_HOST -> "CONFIRM_UNAVAILABLE"
                    }
                    val envelope = ToolEnvelope.failure(
                        code,
                        "tool '${tool.name}' was not approved by the user",
                    )
                    tracer.record(
                        buildSpan(
                            toolName = tool.name, argsHash = argsHash, envelope = envelope,
                            durationMs = Clock.System.now().toEpochMilliseconds() - started,
                            ctx = ctx, idempotentHit = false, rateLimited = false,
                        ),
                    )
                    return envelope
                }
            }
        }

        // Scoped to the surface AND the turn. The cache exists to absorb a model re-issuing the SAME call
        // inside ONE turn; an unscoped `name:argsHash` key made it a five-minute cross-chat memo instead,
        // so an identical request in another chat was answered without the handler ever running.
        val key = "${ctx.surface.name}:${ctx.turnId}:${tool.name}:$argsHash"
        if (tool.readOnly) idempotency.get(key)?.let { cached ->
            val withFlag = appendFlag(cached, "idempotent_hit", true)
            tracer.record(
                buildSpan(
                    toolName = tool.name, argsHash = argsHash, envelope = withFlag,
                    durationMs = Clock.System.now().toEpochMilliseconds() - started,
                    ctx = ctx, idempotentHit = true, rateLimited = false,
                ),
            )
            return withFlag
        }

        val cap = tool.maxCallsPerTurn ?: rateLimiter.defaultCapFor(tool.name)
        when (val outcome = rateLimiter.acquire(tool.name, ctx.turnId, cap)) {
            RateLimiter.Outcome.Allowed -> Unit
            is RateLimiter.Outcome.Denied -> {
                val envelope = ToolEnvelope.failure(
                    "RATE_LIMITED",
                    "tool '${tool.name}' exceeded $cap calls this turn; retry next turn",
                ) { put("retry_in_seconds", JsonPrimitive(outcome.retryInSeconds)) }
                tracer.record(
                    buildSpan(
                        toolName = tool.name, argsHash = argsHash, envelope = envelope,
                        durationMs = Clock.System.now().toEpochMilliseconds() - started,
                        ctx = ctx, idempotentHit = false, rateLimited = true,
                    ),
                )
                return envelope
            }
        }

        val handlerStartedMs = Clock.System.now().toEpochMilliseconds()
        val envelope = try {
            // ToolTurnId rides along so a gate awaited INSIDE the handler (fs write confirm, contact pick)
            // routes its prompt to this turn's host, not to whichever surface bound an emitter last.
            withContext(handlerDispatcher + ToolTurnId(ctx.turnId)) { tool.handler(args) }
        } catch (ce: CancellationException) {
            throw ce // never swallow cancellation — propagate it up the coroutine hierarchy.
        } catch (t: Throwable) {
            AideLog.w(
                "AidePerf",
                "tool.handler-threw name=${tool.name} " +
                    "ms=${Clock.System.now().toEpochMilliseconds() - handlerStartedMs}: " +
                    "${t::class.simpleName}: ${t.message}",
                t,
            )
            ToolEnvelope.failure(
                "HANDLER_THREW",
                t.message ?: t::class.simpleName ?: "error",
            )
        }
        val handlerMs = Clock.System.now().toEpochMilliseconds() - handlerStartedMs

        if (tool.readOnly && shouldCache(envelope)) {
            idempotency.put(key, envelope)
        }

        val ok = (envelope["ok"] as? JsonPrimitive)?.content == "true"
        val errorCode = (envelope["errorCode"] as? JsonPrimitive)?.content
        AideLog.i(
            "AidePerf",
            "tool.dispatch name=${tool.name} ok=$ok " +
                "errorCode=${errorCode ?: "-"} handlerMs=$handlerMs " +
                "totalMs=${Clock.System.now().toEpochMilliseconds() - started} " +
                "surface=${ctx.surface} model=${ctx.modelId}",
        )

        tracer.record(
            buildSpan(
                toolName = tool.name, argsHash = argsHash, envelope = envelope,
                durationMs = Clock.System.now().toEpochMilliseconds() - started,
                ctx = ctx, idempotentHit = false, rateLimited = false,
            ),
        )
        return envelope
    }

    private fun buildSpan(
        toolName: String,
        argsHash: String,
        envelope: JsonObject,
        durationMs: Long,
        ctx: Context,
        idempotentHit: Boolean,
        rateLimited: Boolean,
    ): Tracer.Span = Tracer.Span(
        ts = Clock.System.now().toEpochMilliseconds(),
        tool = toolName,
        argsHash = argsHash,
        resultPreview = envelope.toString().take(Tracer.RESULT_PREVIEW_CHARS),
        ok = (envelope["ok"] as? JsonPrimitive)?.content == "true",
        errorCode = (envelope["errorCode"] as? JsonPrimitive)?.content,
        durationMs = durationMs,
        surface = ctx.surface.name,
        modelId = ctx.modelId,
        idempotentHit = idempotentHit,
        rateLimited = rateLimited,
        verified = (envelope["verified"] as? JsonPrimitive)?.contentOrNullBoolean(),
    )

    private fun shouldCache(envelope: JsonObject): Boolean {
        val ok = (envelope["ok"] as? JsonPrimitive)?.content == "true"
        if (ok) return true
        val code = (envelope["errorCode"] as? JsonPrimitive)?.content ?: return false
        return code !in NON_CACHEABLE_CODES
    }

    private fun appendFlag(envelope: JsonObject, key: String, value: Boolean): JsonObject =
        buildJsonObject {
            envelope.forEach { (k, v) -> put(k, v) }
            put(key, JsonPrimitive(value))
        }

    // Strip idempotency_key + __trace_id before hashing — cross-cutting hints, not args.
    private fun canonicalArgsHash(args: JsonObject): String {
        val stripped = JsonObject(
            args.filterKeys { it != "idempotency_key" && it != "__trace_id" },
        )
        val canonical = canonicalJson(stripped)
        return canonical.encodeUtf8().sha256().hex()
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "\"${escape(element.content)}\""
        else element.content
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "\"${escape(k)}\":${canonicalJson(v)}" }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun argsAsKeyValues(args: JsonObject): List<WriteConfirmGate.KeyValue> =
        args.entries
            .filter { (k, _) -> k != "idempotency_key" && k != "__trace_id" }
            .map { (k, v) ->
                val rendered = if (v is JsonPrimitive && v.isString) v.content
                else v.toString()
                WriteConfirmGate.KeyValue(k, rendered.take(200))
            }

    companion object {
        private val NON_CACHEABLE_CODES = setOf(
            "RATE_LIMITED",
            "CANCELLED_BY_USER",
            "CONFIRM_TIMEOUT",
            "USER_CANCELLED",
            "CONFIRM_UNAVAILABLE",
            "HANDLER_THREW",
        )
    }
}

private fun JsonPrimitive.contentOrNullBoolean(): Boolean? = when {
    isString -> null
    content == "true" -> true
    content == "false" -> false
    else -> null
}
