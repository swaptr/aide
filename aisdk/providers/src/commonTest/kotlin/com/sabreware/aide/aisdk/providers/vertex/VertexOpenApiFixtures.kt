package com.sabreware.aide.aisdk.providers.vertex

/**
 * Recorded bodies for Vertex's OpenAI-compatible `endpoints/openapi` surface — the wire the xAI and
 * MaaS subproviders share.
 *
 * The reference's tests for both subproviders mock `createOpenAICompatible` and never touch a wire, so
 * the only fixture it records is the Grok usage object in `google-vertex-xai-provider.test.ts`
 * ("should count Grok reasoning tokens separately from completion tokens"), copied verbatim as
 * [XAI_USAGE]. Google's own documentation supplies the rest: the Grok reasoning guide's example
 * response carries the SAME four numbers (663 / 654 / 50 / 124), which is what makes the reference's
 * fixture a recording rather than an invention, and the MaaS streaming example shows usage arriving on
 * the final chunk unprompted.
 */
internal object VertexOpenApiFixtures {

    /**
     * `google-vertex/src/xai/google-vertex-xai-provider.test.ts`, the `convertUsage` input, verbatim.
     *
     * 663 prompt tokens of which 654 were cache reads; 50 completion tokens BESIDE 124 reasoning tokens
     * — disjoint, not nested, which is the whole reason the converter exists.
     */
    const val XAI_USAGE: String =
        """{"prompt_tokens":663,"prompt_tokens_details":{"cached_tokens":654},""" +
            """"completion_tokens":50,"completion_tokens_details":{"reasoning_tokens":124}}"""

    /**
     * A Grok stream on Vertex: the reasoning guide's example response
     * (https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/grok/capabilities/reasoning)
     * — its id, model, fingerprint and full `usage` block including the fields no reader consumes
     * (`accepted_prediction_tokens`, `cost_in_usd_ticks`, `num_sources_used`) — reshaped into the
     * chunks a `stream: true` request receives, with the usage on the final one.
     */
    const val XAI_STREAM: String =
        "data: " +
            """{"id":"knTMaJC0EJfM5OMP7I3xkAk","object":"chat.completion.chunk","created":1775523905,""" +
            """"model":"xai/grok-4.1-fast-reasoning","system_fingerprint":"fp_39c5j0a324",""" +
            """"choices":[{"index":0,"delta":{"role":"assistant","content":""" +
            """"I am Grok, an AI assistant built by xAI..."},"finish_reason":null}]}""" + "\n\n" +
            "data: " +
            """{"id":"knTMaJC0EJfM5OMP7I3xkAk","object":"chat.completion.chunk","created":1775523905,""" +
            """"model":"xai/grok-4.1-fast-reasoning","system_fingerprint":"fp_39c5j0a324",""" +
            """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
            "data: " +
            """{"id":"knTMaJC0EJfM5OMP7I3xkAk","object":"chat.completion.chunk","created":1775523905,""" +
            """"model":"xai/grok-4.1-fast-reasoning","system_fingerprint":"fp_39c5j0a324","choices":[],""" +
            """"usage":{"completion_tokens":50,"completion_tokens_details":{"accepted_prediction_tokens":0,""" +
            """"audio_tokens":0,"reasoning_tokens":124,"rejected_prediction_tokens":0},""" +
            """"cost_in_usd_ticks":0,"num_sources_used":0,"prompt_tokens":663,""" +
            """"prompt_tokens_details":{"audio_tokens":0,"cached_tokens":654,"image_tokens":0,""" +
            """"text_tokens":663},"total_tokens":837}}""" + "\n\n" +
            "data: [DONE]\n\n"

    /**
     * A MaaS stream: Google's "Call MaaS APIs for open models" streaming example
     * (https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/maas/call-open-model-apis)
     * — the pipe-delimited id, the empty `system_fingerprint`, and `usage` on the last chunk of a
     * request that sent NO `stream_options`.
     */
    const val MAAS_STREAM: String =
        "data: " +
            """{"choices":[{"delta":{"content":"Hello","role":"assistant"},"finish_reason":null,"index":0}],""" +
            """"created":1749661200,"id":"2025-06-11|10:00:00.292195-07|9.7.144.202|-123456789",""" +
            """"model":"deepseek-ai/deepseek-v3.1-maas","object":"chat.completion.chunk",""" +
            """"system_fingerprint":""}""" + "\n\n" +
            "data: " +
            """{"choices":[{"delta":{"content":""},"finish_reason":"stop","index":0}],""" +
            """"created":1749661200,"id":"2025-06-11|10:00:00.292195-07|9.7.144.202|-123456789",""" +
            """"model":"deepseek-ai/deepseek-v3.1-maas","object":"chat.completion.chunk",""" +
            """"system_fingerprint":"","usage":{"completion_tokens":131,"prompt_tokens":14,""" +
            """"total_tokens":145}}""" + "\n\n" +
            "data: [DONE]\n\n"
}
