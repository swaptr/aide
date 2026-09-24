package com.sabreware.aide.aisdk.providers.xai

/**
 * Wire bodies from the reference's xAI Files and Batch tests, transcribed value for value.
 *
 * `xai-files.test.ts` builds its responses as inline objects and `xai-responses-batch.test.ts` as
 * builder functions with overrides; both are reproduced here as the JSON those objects serialize to,
 * so an assertion below is about the vendor's recorded wire rather than this port's reading of it.
 */
internal object XaiBatchFixtures {

    // --- xai-files.test.ts -------------------------------------------------------------------------

    /** `defaultResponseBody`. */
    const val FILE_UPLOADED: String =
        """{"id":"file-abc123","object":"file","bytes":3,"created_at":1234567890,"filename":"upload"}"""

    /** `should include providerMetadata with response data`. */
    const val FILE_UPLOADED_CSV: String =
        """{"id":"file-abc123","object":"file","bytes":512,"created_at":1700000000,"filename":"data.csv"}"""

    /** `should omit null response fields from providerMetadata`. */
    const val FILE_UPLOADED_NULLS: String =
        """{"id":"file-abc123","object":"file","bytes":null,"created_at":null,"filename":null}"""

    // --- xai-responses-batch.test.ts ---------------------------------------------------------------

    /** The upload response the batch start receives for its JSONL. */
    const val BATCH_FILE_UPLOADED: String = """{"id":"file_123","filename":"batch.jsonl"}"""

    /** `batchResponse(overrides)`: the batch object, with the defaults the reference test uses. */
    fun batchResponse(
        batchId: String = "batch_123",
        numRequests: Int = 2,
        numPending: Int = 0,
        numSuccess: Int = 2,
        numError: Int = 0,
        numCancelled: Int = 0,
        cancelTime: String? = null,
        cancelByXaiMessage: String? = null,
        expireTime: String = "2099-08-26T12:00:00Z",
    ): String = """{"batch_id":"$batchId","name":"ai-sdk-text-batch",""" +
        """"create_time":"2026-08-25T12:00:00Z","expire_time":"$expireTime",""" +
        """"cancel_time":${cancelTime.jsonOrNull()},"cancel_by_xai_message":${cancelByXaiMessage.jsonOrNull()},""" +
        """"state":{"num_requests":$numRequests,"num_pending":$numPending,"num_success":$numSuccess,""" +
        """"num_error":$numError,"num_cancelled":$numCancelled}}"""

    /** `chatResultBody(text)`: what xAI stores for a Responses request — a Chat Completions document. */
    fun chatResultBody(text: String): String = """{"id":"response_123","object":"chat.completion",""" +
        """"created":1700000000,"model":"grok-4.3","choices":[{"index":0,"message":{"role":"assistant",""" +
        """"content":${text.json()},"reasoning_content":"Reasoning","tool_calls":null},"finish_reason":"stop"}],""" +
        """"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":14,""" +
        """"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1},""" +
        """"cost_in_usd_ticks":123},"citations":["https://example.com/source"],"service_tier":"default"}"""

    /** `successfulResult(id, text)`: a succeeded item carries a zero error code, not no error. */
    fun successfulResult(id: String, text: String): String = """{"batch_request_id":${id.json()},""" +
        """"batch_result":{"response":{"chat_get_completion":${chatResultBody(text)}},""" +
        """"error":{"code":0,"message":""}}}"""

    /** Page 1 of `paginates and converts successful and failed results`. */
    val RESULTS_PAGE_1: String = """{"results":[${successfulResult("france", "Paris")}],""" +
        """"pagination_token":"next/page"}"""

    /** Page 2: a failed item with a numeric code, and a cancelled one. */
    const val RESULTS_PAGE_2: String = """{"results":[""" +
        """{"batch_request_id":"failed","batch_result":{"error":{"code":3,"message":"Invalid request."}},""" +
        """"error_message":"Invalid request."},""" +
        """{"batch_request_id":"cancelled","batch_result":{"error":{"code":1,"message":"Cancelled."}}}],""" +
        """"pagination_token":null}"""

    /** `fails invalid and unsupported items without stopping later results`. */
    val RESULTS_MIXED: String = """{"results":[""" +
        """{"batch_request_id":"invalid","batch_result":{"response":{"chat_get_completion":{"choices":42}}}},""" +
        """{"batch_request_id":"tool-call","batch_result":{"response":{"chat_get_completion":""" +
        """{"id":"response_123","object":"chat.completion","created":1700000000,"model":"grok-4.3",""" +
        """"choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":null,""" +
        """"tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather","arguments":"{}"}}]},""" +
        """"finish_reason":"tool_calls"}],""" +
        """"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":14,""" +
        """"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1},""" +
        """"cost_in_usd_ticks":123},"citations":["https://example.com/source"],"service_tier":"default"}}}},""" +
        """${successfulResult("valid", "Berlin")}],"pagination_token":null}"""


    // --- xai-batch.test.ts: images and transcripts -------------------------------------------------

    /** `converts image generation results`. */
    const val RESULTS_IMAGE: String = """{"results":[{"batch_request_id":"image-1","batch_result":{"response":""" +
        """{"image_generation":{"data":[{"b64_json":"aGVsbG8=","revised_prompt":"A vivid red panda"}],""" +
        """"usage":{"cost_in_usd_ticks":42}}},"error":{"code":0,"message":""}}}],"pagination_token":null}"""

    /** `returns moderated image generation results as failed items`. */
    const val RESULTS_IMAGE_MODERATED: String = """{"results":[{"batch_request_id":"image-1","batch_result":{"response":""" +
        """{"image_generation":{"data":[{"url":null,"b64_json":null,"respect_moderation":false}]}},""" +
        """"error":{"code":0,"message":""}}}],"pagination_token":null}"""

    /** `preserves provider-executed tool calls and final text from batch transcripts`. */
    val RESULTS_TRANSCRIPTS: String = """{"results":[""" +
        """{"batch_request_id":"provider-tool","batch_result":{"response":{"chat_get_completion":""" +
        chatResultBody("").replace(
            """"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"Reasoning","tool_calls":null},"finish_reason":"stop"}]""",
            """"choices":[""" +
                """{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"web-search-1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"Vercel\"}"}}]},"finish_reason":""},""" +
                """{"index":1,"message":{"role":"tool","content":"Search results","tool_calls":[{"id":"web-search-1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"Vercel\"}"}}]},"finish_reason":""},""" +
                """{"index":2,"message":{"role":"assistant","content":"Final answer","tool_calls":null},"finish_reason":"stop"},""" +
                """{"index":3,"message":{"role":"tool","content":null,"tool_calls":[]},"finish_reason":""}]""",
        ) + """}}},""" +
        """{"batch_request_id":"client-tool-with-provider-name","batch_result":{"response":{"chat_get_completion":""" +
        chatResultBody("").replace(
            """"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"Reasoning","tool_calls":null},"finish_reason":"stop"}]""",
            """"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"client-web-search-1","type":"function","function":{"name":"web_search","arguments":"{}"}}]},"finish_reason":"tool_calls"}]""",
        ) + """}}}],"pagination_token":null}"""

    private fun String?.jsonOrNull(): String = if (this == null) "null" else json()

    private fun String.json(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
