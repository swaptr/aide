package com.sabreware.aide.aisdk.providers.google

/**
 * Frames and bodies from the reference's `realtime/google-realtime-event-mapper.test.ts` and
 * `realtime/google-realtime-model.test.ts`, byte-for-byte where the reference spells them inline.
 */
internal object GoogleRealtimeFixtures {

    const val AUTH_TOKENS_URL: String =
        "https://generativelanguage.googleapis.com/v1alpha/auth_tokens?key=test-key"

    const val SOCKET_PATH: String =
        "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"

    /** The auth token reply of the first model test. */
    const val TOKEN_REPLY: String =
        """{"name":"projects/123/locations/us/accessTokens/abc","expireTime":"2026-01-01T00:05:00.000Z"}"""

    const val BARE_TOKEN_REPLY: String = """{"name":"token"}"""

    // ---- server frames ------------------------------------------------------------------------------

    const val SETUP_COMPLETE: String = """{"setupComplete":true}"""

    const val AUDIO_PART: String =
        """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"base64audio"}}]}}}"""

    const val TEXT_PART: String = """{"serverContent":{"modelTurn":{"parts":[{"text":"hello world"}]}}}"""

    const val OUTPUT_TRANSCRIPTION: String =
        """{"serverContent":{"outputTranscription":{"text":"transcribed text"}}}"""

    const val NESTED_INPUT_TRANSCRIPTION: String =
        """{"serverContent":{"inputTranscription":{"text":"Can you hear me?"}}}"""

    const val TOP_LEVEL_INPUT_TRANSCRIPTION: String = """{"inputTranscription":{"text":"Can you hear me?"}}"""

    const val INTERRUPTED: String = """{"serverContent":{"interrupted":true}}"""

    const val TURN_COMPLETE: String = """{"serverContent":{"turnComplete":true}}"""

    const val GENERATION_COMPLETE: String = """{"serverContent":{"generationComplete":true}}"""

    fun audioTurn(data: String): String =
        """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$data"}}]}}}"""

    fun textTurn(text: String): String = """{"serverContent":{"modelTurn":{"parts":[{"text":"$text"}]}}}"""

    const val LATE_TRANSCRIPT: String =
        """{"serverContent":{"outputTranscription":{"text":"late transcript"}}}"""

    const val MULTI_PART: String =
        """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"audio"}},{"text":"text"}]}}}"""

    const val TOOL_CALL: String =
        """{"toolCall":{"functionCalls":[{"id":"call_1","name":"getWeather","args":{"city":"NYC"}},""" +
            """{"id":"call_2","name":"rollDice","args":{}}]}}"""

    const val TOOL_CALL_CANCELLATION: String = """{"toolCallCancellation":{"ids":["call_1"]}}"""

    const val GO_AWAY: String = """{"goAway":{"timeLeft":"30s"}}"""

    const val SESSION_RESUMPTION_UPDATE: String =
        """{"sessionResumptionUpdate":{"newHandle":"resume-handle","resumable":true,""" +
            """"lastConsumedClientMessageIndex":"42"}}"""

    const val UNKNOWN_FRAME: String = """{"somethingNew":{"data":123}}"""

    // ---- expected client frames ---------------------------------------------------------------------

    const val BARE_SETUP: String =
        """{"setup":{"model":"models/gemini-2.0-flash-live-001","generationConfig":{"responseModalities":["AUDIO"]}}}"""

    const val FULL_SETUP: String =
        """{"setup":{"model":"models/gemini-2.0-flash-live-001",""" +
            """"systemInstruction":{"parts":[{"text":"Be helpful"}]},""" +
            """"generationConfig":{"responseModalities":["AUDIO","TEXT"],""" +
            """"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Puck"}}}},""" +
            """"tools":[{"functionDeclarations":[{"name":"getWeather","description":"Get weather",""" +
            """"parametersJsonSchema":{"type":"object","properties":{"city":{"type":"string"}}}}]}]}}"""

    const val TRANSLATION_SETUP: String =
        """{"setup":{"model":"models/gemini-3.5-live-translate-preview",""" +
            """"generationConfig":{"responseModalities":["AUDIO"],""" +
            """"translationConfig":{"targetLanguageCode":"pl","echoTargetLanguage":true}},""" +
            """"inputAudioTranscription":{},"outputAudioTranscription":{}}}"""

    fun audioAppend(rate: Int, data: String = "base64data"): String =
        """{"realtimeInput":{"audio":{"data":"$data","mimeType":"audio/pcm;rate=$rate"}}}"""

    const val AUDIO_STREAM_END: String = """{"realtimeInput":{"audioStreamEnd":true}}"""

    const val TEXT_INPUT: String = """{"realtimeInput":{"text":"hello"}}"""

    fun toolResponse(response: String): String =
        """{"toolResponse":{"functionResponses":[{"id":"call_1","name":"getWeather","response":$response}]}}"""

    // ---- expected setups from the buildGoogleSessionConfig snapshots -------------------------------

    const val MODEL_PATH_ONLY: String =
        """{"generationConfig":{"responseModalities":["AUDIO"]},"model":"models/gemini-2.0-flash"}"""

    const val INSTRUCTIONS_AND_VOICE: String =
        """{"generationConfig":{"responseModalities":["AUDIO"],""" +
            """"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Puck"}}}},""" +
            """"model":"models/gemini-2.0-flash","systemInstruction":{"parts":[{"text":"Be helpful"}]}}"""

    const val TOOLS_SNAPSHOT: String =
        """[{"functionDeclarations":[{"description":"Get weather","name":"getWeather",""" +
            """"parametersJsonSchema":{"properties":{"city":{"type":"string"}},"required":["city"],""" +
            """"type":"object"}}]}]"""

    /** "builds config with preserved local JSON Schema references": the `$ref` and its `$defs` survive. */
    const val PRESERVED_REFERENCE_TOOLS: String =
        """[{"functionDeclarations":[{"name":"formatDate","description":"Format a date",""" +
            """"parametersJsonSchema":{"type":"object","properties":{"locale":{"${'$'}ref":"#/${'$'}defs/Locale"}},""" +
            """"required":["locale"],"${'$'}defs":{"Locale":{"type":"string","enum":["de","en"]}}}}]}]"""

    // ---- Gemini 3.8 Live server frames --------------------------------------------------------------

    const val INTERACTION_STATUS: String = """{"serverContent":{"interactionStatus":"IN_PROGRESS"}}"""

    const val TURN_COMPLETE_IDLE: String = """{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}"""

    const val WAITING_FOR_INPUT: String = """{"serverContent":{"waitingForInput":true}}"""

    const val TRANSLATION_ONLY: String =
        """{"model":"models/gemini-3.5-live-translate-preview","generationConfig":{"responseModalities":["AUDIO"],""" +
            """"translationConfig":{"targetLanguageCode":"es","echoTargetLanguage":true}}}"""
}
