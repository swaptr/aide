package com.sabreware.aide.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.designsystem.LocalAppBanner
import com.sabreware.aide.core.designsystem.rememberAppBannerState
import com.sabreware.aide.core.domain.navigation.DeepLinkDest
import com.sabreware.aide.app.platform.androidAffordances
import com.sabreware.aide.ui.app.AppAppearance
import com.sabreware.aide.ui.app.AppShell
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

// Consume `navigate_to` once (gated on savedInstanceState==null and stripped from
// Intent) — else config changes replay the deep-link.
class MainActivity : ComponentActivity() {

    private val prefStore: PreferenceStore by inject()
    private val modelSelection: ModelSelectionStore by inject()

    private val pendingDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            consumeIntentExtras(intent)
        }
        holdFirstFrameForModelChoice()
        setContent {
            AppAppearance(prefStore) {
                val deepLink by pendingDeepLink.collectAsState()
                // App-wide banner controller (Material "banner": app updates / announcements). Provided once
                // here so any screen reaches the same instance via LocalAppBanner and AppScaffold hosts it.
                val appBanner = rememberAppBannerState()
                CompositionLocalProvider(
                    LocalAppBanner provides appBanner,
                    // The host pickers/launchers the shared UI resolves. Android has all seven.
                    LocalPlatformAffordances provides androidAffordances,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppShell(
                            deepLinkDestination = deepLink,
                            onDeepLinkConsumed = { pendingDeepLink.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentExtras(intent)
    }

    /**
     * Holds the first draw — the system splash stays up — until the user's model choice document has been
     * read, so the chat header opens on the model's name (or a settled "No model") instead of a shimmer, and
     * until the prefs file has, so the first frame is drawn in the user's theme and chat font rather than
     * the defaults and then repainted.
     *
     * The read started in Application.onCreate and is one small local file, so this is normally a no-op;
     * the cap bounds it on a slow disk, after which the header falls back to its skeleton. This is the
     * platform's own keep-the-splash idiom (a pre-draw listener) — no library at minSdk 35.
     */
    private fun holdFirstFrameForModelChoice() {
        val deadline = SystemClock.uptimeMillis() + FIRST_FRAME_HOLD_MAX_MS
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val ready = (modelSelection.state.value is DocState.Ready && prefStore.isLoaded) ||
                        SystemClock.uptimeMillis() >= deadline
                    if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
                    return ready
                }
            },
        )
    }

    private fun consumeIntentExtras(intent: Intent?) {
        if (intent == null) return
        val dest = intent.getStringExtra(EXTRA_NAVIGATE_TO) ?: return
        intent.removeExtra(EXTRA_NAVIGATE_TO)
        pendingDeepLink.value = dest
    }

    // Deep-link keys — single source of truth in commonMain (navigation.DeepLinkDest); aliased here so
    // the Android entry points (IME / assistant) keep referencing MainActivity.DEST_*.
    companion object {
        const val EXTRA_NAVIGATE_TO = com.sabreware.aide.core.domain.navigation.DeepLinkDest.EXTRA_NAVIGATE_TO
        const val DEST_TASK_EDIT_NEW = com.sabreware.aide.core.domain.navigation.DeepLinkDest.DEST_TASK_EDIT_NEW
        const val DEST_TASKS = com.sabreware.aide.core.domain.navigation.DeepLinkDest.DEST_TASKS
        const val DEST_CUSTOM_INSTRUCTION = com.sabreware.aide.core.domain.navigation.DeepLinkDest.DEST_CUSTOM_INSTRUCTION
        const val DEST_MODELS = com.sabreware.aide.core.domain.navigation.DeepLinkDest.DEST_MODELS

        /** Upper bound on [holdFirstFrameForModelChoice]; past it the header shows its skeleton instead. */
        private const val FIRST_FRAME_HOLD_MAX_MS = 150L
    }
}
