package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * The batch contract's delta: requests are a modality union, results are typed by modality, and
 * cancellation and listing are optional operations that read "absent" as null.
 *
 * The one property worth a test of its own is the rejection. The reference has every text-only
 * provider refuse an image request with `UnsupportedFunctionalityError` before it sends anything; here
 * that refusal is built into [BatchRequest.options], the accessor every text-only provider already reads
 * — so a provider written before image batches existed rejects one exactly as the reference does,
 * without a line changed.
 */
class BatchContractTest {

    /** The three required operations and nothing else — every batch model before the extension. */
    private class TextOnlyBatchModel : BatchLanguageModel {
        override val provider: String = "vendor"
        override val modelId: String = "model-1"
        val sent = mutableListOf<CallOptions>()

        override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
            // What every text-only provider does: map each request's call, THEN send.
            val calls = options.requests.map { it.options }
            sent += calls
            return BatchStartResult("batch-1", BatchStatus(BatchStatus.State.Pending))
        }

        override suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus =
            BatchStatus(BatchStatus.State.Pending)

        override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> = emptyFlow()
    }

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))

    @Test
    fun `cancel and list answer null on a model that offers neither`() = runTest {
        val model = TextOnlyBatchModel()

        assertNull(model.doCancelBatch(BatchOperationOptions("batch-1")))
        assertNull(model.doListBatches(BatchListOptions()))
        assertEquals(emptyMap(), model.supportedUrls())
    }

    @Test
    fun `a text request is spelled as it always was`() {
        val request = BatchRequest("r1", CallOptions(prompt = prompt))

        assertIs<BatchRequest.Text>(request)
        assertEquals(BatchRequestType.Text, request.type)
        assertEquals("text", request.type.wireName)
        assertNull(request.modelId)
        assertEquals(CallOptions(prompt = prompt), request.options)
    }

    @Test
    fun `a text-only model rejects an image request before sending anything`() = runTest {
        val model = TextOnlyBatchModel()

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model.doStartBatch(
                BatchStartOptions(
                    requests = listOf(
                        BatchRequest("text-1", CallOptions(prompt = prompt)),
                        BatchRequest.Image("image-1", ImageCallOptions(prompt = "a red panda")),
                    ),
                ),
            )
        }

        assertEquals("batch request type: image", error.functionality)
        assertEquals(emptyList(), model.sent)
    }

    @Test
    fun `every item result knows its modality, text by default`() {
        val failed = BatchItemResult.Failed("r1", BatchError("boom"))
        val imageFailed = BatchItemResult.Failed("i1", BatchError("boom"), type = BatchRequestType.Image)
        val succeeded = BatchItemResult.ImageSucceeded(
            "i2",
            ImageResult(images = listOf(BinaryData.Base64("aGVsbG8=")), response = ModalityResponse()),
        )

        assertEquals(BatchRequestType.Text, failed.type)
        assertEquals(BatchRequestType.Image, imageFailed.type)
        assertEquals(BatchRequestType.Image, succeeded.type)
        assertEquals(BatchRequestType.Text, BatchItemResult.Cancelled("r2").type)
        assertEquals(BatchRequestType.Text, BatchItemResult.Expired("r3").type)
    }
}
