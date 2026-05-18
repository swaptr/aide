package com.swaptr.aide.intent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.swaptr.aide.domain.llm.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IntentDispatcher"

// Two impls: Android 14+ blocks startActivity from IME's service-only surface — IME
// routes through IntentBrokerActivity; CHAT uses its own foreground activity directly.
interface IntentDispatcher {

    fun launch(intent: Intent): LaunchOutcome

    sealed class LaunchOutcome {
        data object Launched : LaunchOutcome()
        data class Failed(val error: Throwable) : LaunchOutcome()
    }
}

@Singleton
class DirectIntentDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) : IntentDispatcher {

    override fun launch(intent: Intent): IntentDispatcher.LaunchOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.fold(
            onSuccess = { IntentDispatcher.LaunchOutcome.Launched },
            onFailure = {
                Log.w(TAG, "direct startActivity failed for ${intent.action}", it)
                IntentDispatcher.LaunchOutcome.Failed(it)
            },
        )
    }
}

// IME has no foreground activity to anchor the launch; the broker provides the
// activity → activity chain Android 14+ requires.
@Singleton
class BrokeredIntentDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) : IntentDispatcher {

    override fun launch(intent: Intent): IntentDispatcher.LaunchOutcome {
        // Broker's `singleInstance` + `taskAffinity=""` keeps it from collapsing
        // into the user's current task.
        val broker = Intent(context, IntentBrokerActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_NO_HISTORY
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(IntentBrokerActivity.EXTRA_PAYLOAD_INTENT, intent)
        }
        return runCatching { context.startActivity(broker) }.fold(
            onSuccess = { IntentDispatcher.LaunchOutcome.Launched },
            onFailure = {
                Log.w(TAG, "broker startActivity failed for ${intent.action}", it)
                IntentDispatcher.LaunchOutcome.Failed(it)
            },
        )
    }
}

@Singleton
class IntentDispatchers @Inject constructor(
    private val direct: DirectIntentDispatcher,
    private val brokered: BrokeredIntentDispatcher,
) {
    fun forSurface(surface: Surface): IntentDispatcher = when (surface) {
        Surface.CHAT -> direct
        Surface.IME -> brokered
        Surface.VOICE -> direct
    }
}
