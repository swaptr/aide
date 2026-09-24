package com.sabreware.aide.aisdk.providers.bedrock

/**
 * Recorded Bedrock responses for the non-chat modalities, plus the two Mantle bodies.
 *
 * The image bodies are the server responses in the reference's `amazon-bedrock-image-model.test.ts`
 * and the rerank body is its `reranking/__fixtures__/amazon-bedrock-reranking.1.json`, all verbatim.
 * Mantle has NO recorded wire in the reference — `bedrock-mantle-provider.test.ts` mocks the two model
 * classes and asserts their constructor arguments — so its bodies here are ours, shaped to the OpenAI
 * wire the endpoint documents.
 */
internal object BedrockModalityFixtures {

    /** `amazon-bedrock-image-model.test.ts` — the `doGenerate` server response. */
    const val IMAGES_TWO: String = """{"images":["base64-image-1","base64-image-2"]}"""

    /** `amazon-bedrock-image-model.test.ts` — "should throw error when request is moderated". */
    const val IMAGE_MODERATED: String =
        """{"id":"fe7256d1-50d9-4663-8592-85eaf002e80c","status":"Request Moderated","result":null,""" +
            """"progress":null,"details":{"Moderation Reasons":["Derivative Works Filter"]},"preview":null}"""

    /** `amazon-bedrock-image-model.test.ts` — "should throw error when no images are returned". */
    const val IMAGES_EMPTY: String = """{"images":[]}"""

    /** `amazon-bedrock-image-model.test.ts` — the "Image Editing" server response. */
    const val IMAGE_EDITED: String = """{"images":["edited-image-base64"]}"""

    /** `reranking/__fixtures__/amazon-bedrock-reranking.1.json`, verbatim. */
    const val RERANK_1: String = """{
  "results": [
    {
      "index": 0,
      "relevanceScore": 0.5110583305358887
    },
    {
      "index": 5,
      "relevanceScore": 0.30241215229034424
    }
  ]
}
"""

    /** Ours: a Chat Completions stream from `openai.gpt-oss-20b` — text, a stop, usage on the last chunk. */
    const val MANTLE_CHAT_SSE: String =
        """data: {"id":"chatcmpl-mantle-1","object":"chat.completion.chunk","created":1777000000,""" +
            """"model":"openai.gpt-oss-20b","choices":[{"index":0,"delta":{"role":"assistant",""" +
            """"content":"Hello from Mantle."},"finish_reason":null}]}""" + "\n\n" +
            """data: {"id":"chatcmpl-mantle-1","object":"chat.completion.chunk","created":1777000000,""" +
            """"model":"openai.gpt-oss-20b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}}""" + "\n\n" +
            "data: [DONE]\n\n"

    /** Ours: one completed Responses API body carrying a single assistant message. */
    const val MANTLE_RESPONSES_JSON: String =
        """{"id":"resp_mantle_1","object":"response","created_at":1777000000,"status":"completed",""" +
            """"error":null,"incomplete_details":null,"model":"openai.gpt-oss-20b","output":[{"id":"msg_mantle_1",""" +
            """"type":"message","status":"completed","role":"assistant","content":[{"type":"output_text",""" +
            """"text":"Hello from the Responses API.","annotations":[]}]}],"usage":{"input_tokens":9,""" +
            """"input_tokens_details":{"cached_tokens":0},"output_tokens":6,""" +
            """"output_tokens_details":{"reasoning_tokens":0},"total_tokens":15}}"""
}
