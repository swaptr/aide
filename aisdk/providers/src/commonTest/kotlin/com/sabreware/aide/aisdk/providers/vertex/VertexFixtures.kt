package com.sabreware.aide.aisdk.providers.vertex

/**
 * Recorded Vertex responses, from the reference's own `__fixtures__` where it has them.
 *
 * The embedding body is `google-vertex/src/__fixtures__/google-vertex-embedding.json`, values verbatim
 * — including the per-prediction `statistics.token_count` (5 then 6) whose SUM is the call's usage, the
 * detail a hand-written fixture flattens into one number.
 */
internal object VertexFixtures {

    const val EMBEDDING_PREDICT: String = """{
  "predictions": [
    {
      "embeddings": {
        "statistics": {
          "token_count": 5,
          "truncated": false
        },
        "values": [
          -0.017999587580561638, -0.006893285550177097, -0.036766719073057175,
          -0.017558680847287178, -0.019938766956329346
        ]
      }
    },
    {
      "embeddings": {
        "statistics": {
          "truncated": false,
          "token_count": 6
        },
        "values": [
          -0.06007182598114014, 0.004907649010419846, -0.00690646655857563,
          -0.007314121350646019, -0.048464205116033554
        ]
      }
    }
  ],
  "metadata": {
    "billableCharacterCount": 35
  }
}"""

    /** Cloud Speech-to-Text v2 `recognize`: sequential results, word timings, billed duration. */
    const val CLOUD_STT: String =
        """{"results":[{"alternatives":[{"transcript":"Hello from Vertex.","confidence":0.92,""" +
            """"words":[{"word":"Hello","startOffset":"0.100s","endOffset":"0.500s"},""" +
            """{"word":"from","startOffset":"0.500s","endOffset":"0.700s"},""" +
            """{"word":"Vertex.","startOffset":"0.700s","endOffset":"1.200s"}]}],""" +
            """"languageCode":"en-US","resultEndOffset":"1.200s"}],""" +
            """"metadata":{"totalBilledDuration":"2s"}}"""

    /** Vertex `generateContent` transcription: an `audioTranscription` block beside plain text. */
    const val GEMINI_TRANSCRIPTION: String =
        """{"candidates":[{"content":{"role":"model","parts":[{"audioTranscription":""" +
            """{"text":"Hello world.","languageCode":"en-us","words":[""" +
            """{"word":"Hello","startOffset":"0.100s","endOffset":"0.400s"},""" +
            """{"word":"world.","startOffset":"0.500s","endOffset":"0.900s"}]}}]},""" +
            """"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":15,""" +
            """"candidatesTokenCount":4,"totalTokenCount":19}}"""
}
