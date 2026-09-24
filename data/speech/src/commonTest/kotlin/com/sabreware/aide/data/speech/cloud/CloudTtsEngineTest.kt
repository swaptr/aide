package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.audio.AudioSynthesisEngine
import com.sabreware.aide.core.domain.audio.AudioSynthesisOptions
import com.sabreware.aide.core.domain.audio.SynthesizedAudio
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.TtsOptions
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.data.speech.audio.WavCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class CloudTtsEngineTest {

    private class RecordingSynthesis(
        private val answer: () -> ByteArray,
        private val fail: Throwable? = null,
    ) : AudioSynthesisEngine {
        var lastModel: String? = null
        var lastText: String? = null
        var lastOptions: AudioSynthesisOptions? = null

        override suspend fun synthesize(modelName: String, text: String, options: AudioSynthesisOptions): SynthesizedAudio {
            lastModel = modelName
            lastText = text
            lastOptions = options
            fail?.let { throw it }
            return SynthesizedAudio(answer(), "audio/pcm")
        }
    }

    /** One second of a ramp at [rate], as raw s16le. */
    private fun pcm(samples: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        for (i in 0 until samples) {
            val value = ((i % 200) - 100) * 100 // -10000..9900
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    @Test
    fun `raw pcm is chunked at the injected rate and ends Done`() = runTest {
        val synthesis = RecordingSynthesis({ pcm(24_000 + 500) })
        val engine = CloudTtsEngine(synthesis, { "gpt-4o-mini-tts" }, CloudTtsFormat.OpenAi, voice = "alloy")

        val events = engine.synthesize("Hello.", TtsOptions()).toList()

        val chunks = events.filterIsInstance<TtsStreamEvent.AudioChunk>()
        assertEquals(TtsStreamEvent.End(SpeechStreamOutcome.Done), events.last())
        assertEquals(26, chunks.size, "25 full 40 ms chunks and one short tail")
        assertTrue(chunks.dropLast(1).all { it.pcm.size == 960 && it.sampleRate == 24_000 })
        assertEquals(500, chunks.last().pcm.size)
        assertTrue(chunks.all { chunk -> chunk.pcm.all { it in -1f..1f } })
        assertEquals("gpt-4o-mini-tts", synthesis.lastModel)
        assertEquals("Hello.", synthesis.lastText)
        assertEquals("pcm", synthesis.lastOptions?.outputFormat)
        assertEquals("alloy", synthesis.lastOptions?.voice)
    }

    @Test
    fun `a wav answer takes its rate from the header`() = runTest {
        val wav = WavCodec.encode(FloatArray(8_000) { 0.1f }, 8_000)
        val engine = CloudTtsEngine(
            RecordingSynthesis({ wav }),
            { "m" },
            CloudTtsFormat("wav", 24_000, PcmContainer.Wav),
            voice = null,
        )

        val chunks = engine.synthesize("x", TtsOptions()).toList().filterIsInstance<TtsStreamEvent.AudioChunk>()

        assertTrue(chunks.all { it.sampleRate == 8_000 })
        assertEquals(8_000, chunks.sumOf { it.pcm.size })
        assertEquals(320, chunks.first().pcm.size, "40 ms at 8 kHz")
    }

    @Test
    fun `the shared voice preference is ignored in favour of the vendor's voice`() = runTest {
        val synthesis = RecordingSynthesis({ pcm(100) })
        val engine = CloudTtsEngine(synthesis, { "m" }, CloudTtsFormat.ElevenLabs, voice = "21m00Tcm4TlvDq8ikWAM")

        engine.synthesize("x", TtsOptions(voiceId = "en-us-x-sfg#male_1-local")).toList()

        assertEquals("21m00Tcm4TlvDq8ikWAM", synthesis.lastOptions?.voice)
        assertEquals("pcm_24000", synthesis.lastOptions?.outputFormat)
    }

    @Test
    fun `speed is sent only when the rate is not natural`() = runTest {
        val synthesis = RecordingSynthesis({ pcm(100) })
        val engine = CloudTtsEngine(synthesis, { "m" }, CloudTtsFormat.OpenAi, voice = null)

        engine.synthesize("x", TtsOptions(rate = 1.0f)).toList()
        assertNull(synthesis.lastOptions?.speed)

        engine.synthesize("x", TtsOptions(rate = 1.25f)).toList()
        assertEquals(1.25, synthesis.lastOptions?.speed)
    }

    @Test
    fun `a vendor failure is one End Error and nothing else`() = runTest {
        val engine = CloudTtsEngine(
            RecordingSynthesis({ pcm(1) }, fail = IllegalStateException("429 rate limited")),
            { "m" },
            CloudTtsFormat.OpenAi,
            voice = null,
        )

        val events = engine.synthesize("x", TtsOptions()).toList()

        val outcome = ((events.single() as TtsStreamEvent.End).outcome as SpeechStreamOutcome.Error)
        assertEquals("429 rate limited", outcome.message)
    }

    @Test
    fun `an empty answer is an error rather than silence`() = runTest {
        val engine = CloudTtsEngine(RecordingSynthesis({ ByteArray(0) }), { "m" }, CloudTtsFormat.OpenAi, voice = null)

        val events = engine.synthesize("x", TtsOptions()).toList()

        assertTrue((events.single() as TtsStreamEvent.End).outcome is SpeechStreamOutcome.Error)
    }

    @Test
    fun `it streams pcm and holds nothing resident`() {
        val engine = CloudTtsEngine(RecordingSynthesis({ pcm(1) }), { "m" }, CloudTtsFormat.OpenAi, voice = null)
        assertTrue(engine.supportsStreamingPcm)
        assertNull(engine.loadedModelId)
    }
}
