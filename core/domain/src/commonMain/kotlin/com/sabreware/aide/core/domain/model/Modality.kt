package com.sabreware.aide.core.domain.model

/**
 * Model modality — what kind of model this is, with every modality a peer in ONE taxonomy
 * (chat LLM / ASR / TTS / VAD / image / embedding) instead of the parallel kind enums that used to
 * exist per subsystem. A string-backed value class (not a closed enum) because it is **persisted**
 * (pref slot keys, [ModelDescriptor.modality]) and **extensible**: a closed enum at a persisted point would
 * corrupt stored data the moment a new modality lands.
 */
@JvmInline
value class Modality(val value: String) {
    companion object {
        const val CHAT_KEY = "chat"
        const val ASR_KEY = "asr"
        const val TTS_KEY = "tts"
        const val VAD_KEY = "vad"
        const val IMAGE_KEY = "image"
        const val EMBEDDING_KEY = "embedding"

        /** Conversational LLM. */
        val Chat = Modality(CHAT_KEY)

        /** Speech-to-text (automatic speech recognition). */
        val Asr = Modality(ASR_KEY)

        /** Text-to-speech. */
        val Tts = Modality(TTS_KEY)

        /** Voice-activity detection. */
        val Vad = Modality(VAD_KEY)

        /** Image generation. */
        val Image = Modality(IMAGE_KEY)

        /** Text embeddings. */
        val Embedding = Modality(EMBEDDING_KEY)
    }
}
