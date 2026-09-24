package com.sabreware.aide.app.data.connector.oauth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.sabreware.aide.core.domain.connector.oauth.RedirectResult
import com.sabreware.aide.data.connector.oauth.PendingOAuthFlow

/**
 * Catches the OAuth redirect for the **custom-scheme** strategy
 * (`com.sabreware.aide://oauth-callback?code=…&state=…`). `singleTask` + `NoDisplay`: parses the URI, hands
 * it to the in-flight [PendingOAuthFlow], and finishes immediately — no UI. The **loopback** strategy
 * never reaches here (it binds a local `ServerSocket`).
 */
class OAuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deliver(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliver(intent)
    }

    private fun deliver(intent: Intent?) {
        val uri = intent?.data
        val result = if (uri == null) {
            RedirectResult(null, null, PendingOAuthFlow.CANCELLED)
        } else {
            RedirectResult(uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error"))
        }
        PendingOAuthFlow.deliverFromCallback(result)
        finish()
    }
}
