package com.sabreware.aide.aisdk.providers.openai

/**
 * Fixtures for the Open Responses extension tests, copied from the reference.
 *
 * The response body is `packages/open-responses/src/responses/__fixtures__/lmstudio-basic.1.json`,
 * byte for byte. The items and events are the inline objects of
 * `open-responses-language-model.test.ts`, written out as the JSON the test server would have sent.
 */
internal object OpenResponsesExtensionFixtures {

    /** `__fixtures__/lmstudio-basic.1.json`. */
    const val LMSTUDIO_BASIC_1: String = """{
  "id": "resp_551daeb1a02e4fcaf9ab76ed29f821a6db2df1883e55652c",
  "object": "response",
  "created_at": 1768900049,
  "completed_at": 1768900162,
  "status": "completed",
  "incomplete_details": null,
  "model": "mistralai/ministral-3-14b-reasoning",
  "previous_response_id": null,
  "instructions": null,
  "output": [
    {
      "id": "rs_3l1z5wpifxkwxhj459ya7",
      "type": "reasoning",
      "status": "completed",
      "summary": [],
      "content": [
        {
          "type": "reasoning_text",
          "text": "reasoning content"
        }
      ]
    },
    {
      "id": "msg_p1y190hl7hj1xyfqr1cir",
      "type": "message",
      "role": "assistant",
      "status": "completed",
      "content": [
        {
          "type": "output_text",
          "text": "text content",
          "annotations": [],
          "logprobs": []
        }
      ]
    }
  ],
  "error": null,
  "tools": [],
  "tool_choice": "auto",
  "truncation": "auto",
  "parallel_tool_calls": true,
  "text": {
    "format": {
      "type": "text"
    }
  },
  "top_p": 0.95,
  "presence_penalty": 0,
  "frequency_penalty": 1.1,
  "top_logprobs": 0,
  "temperature": 0.1,
  "reasoning": {
    "summary": null,
    "effort": null
  },
  "usage": {
    "input_tokens": 136,
    "output_tokens": 3677,
    "total_tokens": 3813,
    "input_tokens_details": {
      "cached_tokens": 0
    },
    "output_tokens_details": {
      "reasoning_tokens": 2456
    }
  },
  "max_output_tokens": null,
  "max_tool_calls": null,
  "store": true,
  "background": false,
  "service_tier": "default",
  "metadata": {},
  "safety_identifier": null,
  "prompt_cache_key": null
}
"""

    /**
     * "should decode extension items and replay them through response history" — the `receipt`
     * object. Note `result` is an OBJECT, which is what would have failed the wire decoder before
     * extension items were reduced to `{type, id, status}`.
     */
    const val RECEIPT: String = """{"id":"search_1","type":"acme:document_search_receipt","status":"completed",
        "call_id":"call_1","name":"documentSearch","provider_executed":false,
        "query":{"text":"climate"},"result":{"documents":[{"id":"doc_1","score":0.9}]},
        "opaque_receipt":{"trace_id":"trace_1","implementation_version":3}}"""

    /** "should replay a source-only extension item through response history" — `sourceItem`. */
    const val SOURCE_ITEM: String = """{"id":"source_1","type":"acme:document_search_receipt","status":"completed",
        "url":"https://example.com/documentation","title":"Extension documentation",
        "opaque_receipt":{"trace_id":"trace_source_1"}}"""

    /** "should decode registered extension events and completed items" — the streamed `receipt`. */
    const val STREAM_RECEIPT: String = """{"id":"search_stream_1","type":"acme:document_search_receipt",
        "status":"completed","call_id":"call_stream_1","name":"documentSearch",
        "query":{"text":"streamed query"},"result":{"documents":["doc_1"]},
        "opaque_receipt":{"cursor":"cursor_1"}}"""

    /** The same test's SSE frames, in order — one line each, as an SSE `data:` line has to be. */
    val STREAM_CHUNKS: List<String> = listOf(
        """{"type":"acme:document_search_input","sequence_number":0,"call_id":"call_stream_1",
            "name":"documentSearch","delta":"{\"text\":\"streamed query\"}"}""",
        """{"type":"response.output_item.done","sequence_number":1,"output_index":0,"item":$STREAM_RECEIPT}""",
        """{"type":"response.completed","sequence_number":2,"response":{"id":"response_stream_1",
            "object":"response","created_at":0,"status":"completed","model":"test-model",
            "output":[$STREAM_RECEIPT],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
    ).map { it.replace(Regex("\\n\\s*"), "") }

    /** `prepareOutputResponse(output)` — the reference's minimal response around one `output` array. */
    fun outputResponse(vararg items: String): String =
        """{"id":"resp_1","object":"response","created_at":0,"model":"test-model","status":"completed",
            "output":[${items.joinToString(",")}],
            "usage":{"input_tokens":0,"output_tokens":0,"total_tokens":0}}"""
}
