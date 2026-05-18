package com.swaptr.aide.intent

import android.app.Activity
import android.content.Intent
import android.os.Build
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
        // overrideActivityTransition is API 34+; older releases use the deprecated sibling.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun readPayload(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_PAYLOAD_INTENT, Intent::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_PAYLOAD_INTENT)
    }

    companion object {
        const val EXTRA_PAYLOAD_INTENT = "com.swaptr.aide.intent.PAYLOAD_INTENT"
    }
}
