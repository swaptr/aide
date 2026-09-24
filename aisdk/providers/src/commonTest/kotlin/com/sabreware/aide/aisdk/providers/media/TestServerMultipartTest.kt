package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.fishaudio.FishAudioProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * The harness's own multipart reader, pinned in both directions.
 *
 * A multipart upload is a `WriteChannelContent`, so a body reader written as a `when` over `TextContent`
 * and `ByteArrayContent` yields "" for it — every `assertMultipartField` then compares against null and
 * an entire family of tests becomes a green no-op. Six of this module's transcription vendors send their
 * whole job description as multipart fields, so an assertion that cannot fail there is worse than no
 * assertion: it reports that Rev AI names its transcriber and Fish Audio asks for timestamps when
 * neither field was ever on the wire.
 *
 * Hence both halves. The positive half proves a sent field is READ; the negative half proves an unsent
 * one FAILS, which is the property the first half cannot establish on its own.
 */
class TestServerMultipartTest {

    private fun transcribe(server: TestServer) =
        FishAudioProvider(server.http(), "k", FishAudioProvider.DEFAULT_BASE_URL, emptyMap())
            .transcriptionModel("asr")

    @Test
    fun `a multipart field a provider sent is read back with its value`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"hi"}"""))

        transcribe(server).doGenerate(
            TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"),
        )

        val call = server.request()
        assertEquals(mapOf("ignore_timestamps" to "false"), call.multipart)
        call.assertMultipartField("ignore_timestamps", "false")
    }

    @Test
    fun `a multipart field the provider never sent fails the assertion`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"hi"}"""))

        transcribe(server).doGenerate(
            TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"),
        )

        // The file part carries `filename=`, so it is skipped rather than decoded — a binary part read
        // as a field value would put megabytes of audio into an assertion message.
        val call = server.request()
        assertFailsWith<AssertionError> { call.assertMultipartField("language", "en") }
        assertFailsWith<AssertionError> { call.assertMultipartField("audio", "AUDIO") }
        // A field that IS present still fails against the wrong value, rather than matching loosely.
        assertFailsWith<AssertionError> { call.assertMultipartField("ignore_timestamps", "true") }
    }
}
