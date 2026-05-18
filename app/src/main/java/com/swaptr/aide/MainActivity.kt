package com.swaptr.aide

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.swaptr.aide.ui.app.AppShell
import com.swaptr.aide.ui.theme.AideTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

// Consume `navigate_to` once (gated on savedInstanceState==null and stripped from
// Intent) — else config changes replay the deep-link.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val pendingDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            consumeIntentExtras(intent)
        }
        setContent {
            AideTheme {
                val deepLink by pendingDeepLink.collectAsState()
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(
                        deepLinkDestination = deepLink,
                        onDeepLinkConsumed = { pendingDeepLink.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentExtras(intent)
    }

    private fun consumeIntentExtras(intent: Intent?) {
        if (intent == null) return
        val dest = intent.getStringExtra(EXTRA_NAVIGATE_TO) ?: return
        intent.removeExtra(EXTRA_NAVIGATE_TO)
        pendingDeepLink.value = dest
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val DEST_TASK_EDIT_NEW = "task_edit_new"
        const val DEST_TASKS = "tasks"
        const val DEST_CUSTOM_INSTRUCTION = "custom_instruction"
        const val DEST_MODELS = "models"
    }
}
