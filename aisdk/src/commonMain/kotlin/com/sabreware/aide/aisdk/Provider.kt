package com.sabreware.aide.aisdk

/**
 * A vendor: the factory that turns a model id into a model.
 *
 * **Divergence from the reference, deliberate.** `ProviderV4` declares `languageModel`,
 * `embeddingModel` and `imageModel` as required members, so a vendor offering no image models has to
 * supply one that throws. That is a stub whose only job is to fail, and it makes "does this provider do
 * images?" unanswerable without calling it and catching.
 *
 * Here every modality defaults to `null`, meaning *this provider offers no models of that kind at all*.
 * A caller checks for null and omits the affordance; nothing has to throw to say "absent".
 *
 * The two failure modes stay distinct, which is the point:
 *
 * - `imageModel("anything") == null` → this provider does not do images. Not an error.
 * - `languageModel("typo-4o")` throws [NoSuchModelError] → this provider does language models, and that
 *   is not one of them.
 *
 * A provider that offers a modality must therefore throw rather than return null for an unrecognized id,
 * or the caller cannot tell a missing capability from a mistyped name.
 */
public interface Provider {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a provider built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, and the key its [ProviderMetadata] is filed under. */
    public val providerId: String

    /**
     * @throws NoSuchModelError if this provider serves language models but not this one.
     * @return null if this provider serves no language models at all.
     */
    public fun languageModel(modelId: String): LanguageModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun embeddingModel(modelId: String): EmbeddingModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun imageModel(modelId: String): ImageModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun speechModel(modelId: String): SpeechModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun transcriptionModel(modelId: String): TranscriptionModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun rerankingModel(modelId: String): RerankingModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun videoModel(modelId: String): VideoModel? = null

    /** @see languageModel for the null-versus-throw contract. */
    public fun speechTranslationModel(modelId: String): SpeechTranslationModel? = null
}

/**
 * Language model or [NoSuchModelError] — never null.
 *
 * For a caller that already knows the provider does language models and wants the missing case to be
 * loud rather than silently skipped.
 */
public fun Provider.requireLanguageModel(modelId: String): LanguageModel =
    languageModel(modelId) ?: throw NoSuchModelError(modelId, NoSuchModelError.ModelType.LanguageModel)

/**
 * A provider assembled from pre-bound models, with an optional fallback.
 *
 * The reference's `customProvider`: the way a consumer aliases model ids (`"fast"` → a specific vendor
 * model, possibly wrapped in middleware) or overlays a few custom bindings on an existing provider.
 * Lookup order is the map first, then [fallbackProvider]; the maps hold MODELS, not factories, so an
 * alias resolves to the exact instance it was built with.
 *
 * The null-versus-throw contract of [Provider] is kept, adapted from the reference's throwing shape:
 *
 * - id in the map → that model.
 * - else, with a fallback → the fallback's answer, including its own [NoSuchModelError]. A fallback that
 *   serves NONE of the modality (returns null) does not absorb the miss: if this provider's own map is
 *   non-empty the modality IS served here, and the unknown id throws.
 * - else — no fallback: throw if the map is non-empty (modality served, id unknown), null if it is
 *   empty (modality not served at all).
 *
 * @param providerId the id this provider reports, and the namespace its models' metadata files under.
 *   The reference's provider object carries no id; ours requires one, so it is a parameter with a
 *   deliberately generic default.
 * @param fallbackProvider consulted for any id the maps do not bind. Null means the maps are the whole
 *   provider.
 */
public class CustomProvider(
    override val providerId: String = "custom",
    private val languageModels: Map<String, LanguageModel> = emptyMap(),
    private val embeddingModels: Map<String, EmbeddingModel> = emptyMap(),
    private val imageModels: Map<String, ImageModel> = emptyMap(),
    private val speechModels: Map<String, SpeechModel> = emptyMap(),
    private val transcriptionModels: Map<String, TranscriptionModel> = emptyMap(),
    private val rerankingModels: Map<String, RerankingModel> = emptyMap(),
    private val videoModels: Map<String, VideoModel> = emptyMap(),
    private val fallbackProvider: Provider? = null,
) : Provider {

    override fun languageModel(modelId: String): LanguageModel? =
        resolve(languageModels, modelId, NoSuchModelError.ModelType.LanguageModel) { it.languageModel(modelId) }

    override fun embeddingModel(modelId: String): EmbeddingModel? =
        resolve(embeddingModels, modelId, NoSuchModelError.ModelType.EmbeddingModel) { it.embeddingModel(modelId) }

    override fun imageModel(modelId: String): ImageModel? =
        resolve(imageModels, modelId, NoSuchModelError.ModelType.ImageModel) { it.imageModel(modelId) }

    override fun speechModel(modelId: String): SpeechModel? =
        resolve(speechModels, modelId, NoSuchModelError.ModelType.SpeechModel) { it.speechModel(modelId) }

    override fun transcriptionModel(modelId: String): TranscriptionModel? =
        resolve(transcriptionModels, modelId, NoSuchModelError.ModelType.TranscriptionModel) {
            it.transcriptionModel(modelId)
        }

    override fun rerankingModel(modelId: String): RerankingModel? =
        resolve(rerankingModels, modelId, NoSuchModelError.ModelType.RerankingModel) { it.rerankingModel(modelId) }

    override fun videoModel(modelId: String): VideoModel? =
        resolve(videoModels, modelId, NoSuchModelError.ModelType.VideoModel) { it.videoModel(modelId) }

    /**
     * Speech translation has no [NoSuchModelError.ModelType], so there is no honest error to throw for
     * a serve-but-unknown miss — the modality passes straight through to the fallback instead of
     * getting a map of its own.
     */
    override fun speechTranslationModel(modelId: String): SpeechTranslationModel? =
        fallbackProvider?.speechTranslationModel(modelId)

    private fun <M : Any> resolve(
        models: Map<String, M>,
        modelId: String,
        modelType: NoSuchModelError.ModelType,
        fromFallback: (Provider) -> M?,
    ): M? {
        models[modelId]?.let { return it }
        // A fallback that serves the modality answers or throws its own NoSuchModelError here; one that
        // serves none of it returns null and falls through.
        fallbackProvider?.let { fallback -> fromFallback(fallback)?.let { return it } }
        return if (models.isEmpty()) null else throw NoSuchModelError(modelId, modelType)
    }
}

/**
 * Looks up a provider by id, then a model within it.
 *
 * The registry owns no models and caches nothing; it is a map with two errors attached, which is all the
 * reference's registry is once the JS-specific `providerId:modelId` string parsing is removed. Callers
 * here already have both parts separately.
 */
public class ProviderRegistry(providers: List<Provider>) {

    private val byId: Map<String, Provider> = providers.associateBy { it.providerId }

    public val providerIds: Set<String> get() = byId.keys

    /** @throws NoSuchProviderError if nothing is registered under [providerId]. */
    public fun provider(
        providerId: String,
        modelId: String = "",
        modelType: NoSuchModelError.ModelType = NoSuchModelError.ModelType.LanguageModel,
    ): Provider = byId[providerId]
        ?: throw NoSuchProviderError(providerId, byId.keys.sorted(), modelId, modelType)

    /**
     * @throws NoSuchProviderError if the provider is unknown.
     * @throws NoSuchModelError if the provider is known but the model is not.
     */
    public fun languageModel(providerId: String, modelId: String): LanguageModel =
        provider(providerId, modelId, NoSuchModelError.ModelType.LanguageModel)
            .requireLanguageModel(modelId)
}
