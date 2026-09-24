package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * Middleware ordering, which is the part that is invisible until it is wrong.
 *
 * `withMiddleware(a, b)` puts `a` outermost, so `a` observes whatever `b` does — including `b` serving a
 * cache hit. Reversed, an outer logger only ever sees misses and its numbers quietly disagree with
 * reality.
 */
class MiddlewareTest {

    private class RecordingModel(val calls: MutableList<String>) : LanguageModel {
        override val provider: String = "base"
        override val modelId: String = "base-1"

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            calls += "model"
            return GenerateResult(
                content = listOf(Content.Text("from model")),
                finishReason = FinishReason(FinishReason.Unified.Stop),
                usage = Usage(),
            )
        }

        override suspend fun doStream(options: CallOptions): StreamResult = StreamResult(emptyFlow())
    }

    private class Tracer(private val tag: String, private val calls: MutableList<String>) :
        LanguageModelMiddleware {
        override suspend fun wrapGenerate(
            params: CallOptions,
            model: LanguageModel,
            doGenerate: suspend () -> GenerateResult,
        ): GenerateResult {
            calls += "$tag:enter"
            return doGenerate().also { calls += "$tag:exit" }
        }
    }

    /** Answers without delegating — the case that makes ordering observable. */
    private class ShortCircuit(private val calls: MutableList<String>) : LanguageModelMiddleware {
        override suspend fun wrapGenerate(
            params: CallOptions,
            model: LanguageModel,
            doGenerate: suspend () -> GenerateResult,
        ): GenerateResult {
            calls += "cache:hit"
            return GenerateResult(
                content = listOf(Content.Text("from cache")),
                finishReason = FinishReason(FinishReason.Unified.Stop),
                usage = Usage(),
            )
        }
    }

    private val emptyCall = CallOptions(prompt = listOf(ModelMessage.System("s")))

    @Test
    fun `the first middleware listed is the outermost`() = runTest {
        val calls = mutableListOf<String>()
        val model = RecordingModel(calls).withMiddleware(Tracer("outer", calls), Tracer("inner", calls))

        model.doGenerate(emptyCall)

        assertEquals(
            listOf("outer:enter", "inner:enter", "model", "inner:exit", "outer:exit"),
            calls,
        )
    }

    @Test
    fun `an outer middleware still observes an inner short circuit`() = runTest {
        val calls = mutableListOf<String>()
        val model = RecordingModel(calls).withMiddleware(Tracer("log", calls), ShortCircuit(calls))

        val result = model.doGenerate(emptyCall)

        // The logger saw the call even though the model was never reached — the reason order matters.
        assertEquals(listOf("log:enter", "cache:hit", "log:exit"), calls)
        assertEquals("from cache", (result.content.single() as Content.Text).text)
    }

    @Test
    fun `overrides apply and undeclared members pass through`() = runTest {
        val renaming = object : LanguageModelMiddleware {
            override fun overrideModelId(model: LanguageModel): String = "renamed"
        }
        val model = RecordingModel(mutableListOf()).withMiddleware(renaming)

        assertEquals("renamed", model.modelId)
        // provider was not overridden, so it passes through untouched.
        assertEquals("base", model.provider)
    }

    @Test
    fun `transformParams reaches the model`() = runTest {
        val seen = mutableListOf<Double?>()
        val clamping = object : LanguageModelMiddleware {
            override suspend fun transformParams(
                type: LanguageModelMiddleware.CallType,
                params: CallOptions,
                model: LanguageModel,
            ): CallOptions = params.copy(temperature = 0.0)
        }
        val recording = object : LanguageModel {
            override val provider: String = "p"
            override val modelId: String = "m"
            override suspend fun doGenerate(options: CallOptions): GenerateResult {
                seen += options.temperature
                return GenerateResult(emptyList(), FinishReason(FinishReason.Unified.Stop), Usage())
            }
            override suspend fun doStream(options: CallOptions): StreamResult = StreamResult(emptyFlow())
        }

        recording.withMiddleware(clamping).doGenerate(emptyCall.copy(temperature = 1.9))

        assertEquals(listOf<Double?>(0.0), seen)
    }
}
