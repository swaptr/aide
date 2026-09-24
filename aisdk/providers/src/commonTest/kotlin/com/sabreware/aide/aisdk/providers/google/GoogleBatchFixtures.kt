package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bodies from the reference's `google-batch.test.ts`.
 *
 * The inline ones are copied byte-for-byte. [operation] and [googleResponse] mirror the reference's own
 * builder helpers of the same names, which is how that file spells those bodies — an override map with
 * a null value REMOVES the key, as spreading `undefined` does in the original.
 */
internal object GoogleBatchFixtures {

    const val CREATE_URL: String =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:batchGenerateContent"
    const val BATCH_URL: String = "https://generativelanguage.googleapis.com/v1beta/batches/batch-123"
    const val UPLOAD_START_URL: String = "https://generativelanguage.googleapis.com/upload/v1beta/files"
    const val UPLOAD_SESSION_URL: String =
        "https://generativelanguage.googleapis.com/upload/v1beta/files/session-123"
    const val OUTPUT_URL: String =
        "https://generativelanguage.googleapis.com/download/v1beta/files/batch-output:download?alt=media"
    const val CANCEL_URL: String = "https://generativelanguage.googleapis.com/v1beta/batches/batch-123:cancel"
    const val BATCHES_URL: String = "https://generativelanguage.googleapis.com/v1beta/batches"

    /** `operation(metadataOverrides, operationOverrides)`. */
    fun operation(
        metadata: Map<String, JsonElement?> = emptyMap(),
        operation: Map<String, JsonElement?> = emptyMap(),
    ): String {
        val meta = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive("batches/batch-123"),
            "model" to JsonPrimitive("models/gemini-2.5-flash"),
            "displayName" to JsonPrimitive("ai-sdk-batch-test-id"),
            "state" to JsonPrimitive("BATCH_STATE_SUCCEEDED"),
            "createTime" to JsonPrimitive("2026-08-04T12:34:56.123Z"),
            "batchStats" to parseJsonObject(
                """{"requestCount":"2","successfulRequestCount":"2","failedRequestCount":"0"}""",
            ),
        )
        metadata.forEach { (key, value) -> if (value == null) meta.remove(key) else meta[key] = value }
        val op = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive("batches/batch-123"),
            "done" to JsonPrimitive(true),
            "metadata" to JsonObject(meta),
        )
        operation.forEach { (key, value) -> if (value == null) op.remove(key) else op[key] = value }
        return JsonObject(op).toString()
    }

    /** `googleResponse({ id, text })`. */
    fun googleResponse(id: String, text: String): String =
        """{"responseId":"$id","candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},""" +
            """"finishReason":"STOP","finishMessage":"Generation completed.",""" +
            """"safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH","probability":"NEGLIGIBLE"}],""" +
            """"groundingMetadata":{"webSearchQueries":["capital of France"]}}],""" +
            """"promptFeedback":{"safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH",""" +
            """"probability":"NEGLIGIBLE"}]},"usageMetadata":$USAGE_METADATA}"""

    const val USAGE_METADATA: String =
        """{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":14,""" +
            """"cachedContentTokenCount":2,"thoughtsTokenCount":1,"serviceTier":"priority"}"""

    /** The finalized-upload reply from `prepareUpload()`. */
    const val UPLOADED_FILE: String =
        """{"file":{"name":"files/batch-input","displayName":"batch.jsonl","mimeType":"application/jsonl",""" +
            """"sizeBytes":"256","uri":"https://generativelanguage.googleapis.com/v1beta/files/batch-input",""" +
            """"state":"ACTIVE","expirationTime":"2026-08-27T12:00:00Z"}}"""

    /** The page of "lists and normalizes a page of batches": one running, one finished, more to come. */
    fun listPage(): String = """{"operations":[""" +
        operation(
            metadata = mapOf(
                "state" to JsonPrimitive("BATCH_STATE_RUNNING"),
                "batchStats" to parseJsonObject(
                    """{"requestCount":"3","successfulRequestCount":"1","failedRequestCount":"0","pendingRequestCount":"2"}""",
                ),
            ),
        ) + "," +
        operation(operation = mapOf("name" to JsonPrimitive("batches/batch-122"))) +
        """],"nextPageToken":"page-token-2"}"""

    /** "converts generated image results": one PNG and the token counts that go with it. */
    const val GENERATED_IMAGE_LINE: String =
        """{"key":"image-1","response":{"candidates":[{"content":{"role":"model","parts":[""" +
            """{"inlineData":{"mimeType":"image/png","data":"aGVsbG8="}}]},"finishReason":"STOP"}],""" +
            """"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":3,"totalTokenCount":5}}}"""

    /** A tool call stored as a result — "fails unsupported items and continues" now expects it to succeed. */
    const val TOOL_ITEM_LINE: String =
        """{"key":"tool-request","response":{"responseId":"response-tool","candidates":[{"content":{"role":"model",""" +
            """"parts":[{"functionCall":{"id":"call-1","name":"weather","args":{"city":"Paris"}}}]},""" +
            """"finishReason":"STOP"}]}}"""

    /** A file no batch can carry: audio is neither text nor a generated image. */
    const val AUDIO_ITEM_LINE: String =
        """{"key":"audio-request","response":{"candidates":[{"content":{"role":"model","parts":[""" +
            """{"inlineData":{"mimeType":"audio/wav","data":"UklGRg=="}}]},"finishReason":"STOP"}]}}"""

    const val NOT_FOUND: String =
        """{"error":{"code":404,"message":"Batch not found.","status":"NOT_FOUND"}}"""

    const val INVALID_ARGUMENT: String =
        """{"error":{"code":400,"message":"The batch input was invalid.","status":"INVALID_ARGUMENT"}}"""

    /** The `germany` line of the streamed-results test. */
    const val GERMANY_ERROR_LINE: String =
        """{"key":"germany","error":{"code":3,"message":"The request was invalid.",""" +
            """"details":[{"reason":"INVALID_ARGUMENT"}]}}"""

    const val CANCELLED_LINE: String =
        """{"key":"cancelled-request","error":{"code":1,"message":"The request was cancelled."}}"""

    /** A blocked response; [candidates] is the `undefined` / `[]` half of the reference's `it.each`. */
    fun blockedLine(key: String, candidates: String?): String =
        """{"key":"$key","response":{""" + (candidates?.let { """"candidates":$it,""" } ?: "") +
            """"promptFeedback":{"blockReason":"SAFETY","safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH",""" +
            """"probability":"HIGH"}]}}}"""

    const val INVALID_ITEM_LINE: String =
        """{"key":"invalid-request","response":{"candidates":[{"content":{"role":"model","parts":[{"text":42}]}}]}}"""

    const val IMAGE_ITEM_LINE: String =
        """{"key":"image-request","response":{"candidates":[{"content":{"role":"model","parts":[""" +
            """{"text":"Generated image:"},{"inlineData":{"mimeType":"image/png","data":"aW1hZ2U="}}]},""" +
            """"finishReason":"STOP"}]}}"""

    /** The `response` half of the inline-results operation. */
    fun inlinedResponses(): String =
        """{"inlinedResponses":{"inlinedResponses":[""" +
            """{"metadata":{"key":"france"},"response":${googleResponse("response-france", "Paris")}},""" +
            """{"metadata":{"key":"germany"},"error":{"code":8,"message":"Resource has been exhausted.",""" +
            """"status":"RESOURCE_EXHAUSTED"}}]}}"""
}
