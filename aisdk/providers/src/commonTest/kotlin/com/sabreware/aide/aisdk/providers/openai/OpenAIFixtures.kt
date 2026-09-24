package com.sabreware.aide.aisdk.providers.openai

/**
 * Fixture bodies copied byte for byte from the reference, each naming the file it came from.
 *
 * Agreement with one of these means agreement with the vendor's recorded wire, not with our own reading
 * of it — which is the only kind of agreement a port can offer without a live key.
 */
internal object OpenAIFixtures {

    /** `files/openai-files.test.ts` `prepareFileResponse`; the id is the one knob that test varies. */
    fun fileResponse(id: String = "file-abc123"): String =
        """{"id":"$id","object":"file","bytes":1024,"created_at":1700000000,"filename":"test.csv",""" +
            """"purpose":"assistants","status":"processed","expires_at":null}"""

    /** `skills/__fixtures__/openai-skill-create.json`, verbatim including its pretty-printing. */
    val skillCreate: String = """
        |{
        |  "id": "skill_699fc58f408c8191825d8d06ae75fd5c06de7b381a5db7f5",
        |  "object": "skill",
        |  "name": "test-capture-skill",
        |  "description": "A test skill for fixture capture",
        |  "default_version": "1",
        |  "latest_version": "1",
        |  "created_at": 1772078479
        |}
        |
    """.trimMargin()

    /** `openai-batch.test.ts` `prepareCreateResponse` — the `/files` half, expiry included. */
    const val BATCH_INPUT_FILE: String =
        """{"id":"file-input","object":"file","filename":"batch.jsonl","purpose":"batch","expires_at":1700172800}"""

    /** `openai-batch.test.ts` `batchResponse(overrides)`, overrides applied in place. */
    fun batchResponse(
        id: String = "batch_123",
        status: String = "completed",
        outputFileId: String? = null,
        errorFileId: String? = null,
        requestCounts: String = """{"total":2,"completed":2,"failed":0}""",
        errors: String = "null",
    ): String = """{"id":"$id","status":"$status",""" +
        """"output_file_id":${outputFileId.quotedOrNull()},"error_file_id":${errorFileId.quotedOrNull()},""" +
        """"created_at":1700000000,"expires_at":1700086400,""" +
        """"request_counts":$requestCounts,"errors":$errors}"""

    /** `openai-responses-batch.test.ts` `responsesResultBody(text)`. */
    fun responsesResultBody(text: String): String =
        """{"id":"resp_123","created_at":1700000000,"model":"gpt-5.6","output":[""" +
            """{"type":"message","role":"assistant","id":"msg_123","phase":null,"content":[""" +
            """{"type":"output_text","text":"$text","logprobs":null,"annotations":[]}]}],""" +
            """"service_tier":"default","reasoning":null,"incomplete_details":null,""" +
            """"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},""" +
            """"output_tokens":3,"output_tokens_details":{"reasoning_tokens":1}}}"""

    /** `openai-responses-batch.test.ts` `responsesResultBody(text)` with `output` replaced, as its tests do. */
    fun responsesResultBodyWithOutput(output: String): String =
        """{"id":"resp_123","created_at":1700000000,"model":"gpt-5.6","output":$output,""" +
            """"service_tier":"default","reasoning":null,"incomplete_details":null,""" +
            """"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},""" +
            """"output_tokens":3,"output_tokens_details":{"reasoning_tokens":1}}}"""

    /** `openai-responses-batch.test.ts` `resultLine({id, body})`. */
    fun resultLine(id: String, body: String): String =
        """{"custom_id":"$id","response":{"status_code":200,"request_id":"openai-$id","body":$body},"error":null}"""

    /** `openai-responses-batch.test.ts`, the `http-error` line of the output-and-error-files test. */
    const val HTTP_ERROR_LINE: String =
        """{"custom_id":"http-error","response":{"status_code":400,"request_id":"request-error",""" +
            """"body":{"error":{"message":"Invalid request.","type":"invalid_request_error",""" +
            """"param":null,"code":"invalid_request"}}},"error":null}"""

    /** `openai-responses-batch.test.ts`, the error file of the same test — three lines. */
    val ERROR_FILE_LINES: List<String> = listOf(
        """{"custom_id":"cancelled","response":null,"error":{"code":"batch_cancelled","message":"Batch cancelled."}}""",
        """{"custom_id":"expired","response":null,"error":{"code":"batch_expired","message":"Batch expired."}}""",
        """{"custom_id":"failed","response":null,"error":{"code":"request_timeout","message":"Request timed out."}}""",
    )

    /**
     * `speech-translation/__fixtures__/openai-realtime-speech-translation.chunks.txt`, one entry per
     * line — wire another team captured from the running service.
     */
    val realtimeTranslationChunks: List<String> = listOf(
        """{"type":"session.created","event_id":"event-created","session":{"id":"session-fixture","type":"translation","expires_at":0,"model":"gpt-realtime-translate","audio":{"input":{"noise_reduction":null,"transcription":null},"output":{"language":"es"}},"include":null}}""",
        """{"type":"session.updated","event_id":"event-updated","session":{"id":"session-fixture","type":"translation","expires_at":0,"model":"gpt-realtime-translate","audio":{"input":{"noise_reduction":null,"transcription":{"model":"gpt-realtime-whisper"}},"output":{"language":"es"}},"include":null}}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-1","delta":" The","elapsed_ms":600}""",
        """{"type":"session.output_audio.delta","event_id":"event-audio-1","delta":"AQID","sample_rate":24000,"channels":1,"format":"pcm16","elapsed_ms":200}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-2","delta":" quick","elapsed_ms":800}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-3","delta":" brown","elapsed_ms":1000}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-4","delta":" fox","elapsed_ms":1400}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-5","delta":" jumps","elapsed_ms":1800}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-6","delta":" over","elapsed_ms":2000}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-7","delta":" the","elapsed_ms":2200}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-1","delta":"La","elapsed_ms":2400}""",
        """{"type":"session.input_transcript.delta","event_id":"event-input-8","delta":" lazy","elapsed_ms":2400}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-2","delta":" rápida","elapsed_ms":2600}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-3","delta":" zor","elapsed_ms":3200}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-4","delta":"ra","elapsed_ms":3400}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-5","delta":" marr","elapsed_ms":3400}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-6","delta":"ón","elapsed_ms":3600}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-7","delta":" sal","elapsed_ms":4000}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-8","delta":"ta","elapsed_ms":4200}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-9","delta":" sobre","elapsed_ms":4400}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-10","delta":" el","elapsed_ms":4600}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-11","delta":" perro","elapsed_ms":4800}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-12","delta":" p","elapsed_ms":5000}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-13","delta":"erez","elapsed_ms":5200}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-14","delta":"oso","elapsed_ms":5400}""",
        """{"type":"session.output_transcript.delta","event_id":"event-output-15","delta":".","elapsed_ms":5600}""",
        """{"type":"session.closed","event_id":"event-closed"}""",
    )

    private fun String?.quotedOrNull(): String = this?.let { "\"$it\"" } ?: "null"
}
