package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.TranscriptionStreamResult
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The stream-transcribe wrapper: what it adds over a bare `doStream` is exactly two checks, and both
 * exist because their failure mode is a SUCCESSFUL call — an empty transcript saved, or a fragment
 * mistaken for the whole recording.
 */
class StreamTranscribeTest {

    private class StreamingModel(private val parts: List<TranscriptionStreamPart>?) : TranscriptionModel {
        override val provider: String = "t"
        override val modelId: String = "t-live"

        override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult =
            error("batch path not under test")

        override suspend fun doStream(options: TranscriptionStreamOptions): TranscriptionStreamResult? =
            parts?.let { TranscriptionStreamResult(stream = it.asFlow()) }
    }

    private val options = TranscriptionStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = AudioFormat("audio/pcm", rate = 16_000),
    )

    @Test
    fun `parts are forwarded in order, partials and finals alike`() = runTest {
        val script = listOf(
            TranscriptionStreamPart.StreamStart(),
            TranscriptionStreamPart.TranscriptPartial("hel"),
            TranscriptionStreamPart.TranscriptFinal("hello"),
            TranscriptionStreamPart.Finish("hello"),
        )

        val parts = streamTranscribe(StreamingModel(script), options).toList()

        assertEquals(script, parts)
    }

    @Test
    fun `a model with no streaming endpoint fails loudly, not with an empty flow`() = runTest {
        // The null from doStream is a capability answer for a caller CHOOSING a model. A caller that
        // already chose and got silence would blame the microphone.
        assertFailsWith<UnsupportedFunctionalityError> {
            streamTranscribe(StreamingModel(parts = null), options).toList()
        }
    }

    @Test
    fun `a finish with neither text nor segments is a failure, not an empty success`() = runTest {
        val script = listOf(
            TranscriptionStreamPart.StreamStart(),
            TranscriptionStreamPart.Finish(""),
        )

        assertFailsWith<NoTranscriptGeneratedError> {
            streamTranscribe(StreamingModel(script), options).toList()
        }
    }

    @Test
    fun `segments without joined text are legitimate — a diarizing vendor's shape`() = runTest {
        val script = listOf(
            TranscriptionStreamPart.Finish(
                text = "",
                segments = listOf(TranscriptionResult.Segment("hello", 0.0, 1.0)),
            ),
        )

        val parts = streamTranscribe(StreamingModel(script), options).toList()

        assertEquals(1, parts.size)
    }

    @Test
    fun `a stream that ends without a finish was cut off, and says so`() = runTest {
        val script = listOf(
            TranscriptionStreamPart.StreamStart(),
            TranscriptionStreamPart.TranscriptFinal("half a sent"),
        )

        assertFailsWith<NoTranscriptGeneratedError> {
            streamTranscribe(StreamingModel(script), options).toList()
        }
    }
}
