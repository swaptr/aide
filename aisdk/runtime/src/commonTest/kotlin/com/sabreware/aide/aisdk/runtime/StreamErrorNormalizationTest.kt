package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the loop does with an error a provider reports mid-stream.
 *
 * The reference normalizes a stream error part before its loop sees it — but only a PLAIN payload:
 * an object with a `message` and maybe a status, which a JS provider emits because it cannot import the
 * error class. An `Error` instance passes through untouched. Here every [StreamPart.Error] carries a
 * [Throwable], and every provider's is an [APICallError] with `statusCode`, `isRetryable` and `data`
 * already on it, so "normalization" is the identity by construction. These tests pin that identity and
 * the retry decisions around it, which is the behaviour the normalization exists to serve.
 */
class StreamErrorNormalizationTest {

    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        var calls = 0

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = calls++
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = calls++
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

    private val overloaded = APICallError(
        message = "Overloaded",
        url = "https://api.example.com/v1/messages",
        statusCode = 529,
        isRetryable = true,
        data = buildJsonObject { put("type", "overloaded_error") },
    )

    private val partialThenError = listOf(
        StreamPart.TextStart("t"),
        StreamPart.TextDelta("t", "partial"),
        StreamPart.Error(overloaded),
    )

    @Test
    fun `a typed provider error reaches the caller exactly as the provider emitted it`() = runTest {
        val model = ScriptedModel(listOf(partialThenError))

        val events = streamText(model, prompt).toList()

        val error = events.filterIsInstance<RunEvent.Error>().single().error
        // The same instance — not a copy, not a wrapper, not a message: the status, the retry verdict
        // and the vendor payload all survive to whoever decides what to do about them.
        assertSame(overloaded, error)
        assertEquals(529, (error as APICallError).statusCode)
        assertTrue(error.isRetryable)
        assertEquals(buildJsonObject { put("type", "overloaded_error") }, error.data)
        // And the round kept what it had before the error.
        val result = events.filterIsInstance<RunEvent.Finish>().single().result
        assertEquals("partial", result.text)
    }

    @Test
    fun `an error reported mid-stream is not retried by the loop's retry policy, however retryable`() = runTest {
        val model = ScriptedModel(listOf(partialThenError))

        streamText(model, prompt, retry = RetryPolicy(maxRetries = 2, initialDelayMillis = 0)).toList()

        // `retry` wraps the call that OPENS the stream; an error part inside it costs the round and is
        // reported. Replaying would re-emit every part the collector already rendered, so recovery after
        // the stream began is its own, opt-in policy — `StreamRetries`, pinned in StreamRetriesTest.
        assertEquals(1, model.calls)
    }

    @Test
    fun `a retryable failure while opening the stream is retried under the loop's policy`() = runTest {
        val answer = listOf(
            StreamPart.TextStart("t"),
            StreamPart.TextDelta("t", "ok"),
            StreamPart.TextEnd("t"),
            StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
        )
        val model = object : LanguageModel {
            override val provider: String = "scripted"
            override val modelId: String = "scripted-1"
            var attempts = 0
            override suspend fun doStream(options: CallOptions): StreamResult {
                if (attempts++ == 0) throw overloaded
                return StreamResult(answer.asFlow())
            }
            override suspend fun doGenerate(options: CallOptions): GenerateResult = assembleGenerateResult(answer.asFlow())
        }

        val result = streamText(model, prompt, retry = RetryPolicy(maxRetries = 1, initialDelayMillis = 0))
            .toList().filterIsInstance<RunEvent.Finish>().single().result

        assertEquals(2, model.attempts)
        assertEquals("ok", result.text)
    }
}
