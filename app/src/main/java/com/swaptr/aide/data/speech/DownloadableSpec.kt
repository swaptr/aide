package com.swaptr.aide.data.speech

// Common shape so DownloadEngine doesn't know it's streaming LLM weights vs speech.
interface DownloadableSpec {
    val id: String
    val displayName: String
    val downloadUrl: String?
    val fileName: String?
    val sizeBytes: Long?
}
