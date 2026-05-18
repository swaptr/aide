package com.swaptr.aide.ime

import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import androidx.core.view.WindowInsetsControllerCompat
import com.swaptr.aide.data.prefs.ImePage
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.domain.speech.dictation.DictationSurfaceId
import com.swaptr.aide.domain.speech.dictation.sink.InputConnectionSink
import com.swaptr.aide.ime.host.ImeHeightController
import com.swaptr.aide.ime.host.ImeWindowChrome
import com.swaptr.aide.ime.host.PageFactory
import com.swaptr.aide.ime.host.PopupAnimator
import com.swaptr.aide.ime.page.PageHost
import com.swaptr.aide.ime.text.InputConnectionTextSource
import com.swaptr.aide.ime.text.SensitiveFieldPolicy
import com.swaptr.aide.ime.text.TextContextRepository
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.transform.TransformController
import com.swaptr.aide.ime.widget.ChipDetailSheet
import com.swaptr.aide.ime.widget.QueueSheet
import com.swaptr.aide.ime.widget.ResponsePopupView
import com.swaptr.aide.ime.widget.TransformBar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AideInputMethodService : InputMethodService() {

    @Inject lateinit var textContext: TextContextRepository
    @Inject lateinit var userPrefs: UserPreferencesRepository
    @Inject lateinit var dictationController: com.swaptr.aide.domain.speech.dictation.DictationController
    @Inject lateinit var speechEngine: com.swaptr.aide.data.speech.SpeechEngineRepository
    @Inject lateinit var transformController: TransformController
    @Inject lateinit var engineRepo: com.swaptr.aide.data.model.LlmEngineRepository

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
    private var popupObserver: Job? = null
    private var headerObserver: Job? = null

    override fun onCreateInputView(): View {
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
        queue.start(serviceScope)
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
            dictationSink = InputConnectionSink { currentInputConnection },
            editorInfoProvider = { currentEditorInfo },
            onOpenModels = { openModelsScreen() },
        )
        dictationController.registerSurface(DictationSurfaceId.IME_KEYBOARD_HOST_FIELD)
        bar.start(serviceScope)
        transformBar = bar

        val popup = ResponsePopupView(
            rawContext = this,
            controller = transformController,
            connectionProvider = { currentInputConnection },
        ).apply {
            visibility = View.GONE
            alpha = 0f
        }
        popup.start(serviceScope)
        val popupAnim = PopupAnimator(this, popup, heightCtrl)
        popupAnimator = popupAnim

        popupObserver = serviceScope.launch {
            transformController.preview.collect { preview ->
                popupAnim.setVisible(preview != null)
            }
        }

        headerObserver = serviceScope.launch {
            transformController.headerVisible.collect { visible ->
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
        transformController.setFieldSensitive(SensitiveFieldPolicy.isSensitive(info))
        transformBar?.refreshMicVisibility()
        // Warm STT + Silero VAD so first mic tap skips JNI init latency; idempotent.
        serviceScope.launch { runCatching { speechEngine.warmUpStt() } }
        host?.forwardStartInputView(info, restarting)
    }

    override fun onWindowShown() {
        super.onWindowShown()
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
        if (finishingInput) textContext.unbind()
        host?.forwardFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        textContext.unbind()
        host?.forwardFinishInput()
    }

    override fun onDestroy() {
        textContext.unbind()
        popupObserver?.cancel(); popupObserver = null
        headerObserver?.cancel(); headerObserver = null
        popupAnimator?.stop()
        transformBar?.stop()
        queueSheet?.stop()
        runCatching {
            dictationController.unregisterSurface(DictationSurfaceId.IME_KEYBOARD_HOST_FIELD)
        }
        transformBar = null
        chipSheet = null
        queueSheet = null
        popupAnimator = null
        heightCtrl.detach()
        runCatching { serviceScope.cancel() }
        runCatching { engineRepo.unload() }
        host?.forwardDestroy()
        host = null
        super.onDestroy()
    }

    private fun openMainAppTaskEditor() {
        runCatching {
            val intent = Intent(this, com.swaptr.aide.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(
                    com.swaptr.aide.MainActivity.EXTRA_NAVIGATE_TO,
                    com.swaptr.aide.MainActivity.DEST_TASK_EDIT_NEW,
                )
            }
            startActivity(intent)
        }
    }

    private fun openModelsScreen() {
        runCatching {
            val intent = Intent(this, com.swaptr.aide.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(
                    com.swaptr.aide.MainActivity.EXTRA_NAVIGATE_TO,
                    com.swaptr.aide.MainActivity.DEST_MODELS,
                )
            }
            startActivity(intent)
        }
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
