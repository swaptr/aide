package com.sabreware.aide.platform.android.surface.ime

import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import androidx.core.view.WindowInsetsControllerCompat
import com.sabreware.aide.core.common.speech.DictationController
import com.sabreware.aide.core.common.speech.DictationSurfaceId
import com.sabreware.aide.platform.android.surface.ime.prefs.ImePage
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.navigation.DeepLinkDest
import com.sabreware.aide.platform.android.launchAppAt
import com.sabreware.aide.platform.android.text.SensitiveFieldPolicy
import com.sabreware.aide.platform.android.surface.ime.host.ImeHeightController
import com.sabreware.aide.platform.android.surface.ime.host.ImeWindowChrome
import com.sabreware.aide.platform.android.surface.ime.host.PageFactory
import com.sabreware.aide.platform.android.surface.ime.host.PopupAnimator
import com.sabreware.aide.platform.android.surface.ime.page.PageHost
import com.sabreware.aide.platform.android.surface.ime.speech.InputConnectionSink
import com.sabreware.aide.platform.android.surface.ime.text.InputConnectionTextSource
import com.sabreware.aide.platform.android.surface.ime.text.TextContextRepository
import com.sabreware.aide.platform.android.surface.ime.theme.Sizes
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController
import com.sabreware.aide.platform.android.surface.ime.widget.ChipDetailSheet
import com.sabreware.aide.platform.android.surface.ime.widget.QueueSheet
import com.sabreware.aide.platform.android.surface.ime.widget.ResponsePopupView
import com.sabreware.aide.platform.android.surface.ime.widget.TransformBar
import com.sabreware.aide.core.common.prefs.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.sabreware.aide.core.common.di.APPLICATION_SCOPE
import com.sabreware.aide.core.common.startup.DeferredBootstraps
import org.koin.android.ext.android.inject

class AideInputMethodService : InputMethodService() {

    private val textContext: TextContextRepository by inject()
    private val dictationController: com.sabreware.aide.core.common.speech.DictationController by inject()
    private val speechEngine: com.sabreware.aide.core.domain.speech.SpeechEngineRepository by inject()
    private val transformController: TransformController by inject()
    private val deferredBootstraps: DeferredBootstraps by inject()
    private val prefs: PreferenceStore by inject()
    private val applicationScope: CoroutineScope by inject(APPLICATION_SCOPE)

    private val heightCtrl: ImeHeightController by lazy { ImeHeightController(this) }
    private val pageFactory: PageFactory by lazy {
        PageFactory(this, transformController)
    }

    private var host: PageHost? = null
    private var popupAnimator: PopupAnimator? = null
    private var transformBar: TransformBar? = null
    private var chipSheet: ChipDetailSheet? = null
    private var queueSheet: QueueSheet? = null
    private var currentEditorInfo: EditorInfo? = null

    private val serviceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Scope for everything [onCreateInputView] builds, cancelled by [teardownInputView].
     *
     * Distinct from [serviceScope] on purpose: the input view is rebuilt on every configuration change,
     * while the service lives on. A collector started for a view and parented to the service scope keeps
     * the detached View — and its Context — alive for the rest of the process.
     */
    private var viewScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        // The surface exists for as long as the service does; the VIEW is what comes and goes.
        dictationController.registerSurface(DictationSurfaceId.IME_KEYBOARD_HOST_FIELD)
    }

    override fun onCreateInputView(): View {
        // Called again on every rotation / dark-mode / font-size change. Everything below is about to be
        // replaced, so the previous generation has to be stopped first or it leaks with its collectors.
        teardownInputView()
        val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.viewScope = viewScope

        val host = PageHost(
            context = this,
            connectionProvider = { currentInputConnection },
            activityStarter = { intent ->
                runCatching {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            },
            heightChangeListener = { heightDp -> heightCtrl.setPageHeight(heightDp) },
        )
        this.host = host

        // The page is built from the prefs snapshot (height, key style). Its read started with the process;
        // on the rare cold start where it has not landed, wait for it — bounded, like the app's splash hold —
        // rather than draw the default height and resize under the user's thumb.
        if (!prefs.isLoaded) runBlocking { withTimeoutOrNull(FIRST_DRAW_PREFS_WAIT_MS) { prefs.awaitLoaded() } }
        val initialPage = pageFactory.build(ImePage.KEYBOARD)
        heightCtrl.setPageHeight(initialPage.desiredHeightDp)
        heightCtrl.setBarHeight(Sizes.barHeight.toInt())
        host.setPage(initialPage)

        val sheet = ChipDetailSheet(this) { task ->
            transformController.onChipTapped(task.id, task.name)
        }
        sheet.setContent(host.view)
        chipSheet = sheet

        val queue = QueueSheet(this, transformController)
        queue.setContent(sheet.root)
        queue.start(viewScope)
        queueSheet = queue

        val bar = TransformBar(
            rawContext = this,
            controller = transformController,
            onTaskTap = { task -> transformController.onChipTapped(task.id, task.name) },
            onGroupTap = { group -> sheet.showGroup(group) },
            onAddNew = { openMainAppTaskEditor() },
            onAddInstruction = { transformController.requestCustomInstructionsEditor() },
            onMagicTap = { transformController.runRawPrompt() },
            onQueueTap = { queue.toggle() },
            onTaskLong = { task -> sheet.showTaskDescription(task) },
            onGroupLong = { group -> sheet.showGroupDescription(group) },
            dictationController = dictationController,
            dictationSurface = DictationSurfaceId.IME_KEYBOARD_HOST_FIELD,
            dictationSink = InputConnectionSink(
                connection = { currentInputConnection },
                editorInfo = { currentEditorInfo },
            ),
            editorInfoProvider = { currentEditorInfo },
            onOpenModels = { openModelsScreen() },
        )
        bar.start(viewScope)
        transformBar = bar

        val popup = ResponsePopupView(
            rawContext = this,
            controller = transformController,
            connectionProvider = { currentInputConnection },
        ).apply {
            visibility = View.GONE
            alpha = 0f
        }
        popup.start(viewScope)
        val popupAnim = PopupAnimator(this, popup, heightCtrl)
        popupAnimator = popupAnim

        viewScope.launch {
            transformController.preview.collect { preview ->
                popupAnim.setVisible(preview != null)
            }
        }

        // Bar shows only when the subpage header isn't up AND AI isn't disabled (reclaims its height).
        viewScope.launch {
            combine(
                transformController.headerVisible,
                transformController.keyboardAppearance,
            ) { headerVisible, appearance -> headerVisible && appearance.aiEnabled }
                .distinctUntilChanged()
                .collect { visible ->
                    bar.visibility = if (visible) View.VISIBLE else View.GONE
                    heightCtrl.setBarHeight(if (visible) Sizes.barHeight.toInt() else 0)
                }
        }

        return ImeWindowChrome(
            ctx = this,
            popup = popup,
            bar = bar,
            pageSlot = queue.root,
            heightCtrl = heightCtrl,
        ).build()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        bindTextSource(attribute)
        transformController.onStartInput()
        host?.forwardStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        if (!restarting) bindTextSource(info)
        textContext.refresh()
        // Re-evaluated per input connection, not once at mic-tap time: focus moving from an ordinary field
        // to a password field has to end the dictation that was already running, not merely hide the button.
        val sensitive = SensitiveFieldPolicy.isSensitive(info)
        transformController.setFieldSensitive(sensitive)
        if (sensitive) stopDictation()
        transformBar?.refreshMicVisibility()
        // Warm STT + Silero VAD so first mic tap skips JNI init latency; idempotent.
        serviceScope.launch { runCatching { speechEngine.warmUpStt() } }
        host?.forwardStartInputView(info, restarting)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The app shell runs these after its first frame, and so does the keyboard: here, not onCreate, so
        // an MCP reconnect or a catalog fetch never competes with the keyboard's first draw. The keyboard can
        // be the only surface this process ever shows — without the catalog refresh a configured provider
        // with no cached listing never settles, which parked the bar on "Loading" for good. Idempotent:
        // whichever surface asks first runs them, once per process. On the APPLICATION scope, not the
        // service's: switching keyboards destroys this service within moments, and a run cancelled halfway
        // would still count as "started", so the rest would never run in this process at all.
        applicationScope.launch { deferredBootstraps.startAll() }
        applyNavigationBarAppearance()
        host?.forwardWindowShown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode flips don't recreate the IME view; re-apply nav-bar tint or icons stay stale.
        applyNavigationBarAppearance()
    }

    // Sets the IME nav-bar icon tint; without this hide-keyboard/globe icons can vanish.
    private fun applyNavigationBarAppearance() {
        val win: Window = window?.window ?: return
        val decor = win.decorView
        val isNight = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(win, decor).isAppearanceLightNavigationBars = !isNight
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText?) {
        super.onUpdateExtractedText(token, text)
        textContext.onExtractedText(token, text)
        host?.forwardUpdateExtractedText(token, text)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        textContext.onSelectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        host?.forwardUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // The keyboard is going away. Dictation used to survive this — the only stop was in onDestroy — so
        // tapping the mic and then switching apps left the microphone open and recording for the life of
        // the IME service, with the transcript going nowhere.
        stopDictation()
        if (finishingInput) textContext.unbind()
        host?.forwardFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopDictation()
        textContext.unbind()
        host?.forwardFinishInput()
    }

    private fun stopDictation() {
        runCatching { dictationController.stop(DictationSurfaceId.IME_KEYBOARD_HOST_FIELD) }
    }

    /**
     * Stops and drops everything [onCreateInputView] built.
     *
     * That method runs again on every configuration change and used to overwrite the host, both sheets, the
     * transform bar, the popup animator and two observers without stopping any of them — so each rotation,
     * dark-mode toggle or font-size change leaked a whole View tree plus its live collectors, each one
     * retaining the detached View and its Context. Cancelling [viewScope] is the guarantee; the individual
     * `stop()` calls remain because they also release non-coroutine resources (animators, listeners).
     */
    private fun teardownInputView() {
        viewScope?.cancel()
        viewScope = null
        popupAnimator?.stop()
        transformBar?.stop()
        queueSheet?.stop()
        popupAnimator = null
        transformBar = null
        queueSheet = null
        chipSheet = null
        host?.forwardDestroy()
        host = null
    }

    override fun onDestroy() {
        textContext.unbind()
        stopDictation()
        runCatching {
            dictationController.unregisterSurface(DictationSurfaceId.IME_KEYBOARD_HOST_FIELD)
        }
        teardownInputView()
        heightCtrl.detach()
        runCatching { serviceScope.cancel() }
        // Engine unload is owned by the per-task ResidencyManager keepAlive now (Phase 5).
        super.onDestroy()
    }

    // The main activity lives in :app, above this surface, so both of these open the app through its
    // launcher intent carrying the deep-link key the shell already routes.
    private fun openMainAppTaskEditor() {
        runCatching { launchAppAt(DeepLinkDest.DEST_TASK_EDIT_NEW) }
    }

    private fun openModelsScreen() {
        runCatching { launchAppAt(DeepLinkDest.DEST_MODELS) }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun bindTextSource(info: EditorInfo?) {
        val source = InputConnectionTextSource(
            connectionProvider = { currentInputConnection },
            editorInfo = info,
            packageName = info?.packageName,
            token = TextContextRepository.EXTRACTED_TEXT_TOKEN,
        )
        textContext.bind(source)
    }
}

/** Cap on holding the keyboard's first build for the prefs read — the same order as the app's splash hold. */
private const val FIRST_DRAW_PREFS_WAIT_MS = 150L
