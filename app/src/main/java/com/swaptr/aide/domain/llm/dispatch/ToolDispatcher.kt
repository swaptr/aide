package com.swaptr.aide.domain.llm.dispatch

import android.util.Log
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.gates.WriteConfirmGate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolDispatcher @Inject constructor(
    private val idempotency: IdempotencyCache,
    private val rateLimiter: RateLimiter,
    private val tracer: Tracer,
    private val confirmGate: WriteConfirmGate,
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

    fun dispatch(
        tool: AideTool.Function?,
        toolName: String,
        args: JsonObject,
        ctx: Context,
    ): JsonObject {
        val started = System.currentTimeMillis()
        val argsHash = canonicalArgsHash(args)

        if (tool == null) {
            val envelope = ToolEnvelope.failure("UNKNOWN_TOOL", "no tool named '$toolName'")
            tracer.record(
                buildSpan(
                    toolName = toolName, argsHash = argsHash, envelope = envelope,
                    durationMs = System.currentTimeMillis() - started,
                    ctx = ctx, idempotentHit = false, rateLimited = false,
                ),
            )
            return envelope
        }

        // Confirm gate must precede cache + rate limiter — a cached envelope or denial
        // served without consent would defeat the toggle. CHAT-only: IME has no dialog host.
        if (ctx.askBeforeEachTool && ctx.surface == Surface.CHAT) {
            val prompt = WriteConfirmGate.Prompt(
                opId = "ask:${ctx.turnId}:${tool.name}:${argsHash.take(8)}",
                toolName = tool.name,
                summary = "Run ${tool.name}?",
                details = argsAsKeyValues(args),
                severity = WriteConfirmGate.Severity.INFO,
            )
            when (val r = confirmGate.await(prompt)) {
                WriteConfirmGate.Result.Approved -> Unit
                is WriteConfirmGate.Result.Denied -> {
                    val code = when (r.reason) {
                        WriteConfirmGate.Result.Reason.USER_REJECTED -> "USER_CANCELLED"
                        WriteConfirmGate.Result.Reason.TIMEOUT -> "CONFIRM_TIMEOUT"
                        WriteConfirmGate.Result.Reason.CANCELLED_BY_STOP -> "CANCELLED_BY_USER"
                    }
                    val envelope = ToolEnvelope.failure(
                        code,
                        "tool '${tool.name}' was not approved by the user",
                    )
                    tracer.record(
                        buildSpan(
                            toolName = tool.name, argsHash = argsHash, envelope = envelope,
                            durationMs = System.currentTimeMillis() - started,
                            ctx = ctx, idempotentHit = false, rateLimited = false,
                        ),
                    )
                    return envelope
                }
            }
        }

        val key = "${tool.name}:$argsHash"
        idempotency.get(key)?.let { cached ->
            val withFlag = appendFlag(cached, "idempotent_hit", true)
            tracer.record(
                buildSpan(
                    toolName = tool.name, argsHash = argsHash, envelope = withFlag,
                    durationMs = System.currentTimeMillis() - started,
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
                        durationMs = System.currentTimeMillis() - started,
                        ctx = ctx, idempotentHit = false, rateLimited = true,
                    ),
                )
                return envelope
            }
        }

        val handlerStartedMs = System.currentTimeMillis()
        val envelope = runCatching { tool.handler(args) }.getOrElse {
            Log.w(
                "AidePerf",
                "tool.handler-threw name=${tool.name} " +
                    "ms=${System.currentTimeMillis() - handlerStartedMs}: " +
                    "${it.javaClass.simpleName}: ${it.message}",
                it,
            )
            ToolEnvelope.failure(
                "HANDLER_THREW",
                it.message ?: it.javaClass.simpleName,
            )
        }
        val handlerMs = System.currentTimeMillis() - handlerStartedMs

        if (shouldCache(envelope)) {
            idempotency.put(key, envelope)
        }

        val ok = (envelope["ok"] as? JsonPrimitive)?.content == "true"
        val errorCode = (envelope["errorCode"] as? JsonPrimitive)?.content
        Log.i(
            "AidePerf",
            "tool.dispatch name=${tool.name} ok=$ok " +
                "errorCode=${errorCode ?: "-"} handlerMs=$handlerMs " +
                "totalMs=${System.currentTimeMillis() - started} " +
                "surface=${ctx.surface} model=${ctx.modelId}",
        )

        tracer.record(
            buildSpan(
                toolName = tool.name, argsHash = argsHash, envelope = envelope,
                durationMs = System.currentTimeMillis() - started,
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
        ts = System.currentTimeMillis(),
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
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
