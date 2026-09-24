package com.sabreware.aide.app.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sabreware.aide.app.R
import com.sabreware.aide.core.common.di.APPLICATION_SCOPE
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.startup.DeferredBootstraps
import com.sabreware.aide.ui.app.AppAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.sabreware.aide.core.domain.navigation.DeepLinkDest
import com.sabreware.aide.platform.android.launchAppAt
import com.sabreware.aide.app.assistant.ui.AssistantOverlay
import kotlin.math.max
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// VoiceInteractionSession is a plain Object: Compose's three view-tree owners are
// hand-rolled via SessionStateOwner and driven from framework callbacks.
private const val TAG = "AideAssistantSession"

class AideAssistantSession(context: Context) : VoiceInteractionSession(context), KoinComponent {

    init {
        // MUST run before onCreate(): the framework reads the theme in doOnCreate to
        // build the session window. Replaces the DeviceDefault theme whose
        // windowContentOverlay/opaque background drew the white frame around the overlay.
        setTheme(R.style.Theme_Aide_AssistantSession)
    }

    private val stateOwner = SessionStateOwner()
    private val bottomInsetPx = mutableIntStateOf(0)
    // Increments on each onShow. The overlay keys its enter animation on this so every
    // show recreates the slide from hidden (epoch 0 = onPrepareShow warm-up, no animation).
    private val showEpoch = mutableIntStateOf(0)
    private val voiceController: AssistantVoiceController by inject()
    private val prefs: PreferenceStore by inject()
    private val deferredBootstraps: DeferredBootstraps by inject()
    private val applicationScope: CoroutineScope by inject(APPLICATION_SCOPE)

    // Lets the controller hide/restore this overlay and launch the permission trampoline as an
    // assistant activity during the mic round-trip (see AssistantWindowHost). startAssistantActivity
    // requires mToken, so it's only valid between onCreate and onDestroy — the window's lifetime.
    private val windowHost = object : AssistantWindowHost {
        override fun setUiEnabled(enabled: Boolean) {
            this@AideAssistantSession.setUiEnabled(enabled)
        }

        override fun startAssistantActivity(intent: Intent) {
            this@AideAssistantSession.startAssistantActivity(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        stateOwner.onCreate()
        window?.window?.let(::configureWindow)
        voiceController.windowHost = windowHost
    }

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(stateOwner)
            setViewTreeViewModelStoreOwner(stateOwner)
            setViewTreeSavedStateRegistryOwner(stateOwner)
            // Dispose the composition when our hand-rolled lifecycle is DESTROYED (onDestroy).
            // Composition still persists across hide/show (the recomposer pauses at CREATED).
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                // Collected WITHOUT reading `.value` here — the State is passed down and read
                // only in the leaf composables, so high-frequency Listening emissions don't
                // recompose the whole overlay. needsModelSetup is low-frequency, read directly.
                val voiceState = voiceController.state.collectAsState()
                val needsModelSetup by voiceController.needsModelSetup.collectAsState()
                val epoch = showEpoch.intValue
                // Stable across the (now rare) session recompositions.
                val onTapMic = remember { { voiceController.onTapMic() } }
                val onOpenModels = remember { { openModelsScreen() } }
                AppAppearance(prefs) {
                    AssistantOverlay(
                        voiceState = voiceState,
                        onDismiss = { hide() },
                        showEpoch = epoch,
                        bottomInsetPx = bottomInsetPx.intValue,
                        onTapMic = onTapMic,
                        needsModelSetup = needsModelSetup,
                        onOpenModels = onOpenModels,
                    )
                }
            }
        }
        attachInsetsListener(composeView)
        return composeView
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Drive lifecycle to STARTED before the framework paints; without this
        // first composition races onShow and the overlay appears blank on cold launch.
        stateOwner.onStart()
        voiceController.warmUpStt()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        stateOwner.onResume()
        voiceController.startIfIdle()
        // Bumping the epoch recreates the overlay's enter animation from hidden.
        showEpoch.intValue += 1
        // The assistant can be the first surface a process shows, and without the catalog refresh a configured
        // cloud provider with no cached listing never settles — the setup chip never draws and a voice turn
        // times out on "no model". After the show, like every other surface; idempotent across surfaces.
        applicationScope.launch { deferredBootstraps.startAll() }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        Log.d(
            TAG,
            "onHandleAssist: index=${state.index} count=${state.count} " +
                "hasAssistData=${state.assistData != null} " +
                "hasStructure=${state.assistStructure != null} " +
                "hasContent=${state.assistContent != null}",
        )
    }

    override fun onHide() {
        Log.i(TAG, "onHide")
        voiceController.stop()
        stateOwner.onPause()
        super.onHide()
    }

    override fun onCloseSystemDialogs() {
        Log.i(TAG, "onCloseSystemDialogs — hiding")
        hide()
    }

    override fun onLockscreenShown() {
        Log.i(TAG, "onLockscreenShown — hiding")
        super.onLockscreenShown()
        // Defensive dismiss — supportsLaunchFromKeyguard=false in the manifest,
        // but the keyguard can still appear while we're up.
        hide()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        // Guard against clobbering a newer session's host (sessions are sequential, but cheap).
        if (voiceController.windowHost === windowHost) voiceController.windowHost = null
        stateOwner.onDestroy()
        super.onDestroy()
    }

    private fun openModelsScreen() {
        hide()
        // The activity lives in :app, above this surface — so the app is opened through its launcher intent
        // with the same deep-link destination the shell already routes.
        runCatching { context.launchAppAt(DeepLinkDest.DEST_MODELS) }
    }

    // Edge-to-edge — without this, the SoftInputWindow host leaves a gray nav-bar
    // strip below the Compose content.
    private fun configureWindow(w: Window) {
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        // System bars are transparent by default under edge-to-edge (minSdk 35); the deprecated
        // statusBarColor/navigationBarColor setters are no-ops here.
        w.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        w.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    // Compose-docs `safeGestures` set for bottom-anchored overlay UI:
    // navigationBars ∪ mandatorySystemGestures ∪ systemGestures ∪ ime.
    private fun attachInsetsListener(target: View) {
        ViewCompat.setOnApplyWindowInsetsListener(target) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val mandatoryGestures = insets
                .getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            val systemGestures = insets
                .getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            val safeBottom = max(max(nav, mandatoryGestures), systemGestures)
            bottomInsetPx.intValue = max(ime, safeBottom)
            insets
        }
    }

    // Drives CREATED → STARTED → RESUMED → STARTED → CREATED → DESTROYED.
    // Skipping STARTED on the way down throws on stricter androidx LifecycleRegistry versions.
    private class SessionStateOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun onCreate() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun onStart() {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        fun onResume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun onPause() {
            // RESUMED → STARTED → CREATED preserves the LifecycleRegistry invariant.
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun onDestroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
