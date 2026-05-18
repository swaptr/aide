package com.swaptr.aide.data.catalog

// Routes load/generate calls; OLLAMA covers both self-hosted and cloud (same wire
// protocol; differs only in base URL + optional Bearer header).
enum class ProviderId { LOCAL, OLLAMA }
