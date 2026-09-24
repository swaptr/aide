package com.sabreware.aide.aisdk.providers.perplexity

/**
 * Agent API payloads in the shapes Perplexity DOCUMENTS — not recorded from the live service.
 *
 * The reference has no Agent API fixtures (its Perplexity package is the embedding model), so these
 * were hand-written on 2026-09-02 from the API reference (`api-reference/agent-post`), the web-search
 * and fetch-URL tool pages, the custom-functions page and the quickstart's response example: the
 * `output` item types and their fields, the `usage` block with `cost` and the cache counters, the
 * `url_citation` annotation, and the fourteen SSE event types with the fields the reference schema
 * names for each. A doc-derived fixture pins agreement with the documentation, which is one step
 * short of agreement with the wire; the first recorded exchange should replace it.
 */
internal object PerplexityAgentFixtures {

    /** A completed research turn: a search, a fetch of one of its results, then the cited answer. */
    const val RESPONSE: String =
        """{"id":"resp_01","object":"response","created_at":1756800000,"completed_at":1756800004,"status":"completed","model":"openai/gpt-5.6-sol","output":[{"type":"search_results","queries":["transformer architecture attention"],"results":[{"id":1,"url":"https://arxiv.org/abs/1706.03762","title":"Attention Is All You Need","snippet":"We propose a new simple network architecture, the Transformer.","date":"2017-06-12","last_updated":"2023-08-02","source":"web"},{"id":2,"url":"https://example.com/transformers","title":"Transformers explained","snippet":"A gentle introduction.","source":"web"}]},{"type":"fetch_url_results","contents":[{"url":"https://arxiv.org/abs/1706.03762","title":"Attention Is All You Need","snippet":"Abstract: The dominant sequence transduction models are based on complex recurrent networks."}]},{"type":"message","id":"msg_01","status":"completed","role":"assistant","content":[{"type":"output_text","text":"The Transformer relies entirely on attention.","annotations":[{"type":"url_citation","start_index":0,"end_index":15,"url":"https://arxiv.org/abs/1706.03762","title":"Attention Is All You Need"}],"logprobs":[]}]}],"usage":{"input_tokens":150,"output_tokens":200,"total_tokens":350,"input_tokens_details":{"cache_creation_input_tokens":0,"cache_read_input_tokens":50},"tool_calls_details":{},"cost":{"currency":"USD","input_cost":0.00826,"output_cost":0.0063,"tool_calls_cost":0.0025,"total_cost":0.01706}},"output_text":"The Transformer relies entirely on attention."}"""

    /** The custom-functions page's `function_call` item, with the `thought_signature` the reference schema adds. */
    const val FUNCTION_CALL: String =
        """{"id":"resp_03","object":"response","created_at":1756800100,"status":"completed","model":"google/gemini-3-flash-preview","output":[{"type":"function_call","id":"fc_a181bc3f-7a54-40b6-a85e-a50a0a6fac92","call_id":"call_Ku9yfMSIZWrJBGm2wqCaFF0G","name":"get_order_status","arguments":"{\"order_id\":\"ORD-10042\"}","status":"completed","thought_signature":"CkYBVKhc7uZ0"}],"usage":{"input_tokens":40,"output_tokens":12,"total_tokens":52}}"""

    /** The lifecycle, reasoning and output events of one streamed research turn, in the reference schema's field shapes. */
    val stream: List<String> = listOf(
        """{"type":"response.created","sequence_number":0,"response":{"id":"resp_02","object":"response","created_at":1756800000,"status":"in_progress","model":"openai/gpt-5.6-sol","output":[]}}""",
        """{"type":"response.in_progress","sequence_number":1,"response":{"id":"resp_02","object":"response","created_at":1756800000,"status":"in_progress","model":"openai/gpt-5.6-sol","output":[]}}""",
        """{"type":"response.reasoning.started","sequence_number":2,"thought":"Looking this up."}""",
        """{"type":"response.reasoning.search_queries","sequence_number":3,"queries":["transformer architecture"],"thought":"Searching the web."}""",
        """{"type":"response.reasoning.search_results","sequence_number":4,"results":[{"id":1,"url":"https://arxiv.org/abs/1706.03762","title":"Attention Is All You Need","snippet":"We propose a new simple network architecture, the Transformer.","source":"web"}],"usage":{"input_tokens":10,"output_tokens":0,"total_tokens":10}}""",
        """{"type":"response.reasoning.stopped","sequence_number":5}""",
        """{"type":"response.output_item.added","sequence_number":6,"output_index":0,"item":{"type":"search_results","queries":["transformer architecture"],"results":[]}}""",
        """{"type":"response.output_item.done","sequence_number":7,"output_index":0,"item":{"type":"search_results","queries":["transformer architecture"],"results":[{"id":1,"url":"https://arxiv.org/abs/1706.03762","title":"Attention Is All You Need","snippet":"We propose a new simple network architecture, the Transformer.","source":"web"},{"id":2,"url":"https://example.com/transformers","title":"Transformers explained","snippet":"A gentle introduction.","source":"web"}]}}""",
        """{"type":"response.output_item.added","sequence_number":8,"output_index":1,"item":{"type":"message","id":"msg_02","status":"in_progress","role":"assistant","content":[]}}""",
        """{"type":"response.output_text.delta","sequence_number":9,"item_id":"msg_02","output_index":1,"content_index":0,"delta":"The Transformer "}""",
        """{"type":"response.output_text.delta","sequence_number":10,"item_id":"msg_02","output_index":1,"content_index":0,"delta":"relies on attention."}""",
        """{"type":"response.output_text.done","sequence_number":11,"item_id":"msg_02","output_index":1,"content_index":0,"text":"The Transformer relies on attention."}""",
        """{"type":"response.output_item.done","sequence_number":12,"output_index":1,"item":{"type":"message","id":"msg_02","status":"completed","role":"assistant","content":[{"type":"output_text","text":"The Transformer relies on attention.","annotations":[]}]}}""",
        """{"type":"response.completed","sequence_number":13,"response":{"id":"resp_02","object":"response","created_at":1756800000,"completed_at":1756800003,"status":"completed","model":"openai/gpt-5.6-sol","output":[],"usage":{"input_tokens":150,"output_tokens":40,"total_tokens":190,"input_tokens_details":{"cache_creation_input_tokens":0,"cache_read_input_tokens":0},"cost":{"currency":"USD","input_cost":0.001,"output_cost":0.002,"total_cost":0.003}}}}""",
    )

    /** `response.failed`, whose `error` sits at the top of the frame in the reference schema. */
    val failedStream: List<String> = listOf(
        """{"type":"response.created","sequence_number":0,"response":{"id":"resp_04","object":"response","created_at":1756800200,"status":"in_progress","model":"openai/gpt-5.6-sol","output":[]}}""",
        """{"type":"response.failed","sequence_number":1,"error":{"message":"Model overloaded","type":"server_error","code":"overloaded"}}""",
    )
}
