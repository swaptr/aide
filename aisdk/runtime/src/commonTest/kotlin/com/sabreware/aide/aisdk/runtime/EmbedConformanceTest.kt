package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The reference's own `ai/src/embed` and `ai/src/rerank` cases, translated.
 *
 * These pin what a thin wrapper is easy to get wrong and easy to test wrongly: that the vectors a
 * chunked batch produces come back paired with the values the CALLER gave, that a token count is the
 * sum across every call rather than the last one's, that a warning raised by the second chunk is not
 * dropped because the first chunk raised none, and that a model which says its credentials cannot take
 * concurrency is genuinely never given two calls at once. Every one of those is silent when wrong —
 * the run succeeds and the numbers are merely incorrect — which is why the reference spends so much of
 * its embedding suite here rather than on the request body.
 *
 * `ModalityWrapperTest` covers the same wrappers from our own angle; this file is deliberately the
 * reference's angle, case for case, so a divergence shows up as a failing translated assertion rather
 * than as a test that agrees with whatever we happened to write.
 *
 * The byte-budget cases are here too: at `ai@7.0.85` the reference also splits a batch by a UTF-8
 * INPUT BYTE budget, carried upstream as a `Symbol.for` side channel outside its versioned
 * specification. `EmbeddingModel.maxInputBytesPerCall` is that budget as a first-class member, and the
 * cases below pin the split honouring both ceilings at once.
 */
class EmbedConformanceTest {

    private val testValues = listOf(
        "sunny day at the beach",
        "rainy afternoon in the city",
        "snowy night in the mountains",
    )

    private val dummyEmbeddings = listOf(
        listOf(0.1, 0.2, 0.3),
        listOf(0.4, 0.5, 0.6),
        listOf(0.7, 0.8, 0.9),
    )

    // -----------------------------------------------------------------------------------------------
    // embedMany — chunking and ordering
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a batch inside the ceiling is one call and its vectors are returned unchanged`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = 5) { EmbeddingResult(embeddings = dummyEmbeddings) }

        val result = embedMany(model, EmbeddingCallOptions(testValues))

        assertEquals(dummyEmbeddings, result.embeddings)
        assertEquals(1, model.chunks.size)
    }

    @Test
    fun `several calls are required and each is handed exactly its own slice`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = 2) { options ->
            EmbeddingResult(
                embeddings = when (options.values) {
                    testValues.subList(0, 2) -> dummyEmbeddings.subList(0, 2)
                    testValues.subList(2, 3) -> dummyEmbeddings.subList(2, 3)
                    else -> error("unexpected chunk ${options.values}")
                },
            )
        }

        val result = embedMany(model, EmbeddingCallOptions(testValues))

        // Concatenation in call order is what pairs a vector back to its value; nothing downstream can
        // detect the pairing being wrong, so this is the assertion the whole wrapper exists for.
        assertEquals(dummyEmbeddings, result.embeddings)
        assertEquals(listOf(testValues.subList(0, 2), testValues.subList(2, 3)), model.chunks)
    }

    @Test
    fun `the values the caller passed are echoed on the result`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = 5) { EmbeddingResult(embeddings = dummyEmbeddings) }

        assertEquals(testValues, embedMany(model, EmbeddingCallOptions(testValues)).values)
    }

    @Test
    fun `an empty batch makes no call at all`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = 2) { EmbeddingResult(embeddings = emptyList()) }

        val result = embedMany(model, EmbeddingCallOptions(emptyList()))

        assertEquals(emptyList(), result.embeddings)
        assertEquals(0, model.chunks.size)
    }

    // -----------------------------------------------------------------------------------------------
    // embedMany — the byte budget
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a batch under the count ceiling but over the byte budget is split anyway`() = runTest {
        // Three ~25-byte values against a 60-byte budget: the count ceiling alone would send them as
        // one call, and the vendor would answer 400.
        val model = ScriptedEmbeddingModel(maxPerCall = 10, maxBytes = 60) { options ->
            EmbeddingResult(embeddings = options.values.map { listOf(0.0) })
        }

        val result = embedMany(model, EmbeddingCallOptions(testValues))

        assertEquals(2, model.chunks.size)
        assertEquals(testValues.subList(0, 2), model.chunks[0])
        assertEquals(testValues.subList(2, 3), model.chunks[1])
        assertEquals(3, result.embeddings.size)
    }

    @Test
    fun `the byte budget alone splits, with no count ceiling declared`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = null, maxBytes = 30) { options ->
            EmbeddingResult(embeddings = options.values.map { listOf(0.0) })
        }

        embedMany(model, EmbeddingCallOptions(testValues))

        // Every value is over half the budget, so no two fit together.
        assertEquals(testValues.map { listOf(it) }, model.chunks)
    }

    @Test
    fun `a single value larger than the whole budget still goes out, alone`() = runTest {
        val oversized = "x".repeat(100)
        val model = ScriptedEmbeddingModel(maxPerCall = null, maxBytes = 10) { options ->
            EmbeddingResult(embeddings = options.values.map { listOf(0.0) })
        }

        embedMany(model, EmbeddingCallOptions(listOf("tiny", oversized, "small")))

        // The value is the unit of embedding: splitting it would embed two fragments of a document,
        // and dropping it would be silent. The vendor's own error names the real problem.
        assertEquals(listOf(listOf("tiny"), listOf(oversized), listOf("small")), model.chunks)
    }

    @Test
    fun `byte length is UTF-8 bytes, not characters`() = runTest {
        // Four 3-byte CJK characters: 4 characters, 12 bytes. An 11-byte budget must split them; a
        // character count would not.
        val values = listOf("語語", "語語")
        val model = ScriptedEmbeddingModel(maxPerCall = null, maxBytes = 11) { options ->
            EmbeddingResult(embeddings = options.values.map { listOf(0.0) })
        }

        embedMany(model, EmbeddingCallOptions(values))

        assertEquals(2, model.chunks.size)
    }

    @Test
    fun `a non-positive budget is an argument error, not an infinite loop`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = null, maxBytes = 0) {
            EmbeddingResult(embeddings = emptyList())
        }

        assertFailsWith<InvalidArgumentError> {
            embedMany(model, EmbeddingCallOptions(testValues))
        }
    }

    // -----------------------------------------------------------------------------------------------
    // embedMany — aggregation across calls
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `usage is the sum across every call, not the last call's`() = runTest {
        var call = 0
        val model = ScriptedEmbeddingModel(maxPerCall = 2) {
            EmbeddingResult(embeddings = dummyEmbeddings.take(it.values.size), usage = if (call++ == 0) 10 else 20)
        }

        assertEquals(30, embedMany(model, EmbeddingCallOptions(testValues)).usage)
    }

    @Test
    fun `usage stays absent when no call reported one`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = 2) { EmbeddingResult(embeddings = dummyEmbeddings.take(it.values.size)) }

        assertNull(embedMany(model, EmbeddingCallOptions(testValues)).usage)
    }

    @Test
    fun `there is one response per call that was actually made, in call order`() = runTest {
        var call = 0
        val model = ScriptedEmbeddingModel(maxPerCall = 1) {
            EmbeddingResult(
                embeddings = listOf(dummyEmbeddings[0]),
                response = ModalityResponse(id = "response-${call++}"),
            )
        }

        val result = embedMany(model, EmbeddingCallOptions(testValues))

        // A single collapsed "the response" picks one of three arbitrarily, which is precisely the field
        // a caller reaches for when a vendor rate-limits half a batch.
        assertEquals(listOf("response-0", "response-1", "response-2"), result.responses.map { it?.id })
    }

    @Test
    fun `warnings from different calls all survive the aggregation`() = runTest {
        var call = 0
        val first = Warning.Other("Warning from call 1")
        val second = Warning.Unsupported("dimensions")
        val model = ScriptedEmbeddingModel(maxPerCall = 2) {
            EmbeddingResult(
                embeddings = dummyEmbeddings.take(it.values.size),
                warnings = listOf(if (call++ == 0) first else second),
            )
        }

        assertEquals(listOf(first, second), embedMany(model, EmbeddingCallOptions(testValues)).warnings)
    }

    @Test
    fun `a model that warns about nothing yields an empty list, never a null`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = 2) { EmbeddingResult(embeddings = dummyEmbeddings.take(it.values.size)) }

        assertEquals(emptyList(), embedMany(model, EmbeddingCallOptions(testValues)).warnings)
    }

    @Test
    fun `provider metadata from a single call is returned verbatim`() = runTest {
        val metadata: ProviderMetadata = mapOf(
            "gateway" to buildJsonObject {
                put("routing", buildJsonObject { put("resolvedProvider", "test-provider") })
            },
        )
        val model = ScriptedEmbeddingModel(maxPerCall = 3) {
            EmbeddingResult(embeddings = dummyEmbeddings.take(it.values.size), providerMetadata = metadata)
        }

        assertEquals(metadata, embedMany(model, EmbeddingCallOptions(testValues)).providerMetadata)
    }

    /**
     * DEFECT — see the report. `embedMany` keeps `firstNotNullOfOrNull { it.providerMetadata }`, so
     * everything a chunk after the first attached under its own namespace is discarded. The reference
     * merges per provider id across calls; a routing decision or a per-chunk request id from chunk two
     * is simply gone here, with the result still looking populated because chunk one's block is present.
     */
    @Test
    fun `provider metadata is merged across chunks rather than taken from the first`() = runTest {
        var call = 0
        val model = ScriptedEmbeddingModel(maxPerCall = 2) {
            val index = call++
            EmbeddingResult(
                embeddings = dummyEmbeddings.take(it.values.size),
                providerMetadata = mapOf(
                    "testProvider" to buildJsonObject { put("requestId", "req-$index") },
                    (if (index == 0) "first" else "second") to
                        buildJsonObject { put("seen", true) },
                ),
            )
        }

        val metadata = embedMany(model, EmbeddingCallOptions(testValues)).providerMetadata

        assertEquals(setOf("testProvider", "first", "second"), metadata?.keys)
    }

    // -----------------------------------------------------------------------------------------------
    // embedMany — what reaches the model
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `per-call headers and provider options reach every chunk, not only the first`() = runTest {
        val seen = mutableListOf<Pair<Map<String, String>?, JsonObject?>>()
        val model = ScriptedEmbeddingModel(maxPerCall = 1) { options ->
            seen += options.headers to options.providerOptions?.get("aProvider")
            EmbeddingResult(embeddings = listOf(dummyEmbeddings[0]))
        }

        embedMany(
            model,
            EmbeddingCallOptions(
                values = testValues,
                providerOptions = mapOf("aProvider" to buildJsonObject { put("someKey", "someValue") }),
                headers = mapOf("custom-request-header" to "request-header-value"),
            ),
        )

        assertEquals(3, seen.size)
        // A chunker that rebuilt the options instead of copying them would drop these on every call but
        // the one the test happened to look at.
        seen.forEach { (headers, options) ->
            assertEquals(mapOf("custom-request-header" to "request-header-value"), headers)
            assertEquals(buildJsonObject { put("someKey", "someValue") }, options)
        }
    }

    // -----------------------------------------------------------------------------------------------
    // embedMany — supportsParallelCalls, which had no reader at all until the wrapper existed
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a model that refuses parallel calls finishes each call before starting the next`() = runTest {
        val events = mutableListOf<String>()
        val gates = List(3) { CompletableDeferred<Unit>() }
        var call = 0
        val model = ScriptedEmbeddingModel(maxPerCall = 1, parallel = false) {
            val index = call++
            events += "start-$index"
            gates[index].await()
            events += "end-$index"
            EmbeddingResult(embeddings = listOf(dummyEmbeddings[0]))
        }

        val running = async { embedMany(model, EmbeddingCallOptions(testValues)) }
        runCurrent()

        // Nothing but the first call has begun while the first call is still in flight — several vendors
        // serialize per key and answer a fan-out with 429s, which is the whole point of the flag.
        assertEquals(listOf("start-0"), events)
        gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf("start-0", "end-0", "start-1"), events)
        gates[1].complete(Unit)
        runCurrent()
        assertEquals(listOf("start-0", "end-0", "start-1", "end-1", "start-2"), events)
        gates[2].complete(Unit)
        running.await()
    }

    @Test
    fun `a model that allows parallel calls has all of them in flight at once`() = runTest {
        val events = mutableListOf<String>()
        val gates = List(3) { CompletableDeferred<Unit>() }
        var call = 0
        val model = ScriptedEmbeddingModel(maxPerCall = 1, parallel = true) {
            val index = call++
            events += "start-$index"
            gates[index].await()
            EmbeddingResult(embeddings = listOf(dummyEmbeddings[0]))
        }

        val running = async { embedMany(model, EmbeddingCallOptions(testValues)) }
        runCurrent()

        assertEquals(listOf("start-0", "start-1", "start-2"), events)
        gates.forEach { it.complete(Unit) }
        running.await()
    }

    // -----------------------------------------------------------------------------------------------
    // embed — the single-value wrapper
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `embed carries every field the model reported through onto the result`() = runTest {
        val metadata: ProviderMetadata = mapOf("p" to buildJsonObject { put("k", "v") })
        val warning = Warning.Other("Setting is not supported")
        val model = ScriptedEmbeddingModel(maxPerCall = null) {
            EmbeddingResult(
                embeddings = listOf(dummyEmbeddings[0]),
                usage = 7,
                warnings = listOf(warning),
                providerMetadata = metadata,
                response = ModalityResponse(id = "res-1", modelId = "embed-1"),
            )
        }

        val result = embed(model, "test-input")

        assertEquals("test-input", result.value)
        assertEquals(dummyEmbeddings[0], result.embedding)
        assertEquals(7, result.usage)
        assertEquals(listOf(warning), result.warnings)
        assertEquals(metadata, result.providerMetadata)
        assertEquals("res-1", result.response?.id)
    }

    @Test
    fun `embed passes the value as a one-element batch with the caller's options`() = runTest {
        var seen: EmbeddingCallOptions? = null
        val model = ScriptedEmbeddingModel(maxPerCall = null) { options ->
            seen = options
            EmbeddingResult(embeddings = listOf(dummyEmbeddings[0]))
        }

        embed(
            model,
            value = "test-input",
            providerOptions = mapOf("aProvider" to buildJsonObject { put("someKey", "someValue") }),
            headers = mapOf("custom-request-header" to "request-header-value"),
        )

        assertEquals(listOf("test-input"), seen?.values)
        assertEquals(
            buildJsonObject { put("someKey", "someValue") },
            seen?.providerOptions?.get("aProvider"),
        )
        assertEquals(mapOf("custom-request-header" to "request-header-value"), seen?.headers)
    }

    // -----------------------------------------------------------------------------------------------
    // rerank
    // -----------------------------------------------------------------------------------------------

    private val rerankDocuments = listOf(
        "sunny day at the beach",
        "rainy afternoon in the city",
        "snowy night in the mountains",
    )

    @Test
    fun `rerank returns the model's ranking untouched and in its order`() = runTest {
        val model = ScriptedRerankingModel {
            RerankingResult(
                ranking = listOf(
                    RerankingResult.Rank(index = 2, relevanceScore = 0.9),
                    RerankingResult.Rank(index = 0, relevanceScore = 0.4),
                ),
            )
        }

        val result = rerank(
            model,
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(rerankDocuments),
                query = "snow",
                topN = 2,
            ),
        )

        assertEquals(listOf(2, 0), result.ranking.map { it.index })
        assertEquals(listOf(0.9, 0.4), result.ranking.map { it.relevanceScore })
    }

    @Test
    fun `rerankedDocuments resolves the indices back into the submitted documents`() = runTest {
        val model = ScriptedRerankingModel {
            RerankingResult(
                ranking = listOf(
                    RerankingResult.Rank(index = 2, relevanceScore = 0.9),
                    RerankingResult.Rank(index = 0, relevanceScore = 0.4),
                ),
            )
        }

        val result = rerank(
            model,
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(rerankDocuments),
                query = "snow",
            ),
        )

        // Resolving an index list by hand is where an off-by-one silently reorders a retrieval pipeline.
        assertEquals(
            listOf("snowy night in the mountains", "sunny day at the beach"),
            result.rerankedDocuments(rerankDocuments),
        )
    }

    @Test
    fun `rerankedDocuments drops an unknown index on a result assembled outside rerank`() {
        // `rerank` itself refuses such a ranking (below); a stored or hand-built result may still carry one.
        val result = RerankingResult(
            ranking = listOf(
                RerankingResult.Rank(index = 0, relevanceScore = 0.9),
                RerankingResult.Rank(index = 99, relevanceScore = 0.1),
            ),
        )

        assertEquals(listOf("sunny day at the beach"), result.rerankedDocuments(rerankDocuments))
    }

    @Test
    fun `rerank rejects a ranking that names an index outside the submitted documents`() = runTest {
        val model = ScriptedRerankingModel {
            RerankingResult(
                ranking = listOf(
                    RerankingResult.Rank(index = 0, relevanceScore = 0.9),
                    RerankingResult.Rank(index = 99, relevanceScore = 0.1),
                ),
            )
        }

        val error = assertFailsWith<InvalidResponseDataError> {
            rerank(model, RerankingCallOptions(documents = RerankingCallOptions.Documents.Text(rerankDocuments), query = "snow"))
        }

        assertEquals("Invalid ranking index 99. Expected an integer between 0 and 2.", error.message)
    }

    @Test
    fun `rerank rejects a negative index, and counts object documents the same way`() = runTest {
        val model = ScriptedRerankingModel {
            RerankingResult(ranking = listOf(RerankingResult.Rank(index = -1, relevanceScore = 0.5)))
        }
        val documents = RerankingCallOptions.Documents.Objects(List(2) { buildJsonObject { put("id", it) } })

        val error = assertFailsWith<InvalidResponseDataError> {
            rerank(model, RerankingCallOptions(documents = documents, query = "snow"))
        }

        assertEquals("Invalid ranking index -1. Expected an integer between 0 and 1.", error.message)
    }

    @Test
    fun `embed rejects a response with no embeddings`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = null) { EmbeddingResult(embeddings = emptyList()) }

        val error = assertFailsWith<InvalidResponseDataError> { embed(model, "hello") }

        assertEquals("No embedding generated.", error.message)
    }

    @Test
    fun `embedMany rejects a call whose vector count does not match its values`() = runTest {
        val model = ScriptedEmbeddingModel(maxPerCall = null) { EmbeddingResult(embeddings = listOf(listOf(0.1))) }

        val error = assertFailsWith<InvalidResponseDataError> {
            embedMany(model, EmbeddingCallOptions(values = listOf("a", "b")))
        }

        assertEquals("Expected 2 embeddings, but received 1.", error.message)
    }

    @Test
    fun `the vector count is checked per chunk, not only on the joined batch`() = runTest {
        // Three values against a ceiling of two: the second chunk holds one value and answers with none.
        val model = ScriptedEmbeddingModel(maxPerCall = 2, parallel = false) { options ->
            EmbeddingResult(embeddings = if (options.values.size == 2) listOf(listOf(0.1), listOf(0.2)) else emptyList())
        }

        val error = assertFailsWith<InvalidResponseDataError> {
            embedMany(model, EmbeddingCallOptions(values = listOf("a", "b", "c")))
        }

        assertEquals("Expected 1 embeddings, but received 0.", error.message)
    }

    @Test
    fun `rerank forwards object documents, the query, topN, options and headers unchanged`() = runTest {
        var seen: RerankingCallOptions? = null
        val model = ScriptedRerankingModel { options ->
            seen = options
            RerankingResult(ranking = emptyList())
        }
        val objects = listOf(
            buildJsonObject { put("title", "beach") },
            buildJsonObject { put("title", "city") },
        )

        rerank(
            model,
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Objects(objects),
                query = "rain",
                topN = 1,
                providerOptions = mapOf("aProvider" to buildJsonObject { put("someKey", "someValue") }),
                headers = mapOf("custom-request-header" to "request-header-value"),
            ),
        )

        assertEquals(RerankingCallOptions.Documents.Objects(objects), seen?.documents)
        assertEquals("rain", seen?.query)
        assertEquals(1, seen?.topN)
        assertEquals(mapOf("custom-request-header" to "request-header-value"), seen?.headers)
    }

    @Test
    fun `rerank still calls the model when the document list is empty`() = runTest {
        var calls = 0
        val model = ScriptedRerankingModel {
            calls++
            RerankingResult(ranking = emptyList())
        }

        val result = rerank(
            model,
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(emptyList()),
                query = "anything",
            ),
        )

        // The reference fires its lifecycle callbacks for an empty document list rather than
        // short-circuiting, because "nothing matched" and "we never asked" are different answers.
        assertEquals(1, calls)
        assertEquals(emptyList(), result.ranking)
        assertEquals(emptyList(), result.rerankedDocuments(rerankDocuments))
    }

    @Test
    fun `rerank carries warnings, provider metadata and the response through`() = runTest {
        val metadata: ProviderMetadata = mapOf("p" to buildJsonObject { put("k", "v") })
        val model = ScriptedRerankingModel {
            RerankingResult(
                ranking = listOf(RerankingResult.Rank(index = 0, relevanceScore = 1.0)),
                warnings = listOf(Warning.Compatibility("object documents")),
                providerMetadata = metadata,
                response = ModalityResponse(id = "res-1"),
            )
        }

        val result = rerank(
            model,
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(rerankDocuments),
                query = "snow",
            ),
        )

        assertEquals(listOf(Warning.Compatibility("object documents")), result.warnings)
        assertEquals(metadata, result.providerMetadata)
        assertEquals("res-1", result.response?.id)
    }
}

// ---------------------------------------------------------------------------------------------------
// Fakes — scripted rather than recording, so a case can answer differently on its second call
// ---------------------------------------------------------------------------------------------------

private class ScriptedEmbeddingModel(
    private val maxPerCall: Int?,
    private val parallel: Boolean = true,
    private val maxBytes: Int? = null,
    private val answer: suspend (EmbeddingCallOptions) -> EmbeddingResult,
) : EmbeddingModel {

    override val provider: String = "test-provider"
    override val modelId: String = "embed-1"

    val chunks: MutableList<List<String>> = mutableListOf()

    override suspend fun maxEmbeddingsPerCall(): Int? = maxPerCall

    override suspend fun maxInputBytesPerCall(): Int? = maxBytes

    override suspend fun supportsParallelCalls(): Boolean = parallel

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        chunks += options.values
        return answer(options)
    }
}

private class ScriptedRerankingModel(
    private val answer: suspend (RerankingCallOptions) -> RerankingResult,
) : RerankingModel {

    override val provider: String = "test-provider"
    override val modelId: String = "rerank-1"

    override suspend fun doRerank(options: RerankingCallOptions): RerankingResult = answer(options)
}
