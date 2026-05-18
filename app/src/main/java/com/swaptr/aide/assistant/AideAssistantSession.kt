package com.swaptr.aide.assistant

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.swaptr.aide.assistant.ui.AssistantOverlay
import com.swaptr.aide.ui.theme.AideTheme
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.max

// VoiceInteractionSession is a plain Object: Compose's three view-tree owners are
// hand-rolled via SessionStateOwner and driven from framework callbacks.
private const val TAG = "AideAssistantSession"

class AideAssistantSession(context: Context) : VoiceInteractionSession(context) {

    private val stateOwner = SessionStateOwner()
    private val bottomInsetPx = mutableIntStateOf(0)
    private val voiceController: AssistantVoiceController by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AssistantVoiceController.Entry::class.java,
        ).assistantVoiceController()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        stateOwner.onCreate()
        window?.window?.let(::configureWindow)
    }

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(stateOwner)
            setViewTreeViewModelStoreOwner(stateOwner)
            setViewTreeSavedStateRegistryOwner(stateOwner)
            setContent {
                val voiceState by voiceController.state.collectAsState()
                val needsModelSetup by voiceController.needsModelSetup.collectAsState()
                AideTheme {
                    AssistantOverlay(
                        onDismiss = { hide() },
                        bottomInsetPx = bottomInsetPx.intValue,
                        voiceState = voiceState,
                        onTapMic = { voiceController.onTapMic() },
                        needsModelSetup = needsModelSetup,
                        onOpenModels = ::openModelsScreen,
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
        stateOwner.onDestroy()
        super.onDestroy()
    }

    private fun openModelsScreen() {
        hide()
        runCatching {
            val intent = Intent(
                context,
                com.swaptr.aide.MainActivity::class.java,
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(
                    com.swaptr.aide.MainActivity.EXTRA_NAVIGATE_TO,
                    com.swaptr.aide.MainActivity.DEST_MODELS,
                )
            }
            context.startActivity(intent)
        }
    }

    // Edge-to-edge — without this, the SoftInputWindow host leaves a gray nav-bar
    // strip below the Compose content.
    private fun configureWindow(w: Window) {
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        w.statusBarColor = AndroidColor.TRANSPARENT
        w.navigationBarColor = AndroidColor.TRANSPARENT
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
