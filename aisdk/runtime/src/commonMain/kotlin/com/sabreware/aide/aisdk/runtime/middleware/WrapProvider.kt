package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingModelMiddleware
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageModelMiddleware
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechTranslationModel
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.withMiddleware

/**
 * Applies middleware to EVERY model a provider hands out, instead of at each construction site.
 *
 * The provider is the right place for cross-cutting policy — logging, redaction, defaults — because a
 * model constructed later, by code that never heard of the middleware, still gets it. Wrapping at the
 * call site instead is how one un-wrapped lookup quietly bypasses the policy.
 *
 * A modality the provider does not serve stays absent: null in, null out, per the [Provider] contract.
 * The modalities with no middleware contract yet (speech, transcription, reranking, video, speech
 * translation) pass through untouched — the reference's `wrapProvider` does the same, wrapping only
 * what has a middleware type.
 *
 * Ordering within each list is [withMiddleware]'s: first is outermost.
 *
 * @param provider the provider whose models get wrapped.
 * @param languageModelMiddleware applied to every language model the provider returns.
 * @param embeddingModelMiddleware applied to every embedding model. Ours-extra: the reference's
 *   `wrapProvider` predates its embedding middleware and cannot apply one; a wrapper that skips a
 *   modality it has a contract for would force per-model hand-wrapping right back.
 * @param imageModelMiddleware applied to every image model.
 */
public fun wrapProvider(
    provider: Provider,
    languageModelMiddleware: List<LanguageModelMiddleware> = emptyList(),
    embeddingModelMiddleware: List<EmbeddingModelMiddleware> = emptyList(),
    imageModelMiddleware: List<ImageModelMiddleware> = emptyList(),
): Provider = object : Provider {

    override val providerId: String = provider.providerId

    override fun languageModel(modelId: String): LanguageModel? =
        provider.languageModel(modelId)?.withMiddleware(*languageModelMiddleware.toTypedArray())

    override fun embeddingModel(modelId: String): EmbeddingModel? =
        provider.embeddingModel(modelId)?.withMiddleware(*embeddingModelMiddleware.toTypedArray())

    override fun imageModel(modelId: String): ImageModel? =
        provider.imageModel(modelId)?.withMiddleware(*imageModelMiddleware.toTypedArray())

    override fun speechModel(modelId: String): SpeechModel? = provider.speechModel(modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel? =
        provider.transcriptionModel(modelId)

    override fun rerankingModel(modelId: String): RerankingModel? = provider.rerankingModel(modelId)

    override fun videoModel(modelId: String): VideoModel? = provider.videoModel(modelId)

    override fun speechTranslationModel(modelId: String): SpeechTranslationModel? =
        provider.speechTranslationModel(modelId)
}
