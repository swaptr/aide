package com.sabreware.aide.core.domain.search

// Error.code is one of HTTP_4XX | HTTP_5XX | NETWORK | INVALID_URL | TIMEOUT so the model can branch.
sealed class FetchOutcome {
    data class Text(val text: String, val truncated: Boolean) : FetchOutcome()
    data class Empty(val note: String) : FetchOutcome()
    data class Error(val code: String, val message: String) : FetchOutcome()
}
