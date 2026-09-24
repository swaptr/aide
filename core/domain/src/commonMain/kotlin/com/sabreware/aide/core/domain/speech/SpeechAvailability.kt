package com.sabreware.aide.core.domain.speech

data class SpeechAvailability(
    val canStt: Boolean,
    val canTts: Boolean,
    val canVad: Boolean,
    val missingAssets: Set<String> = emptySet(),
)
