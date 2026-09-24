package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.SpeechTranslationModel
import com.sabreware.aide.aisdk.SpeechTranslationStreamOptions
import com.sabreware.aide.aisdk.SpeechTranslationStreamPart
import com.sabreware.aide.aisdk.SpeechTranslationStreamResult
import com.sabreware.aide.aisdk.SpeechTranslationUsage
import com.sabreware.aide.aisdk.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The translation wrapper, over a scripted model.
 *
 * A fake rather than a provider because what is under test is the two things the wrapper adds to a bare
 * `doStream`: that a session which said nothing fails instead of returning an empty success, and that
 * the summary keeps both text channels apart.
 */
class StreamTranslateTest {

    @Test
    fun `every part reaches the collector, warnings and finish included`() = runTest {
        val model = model(
            SpeechTranslationStreamPart.StreamStart(listOf(Warning.Unsupported("sourceLanguage"))),
            SpeechTranslationStreamPart.SourceTranscriptFinal("Hello", "0"),
            SpeechTranslationStreamPart.OutputTextFinal("Hola", "0"),
            SpeechTranslationStreamPart.Finish(sourceText = "Hello", outputText = "Hola"),
        )

        val parts = streamTranslate(model, options()).toList()

        assertEquals(4, parts.size)
        assertEquals(
            listOf(Warning.Unsupported("sourceLanguage")),
            (parts.first() as SpeechTranslationStreamPart.StreamStart).warnings,
        )
    }

    @Test
    fun `the summary keeps what was said apart from what it means`() = runTest {
        val model = model(
            SpeechTranslationStreamPart.StreamStart(listOf(Warning.Unsupported("outputAudioFormat"))),
            SpeechTranslationStreamPart.SourceTranscriptDelta("Hello", "0"),
            SpeechTranslationStreamPart.OutputTextDelta("Hola", "0"),
            SpeechTranslationStreamPart.Finish(
                sourceText = "Hello",
                outputText = "Hola",
                durationInSeconds = 1.5,
                usage = SpeechTranslationUsage(inputAudioTokens = 10, outputAudioTokens = 20),
            ),
        )

        val result = translate(model, options())

        assertEquals("Hello", result.sourceText)
        assertEquals("Hola", result.translationText)
        assertEquals(1.5, result.durationInSeconds)
        assertEquals(SpeechTranslationUsage(inputAudioTokens = 10, outputAudioTokens = 20), result.usage)
        assertEquals(listOf(Warning.Unsupported("outputAudioFormat")), result.warnings)
    }

    @Test
    fun `the envelope's response stands in for a socket protocol that never sends one`() = runTest {
        val model = model(SpeechTranslationStreamPart.Finish(sourceText = "Hello", outputText = "Hola"))

        assertEquals(
            ResponseInfo(ResponseMetadata(modelId = "test-model")),
            translate(model, options()).response,
        )
    }

    @Test
    fun `a mid-stream response part refines the envelope`() = runTest {
        val model = model(
            SpeechTranslationStreamPart.ResponseMetadataPart(ResponseMetadata(id = "session-7")),
            SpeechTranslationStreamPart.Finish(sourceText = "Hello", outputText = "Hola"),
        )

        assertEquals(
            ResponseInfo(ResponseMetadata(id = "session-7")),
            translate(model, options()).response,
        )
    }

    @Test
    fun `a session that produced neither audio nor text fails rather than returning an empty result`() =
        runTest {
            val model = model(SpeechTranslationStreamPart.Finish(sourceText = "", outputText = ""))

            assertFailsWith<NoTranslationGeneratedError> { translate(model, options()) }
            assertFailsWith<NoTranslationGeneratedError> {
                streamTranslate(model, options()).toList()
            }
        }

    /**
     * An audio-only vendor reports no output text at all, so the emptiness check must look at both
     * channels: failing on the text alone would reject every translation that was spoken and not
     * transcribed.
     */
    @Test
    fun `an audio-only translation with no output text is a success`() = runTest {
        val model = model(
            SpeechTranslationStreamPart.Audio(byteArrayOf(1, 2, 3), "0"),
            SpeechTranslationStreamPart.Finish(sourceText = "Hello", outputText = ""),
        )

        assertEquals("", translate(model, options()).translationText)
    }

    @Test
    fun `a stream that ends without a finish part is a truncation, not a short translation`() = runTest {
        val model = model(
            SpeechTranslationStreamPart.Audio(byteArrayOf(1, 2, 3), "0"),
            SpeechTranslationStreamPart.OutputTextDelta("Ho", "0"),
        )

        assertFailsWith<NoTranslationGeneratedError> { streamTranslate(model, options()).toList() }
    }

    private fun model(vararg parts: SpeechTranslationStreamPart) = object : SpeechTranslationModel {
        override val provider: String = "test"
        override val modelId: String = "test-model"
        override suspend fun doStream(
            options: SpeechTranslationStreamOptions,
        ): SpeechTranslationStreamResult = SpeechTranslationStreamResult(
            stream = if (parts.isEmpty()) emptyFlow() else flowOf(*parts),
            response = ResponseInfo(ResponseMetadata(modelId = modelId)),
        )
    }

    private fun options() = SpeechTranslationStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = AudioFormat("audio/pcm", 16_000),
        targetLanguage = "es",
    )
}
