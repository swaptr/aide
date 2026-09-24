package com.sabreware.aide.aisdk.providers.gladia

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.media.GladiaFixtures
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * `c889fd5`: the reference's result schema used to strip every utterance field it had not declared —
 * speaker, confidence, language, words — before the payload reached `providerMetadata`. This port hands
 * the job payload over verbatim, so the fields were never lost here; this pins that they stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GladiaUtteranceMetadataTest {

    private fun TestScope.gladia(server: TestServer) = GladiaProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    @Test
    fun `speaker, confidence, language and words survive on every utterance under the vendor namespace`() = runTest {
        // The reference's fixture edit: a numeric speaker on the first utterance, a string on the rest.
        val fixture = parseJsonObject(GladiaFixtures.RESULT)
        val original = fixture.obj("result", "transcription")!!.arr("utterances")!!
        val utterances = JsonArray(
            original.mapIndexed { index, entry ->
                val speaker: JsonPrimitive = if (index == 0) JsonPrimitive(0) else JsonPrimitive("speaker-$index")
                JsonObject(entry.jsonObject + ("speaker" to speaker))
            },
        )
        val transcription = JsonObject(fixture.obj("result", "transcription")!! + ("utterances" to utterances))
        val result = JsonObject(fixture.obj("result")!! + ("transcription" to transcription))
        val server = TestServer(
            TestServer.json(GladiaFixtures.UPLOAD),
            TestServer.json(GladiaFixtures.INITIATE),
            TestServer.json(JsonObject(fixture + ("result" to result)).toString()),
        )

        val transcribed = gladia(server).transcriptionModel("default").doGenerate(
            TranscriptionCallOptions(audio = BinaryData.Bytes("AUDIO".encodeToByteArray()), mediaType = "audio/wav"),
        )

        val kept = transcribed.providerMetadata?.get(GLADIA_PROVIDER_ID)!!
            .obj("result", "transcription")!!.arr("utterances")!!
        val first = kept[0].jsonObject
        assertEquals(JsonPrimitive(0), first["speaker"])
        assertEquals(original[0].jsonObject["confidence"], first["confidence"])
        assertEquals(original[0].jsonObject["language"], first["language"])
        assertEquals(original[0].jsonObject["words"], first["words"])
        assertEquals(JsonPrimitive("speaker-1"), kept[1].jsonObject["speaker"])
    }
}
