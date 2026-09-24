package com.sabreware.aide.data.llm.openai


/**
 * Which of a compatible server's model ids are chat models.
 *
 * The listing itself is `AiSdkModelCatalog` — a plain `GET {base}/models`. What is left is the judgement
 * call: `/v1/models` lists
 * embeddings, speech, image and moderation models alongside chat ones, and offering those in a model
 * picker produces a confusing failure on first use rather than an obvious one.
 *
 * The filter is deliberately conservative — an id it does not recognise passes. A self-hosted server
 * serves whatever its operator loaded, and hiding an unfamiliar model is worse than showing one that
 * turns out not to chat.
 */
object OpenAiCatalog {

    // 2026 additions: transcribe (gpt-*-transcribe), realtime, sora (video), computer-use — all listed by
    // /v1/models but not chat-completions models.
    private val NON_CHAT_MARKERS = listOf(
        "embedding", "whisper", "tts", "dall-e", "moderation", "image", "rerank",
        "transcribe", "realtime", "sora", "computer-use",
    )

    fun isLikelyChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT_MARKERS.none { it in lower }
    }
}
