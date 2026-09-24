package com.sabreware.aide.core.domain.download

// Common shape so DownloadEngine doesn't know it's streaming LLM weights vs speech.
// Neutral home (domain/download) so model and speech specs share it without coupling
// data/catalog to data/speech.
interface DownloadableSpec {
    val id: String
    val displayName: String
    val downloadUrl: String?
    val fileName: String?
    val sizeBytes: Long?
}
