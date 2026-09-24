package com.sabreware.aide.platform.android.intent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

private const val TAG = "IntentBroker"

// Theme.NoDisplay relay so IME tool calls can launch system editors on
// Android 14+ without tripping BackgroundActivityStartException.
class IntentBrokerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = readPayload()
        if (payload != null) {
            runCatching { startActivity(payload) }
                .onFailure { Log.w(TAG, "broker payload startActivity failed for ${payload.action}", it) }
        } else {
            Log.w(TAG, "broker invoked without EXTRA_PAYLOAD_INTENT — dropping")
        }
        finish()
    }

    private fun readPayload(): Intent? =
        intent.getParcelableExtra(EXTRA_PAYLOAD_INTENT, Intent::class.java)

    companion object {
        const val EXTRA_PAYLOAD_INTENT = "com.sabreware.aide.platform.android.intent.PAYLOAD_INTENT"
    }
}
