package com.sabreware.aide.aisdk

/**
 * A decorator around a [LanguageModel] — caching, logging, guardrails, prompt rewriting, retries.
 *
 * Every member has a default, so a middleware overrides only what it cares about and the rest passes
 * through untouched. That is what keeps a logging middleware three lines long instead of a full
 * re-implementation of the interface.
 *
 * The call type is passed to [transformParams] because generate and stream are not always the same
 * decision: a cache may serve a whole generate result and refuse to fake a stream.
 */
public interface LanguageModelMiddleware {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject middleware built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** Whether this call is streaming. Some middleware behaves differently per mode. */
    public enum class CallType { Generate, Stream }

    /** Rewrite the reported provider id. */
    public fun overrideProvider(model: LanguageModel): String? = null

    /** Rewrite the reported model id. */
    public fun overrideModelId(model: LanguageModel): String? = null

    /** Rewrite which URLs the model claims to fetch for itself. */
    public suspend fun overrideSupportedUrls(model: LanguageModel): Map<String, List<Regex>>? = null

    /** Rewrite the call before it reaches the model. */
    public suspend fun transformParams(
        type: CallType,
        params: CallOptions,
        model: LanguageModel,
    ): CallOptions = params

    /**
     * Wrap a non-streaming call.
     *
     * [doGenerate] proceeds to the wrapped model. Not calling it is legitimate — that is how a cache
     * short-circuits — but a middleware that neither calls it nor produces a result has swallowed the
     * request.
     */
    public suspend fun wrapGenerate(
        params: CallOptions,
        model: LanguageModel,
        doGenerate: suspend () -> GenerateResult,
    ): GenerateResult = doGenerate()

    /** Wrap a streaming call. @see wrapGenerate */
    public suspend fun wrapStream(
        params: CallOptions,
        model: LanguageModel,
        doStream: suspend () -> StreamResult,
    ): StreamResult = doStream()
}

/**
 * Applies [middleware] to this model, outermost first.
 *
 * Order matters and reads left to right: `model.withMiddleware(logging, caching)` puts logging on the
 * outside, so it observes cache hits too. Reverse them and the log only ever sees misses — a difference
 * that is invisible until someone asks why the numbers disagree.
 */
public fun LanguageModel.withMiddleware(vararg middleware: LanguageModelMiddleware): LanguageModel =
    middleware.foldRight(this) { layer, inner -> WrappedLanguageModel(inner, layer) }

private class WrappedLanguageModel(
    private val inner: LanguageModel,
    private val middleware: LanguageModelMiddleware,
) : LanguageModel {

    override val provider: String get() = middleware.overrideProvider(inner) ?: inner.provider

    override val modelId: String get() = middleware.overrideModelId(inner) ?: inner.modelId

    override suspend fun supportedUrls(): Map<String, List<Regex>> =
        middleware.overrideSupportedUrls(inner) ?: inner.supportedUrls()

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val params = middleware.transformParams(
            LanguageModelMiddleware.CallType.Generate,
            options,
            inner,
        )
        return middleware.wrapGenerate(params, inner) { inner.doGenerate(params) }
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val params = middleware.transformParams(
            LanguageModelMiddleware.CallType.Stream,
            options,
            inner,
        )
        return middleware.wrapStream(params, inner) { inner.doStream(params) }
    }
}
