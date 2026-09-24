package com.sabreware.aide.core.domain.rerank

/**
 * Orders documents by how well they answer a query.
 *
 * Returns positions into the submitted list rather than the documents themselves. The caller already
 * holds the documents — often alongside ids, scores or metadata this layer knows nothing about — and
 * handing back copies would force it to match them up again by content, which is both wasteful and
 * ambiguous when two documents are identical.
 */
interface RerankEngine {
    suspend fun rerank(
        modelName: String,
        query: String,
        documents: List<String>,
        topN: Int? = null,
    ): List<Ranked>
}

/** One result: where the document sat in the submitted list, and how relevant it is. Best first. */
data class Ranked(val index: Int, val relevanceScore: Double)
