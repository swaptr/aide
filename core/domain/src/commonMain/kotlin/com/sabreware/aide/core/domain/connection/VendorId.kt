package com.sabreware.aide.core.domain.connection

/**
 * A kind of API the app can talk to — OpenAI-compatible, Anthropic, Gemini, ElevenLabs. Code, not data:
 * each is a [Vendor] contributed in the DI graph. The user never holds a vendor; they hold [Connection]s,
 * each an instance of one.
 *
 * String-backed so a new vendor is a new binding and a new constant, not an edit to a closed enum.
 */
@JvmInline
value class VendorId(val value: String) {
    companion object {
        /**
         * One `/v1/chat/completions` wire serving OpenAI, Ollama (self-hosted or cloud), OpenRouter, Groq,
         * vLLM, LM Studio… The endpoint picks the quirks (`CompatVendors`), so they are presets of one vendor.
         */
        val OPENAI_COMPATIBLE = VendorId("openai")
        val ANTHROPIC = VendorId("anthropic")
        val GEMINI = VendorId("gemini")
        val ELEVENLABS = VendorId("elevenlabs")
    }
}
