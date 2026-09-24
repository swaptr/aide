package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult

// The Gemini media models Vertex REUSES rather than re-implements: image generation and Gemini TTS are
// the same model and the same wire on either host, so the google package's implementations serve both —
// exactly as VertexProvider.languageModel already reuses GoogleLanguageModel. These wrappers exist for
// one job only: re-filing `providerOptions["google-vertex"]` into the `google` namespace the delegates
// read (see withVertexRefiled), so a caller holding a Vertex provider files options under the id it
// constructed and still reaches the model. Provider identity stays `google` — the host changed, the
// model did not, which is the precedent VertexAnthropicLanguageModel documents.

/** Gemini image generation on Vertex — [delegate] does everything; options are re-filed first. */
internal class VertexGeminiImageModel(private val delegate: ImageModel) : ImageModel by delegate {

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult =
        delegate.doGenerate(options.copy(providerOptions = options.providerOptions.withVertexRefiled()))
}

/** Gemini TTS on Vertex — [delegate] does everything; options are re-filed first. */
internal class VertexGeminiSpeechModel(private val delegate: SpeechModel) : SpeechModel by delegate {

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult =
        delegate.doGenerate(options.copy(providerOptions = options.providerOptions.withVertexRefiled()))
}
