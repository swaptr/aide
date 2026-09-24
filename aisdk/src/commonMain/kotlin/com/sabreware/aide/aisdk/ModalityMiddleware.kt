package com.sabreware.aide.aisdk

/**
 * A decorator around an [EmbeddingModel] — caching, batching policy, settings defaults.
 *
 * The same shape as [LanguageModelMiddleware], for the same reason: every member has a default, so a
 * middleware overrides only what it cares about and the rest passes through untouched.
 *
 * The capability overrides return null to mean "no opinion" — the wrapped model's own answer stands.
 * That mirrors the reference, where a hook returning `undefined` falls back to the model, and it means a
 * middleware CANNOT override a ceiling to "unlimited"; lifting a limit the model declared would let a
 * batch through that the vendor then rejects.
 */
public interface EmbeddingModelMiddleware {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject middleware built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** Rewrite the reported provider id. */
    public fun overrideProvider(model: EmbeddingModel): String? = null

    /** Rewrite the reported model id. */
    public fun overrideModelId(model: EmbeddingModel): String? = null

    /** Rewrite the per-call ceiling. Null = no opinion; the model's own ceiling stands. */
    public suspend fun overrideMaxEmbeddingsPerCall(model: EmbeddingModel): Int? = null

    /** Rewrite whether calls may run concurrently. Null = no opinion. */
    public suspend fun overrideSupportsParallelCalls(model: EmbeddingModel): Boolean? = null

    /** Rewrite the call before it reaches the model. */
    public suspend fun transformParams(
        params: EmbeddingCallOptions,
        model: EmbeddingModel,
    ): EmbeddingCallOptions = params

    /**
     * Wrap the embed call.
     *
     * [doEmbed] proceeds to the wrapped model. Not calling it is legitimate — that is how a cache
     * short-circuits — but a middleware that neither calls it nor produces a result has swallowed the
     * request.
     */
    public suspend fun wrapEmbed(
        params: EmbeddingCallOptions,
        model: EmbeddingModel,
        doEmbed: suspend () -> EmbeddingResult,
    ): EmbeddingResult = doEmbed()
}

/**
 * Applies [middleware] to this model, outermost first.
 *
 * Order matters and reads left to right, exactly as on [LanguageModel.withMiddleware]:
 * `model.withMiddleware(logging, caching)` puts logging on the outside, so it observes cache hits too.
 */
public fun EmbeddingModel.withMiddleware(vararg middleware: EmbeddingModelMiddleware): EmbeddingModel =
    middleware.foldRight(this) { layer, inner -> WrappedEmbeddingModel(inner, layer) }

private class WrappedEmbeddingModel(
    private val inner: EmbeddingModel,
    private val middleware: EmbeddingModelMiddleware,
) : EmbeddingModel {

    override val provider: String get() = middleware.overrideProvider(inner) ?: inner.provider

    override val modelId: String get() = middleware.overrideModelId(inner) ?: inner.modelId

    override suspend fun maxEmbeddingsPerCall(): Int? =
        middleware.overrideMaxEmbeddingsPerCall(inner) ?: inner.maxEmbeddingsPerCall()

    override suspend fun supportsParallelCalls(): Boolean =
        middleware.overrideSupportsParallelCalls(inner) ?: inner.supportsParallelCalls()

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        val params = middleware.transformParams(options, inner)
        return middleware.wrapEmbed(params, inner) { inner.doEmbed(params) }
    }
}

/**
 * A decorator around an [ImageModel].
 *
 * See [EmbeddingModelMiddleware] for the contract's rules — every member defaults to pass-through, and
 * a capability override returning null leaves the model's own answer standing.
 */
public interface ImageModelMiddleware {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject middleware built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** Rewrite the reported provider id. */
    public fun overrideProvider(model: ImageModel): String? = null

    /** Rewrite the reported model id. */
    public fun overrideModelId(model: ImageModel): String? = null

    /** Rewrite the per-call ceiling. Null = no opinion; the model's own ceiling stands. */
    public suspend fun overrideMaxImagesPerCall(model: ImageModel): Int? = null

    /** Rewrite the call before it reaches the model. */
    public suspend fun transformParams(
        params: ImageCallOptions,
        model: ImageModel,
    ): ImageCallOptions = params

    /** Wrap the generate call. @see EmbeddingModelMiddleware.wrapEmbed */
    public suspend fun wrapGenerate(
        params: ImageCallOptions,
        model: ImageModel,
        doGenerate: suspend () -> ImageResult,
    ): ImageResult = doGenerate()
}

/**
 * Applies [middleware] to this model, outermost first — the ordering rule of
 * [LanguageModel.withMiddleware], verbatim.
 */
public fun ImageModel.withMiddleware(vararg middleware: ImageModelMiddleware): ImageModel =
    middleware.foldRight(this) { layer, inner -> WrappedImageModel(inner, layer) }

private class WrappedImageModel(
    private val inner: ImageModel,
    private val middleware: ImageModelMiddleware,
) : ImageModel {

    override val provider: String get() = middleware.overrideProvider(inner) ?: inner.provider

    override val modelId: String get() = middleware.overrideModelId(inner) ?: inner.modelId

    override suspend fun maxImagesPerCall(): Int? =
        middleware.overrideMaxImagesPerCall(inner) ?: inner.maxImagesPerCall()

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val params = middleware.transformParams(options, inner)
        return middleware.wrapGenerate(params, inner) { inner.doGenerate(params) }
    }
}
