package com.swaptr.aide.domain.speech

data class SttOptions(
    val locale: String = "en-US",
    val partialResults: Boolean = true,
    val maxDurationMs: Long? = 60_000L,
)
