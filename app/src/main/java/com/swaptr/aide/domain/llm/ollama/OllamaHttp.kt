package com.swaptr.aide.domain.llm.ollama

import javax.inject.Qualifier

// Bound to an OkHttp instance with readTimeout=0 / callTimeout=0 so streaming NDJSON
// generations aren't killed mid-flow; connect timeout stays short.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OllamaHttp
