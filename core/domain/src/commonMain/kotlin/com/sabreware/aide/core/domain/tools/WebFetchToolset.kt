package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebFetcher
import com.sabreware.aide.core.domain.tools.results.WebFetchResult
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AideTools"
private const val FETCH_CAP = 10_000
private val WEB_FETCH_ERROR_CODES = setOf(
    "FETCH_FAILED", "INVALID_URL", "HTTP_4XX", "HTTP_5XX", "NETWORK", "TIMEOUT",
)

class WebFetchToolset(
    private val client: WebFetcher,
) {

    fun asAideTool(): AideTool = AideTool.Function(
        name = "WebFetch",
        readOnly = true,
        description = "Fetch a webpage and return its main text content, capped at " +
            "$FETCH_CAP chars. Use this when the user gives you a URL, or when a " +
            "WebSearch snippet is too thin to answer from.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "url" to stringProp("Full URL beginning with http:// or https://"),
            ),
        ),
        handler = { args ->
            val url = args["url"]?.jsonPrimitive?.content.orEmpty()
            AideLog.i(TAG, "WebFetch called: $url")
            when (val outcome = runCatching { client.fetchOutcome(url, FETCH_CAP) }
                .getOrElse {
                    AideLog.w(TAG, "WebFetch threw", it)
                    return@Function WebFetchResult.Err(
                        "FETCH_FAILED",
                        it.message ?: "fetch failed",
                        url,
                    ).toEnvelope()
                }) {
                is FetchOutcome.Text -> WebFetchResult.Page(
                    url = url,
                    text = outcome.text,
                    truncated = outcome.truncated,
                ).toEnvelope()
                is FetchOutcome.Empty -> WebFetchResult.Empty(
                    url = url,
                    note = outcome.note,
                ).toEnvelope()
                is FetchOutcome.Error -> WebFetchResult.Err(
                    code = outcome.code,
                    message = outcome.message,
                    url = url,
                ).toEnvelope()
            }
        },
        surfaces = BOTH_SURFACES,
        errorCodes = WEB_FETCH_ERROR_CODES,
    )
}
