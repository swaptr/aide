package com.sabreware.aide.platform.android.surface.ime.page

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.sabreware.aide.core.common.di.MAIN
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyStyle
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyboardAppearance
import com.sabreware.aide.platform.android.text.SensitiveFieldPolicy
import com.sabreware.aide.platform.android.surface.ime.R
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.CustomInstructionsEditor
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.KeyFactory
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.KeyboardCallbacks
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.KeyboardRowBuilder
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.KeyboardSpec
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.Layer
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.Shift
import com.sabreware.aide.platform.android.surface.ime.page.keyboard.SubPage
import com.sabreware.aide.platform.android.surface.ime.theme.Sizes
import com.sabreware.aide.platform.android.surface.ime.theme.Spacing
import com.sabreware.aide.platform.android.surface.ime.theme.dp
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController
import com.sabreware.aide.platform.android.surface.ime.widget.KeyPopup
import com.sabreware.aide.platform.android.surface.ime.widget.KeyboardTouchHost
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@SuppressLint("SetTextI18n")
class KeyboardPage(
    context: Context,
    private val controller: TransformController,
) : IMEPage {

    private val ctx: Context = ContextThemeWrapper(context, R.style.Theme_Aide_Ime)

    private val root: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.aide_surface))
        val v = ctx.dp(Spacing.md)
        val h = ctx.dp(Spacing.sm)
        setPadding(h, v, h, v)
    }

    private var host: PageHost? = null
    private var editorInfo: EditorInfo? = null
    private var lastActionSig: Int = -1

    private var shift: Shift = Shift.OFF
    private var lastShiftTap: Long = 0L
    private var layer: Layer = Layer.LETTERS
    private var subPage: SubPage = SubPage.MAIN

    private val popup = KeyPopup(ctx)

    private val callbacks: KeyboardCallbacks = object : KeyboardCallbacks {
        override fun onCommit(text: String) = commit(text)
        override fun onBackspace() = backspace()
        override fun onBackspaceWord() = backspaceWord()
        override fun onEnter() = enter()
        override fun onShiftTap() {
            shift = nextShiftState()
            rowBuilder.applyShift()
        }
        override fun onLayerChange(next: Layer) {
            layer = next
            rebuild()
        }
        override fun shift() = shift
        override fun layer() = layer
        override fun editorInfo() = editorInfo
    }

    // Live look/behaviour; re-applied on change via the onAttach collector.
    private var appearance: KeyboardAppearance = controller.keyboardAppearance.value

    private var factory: KeyFactory = buildFactory()
    private var rowBuilder: KeyboardRowBuilder = buildRowBuilder()
    private val editor = CustomInstructionsEditor(ctx)

    private fun buildFactory(): KeyFactory = KeyFactory(
        ctx, popup, callbacks,
        borderless = appearance.keyStyle == KeyStyle.Borderless,
        keyHeightDp = Sizes.keyHeight * appearance.height.scale,
        keyMarginDp = Spacing.keyMargin,
    )

    private fun buildRowBuilder(): KeyboardRowBuilder =
        KeyboardRowBuilder(ctx, factory, callbacks, numberRow = appearance.numberRow)

    private val pageScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var requestEditorJob: Job? = null
    private var appearanceJob: Job? = null

    override val view: View get() = root

    override var desiredHeightDp: Int = mainBodyHeightDp()
        private set

    // keyboardRows (210) is the tuned 4-row height; the number row adds a 5th on LETTERS only. Scale
    // by row count + height preset.
    private fun mainBodyHeightDp(): Int {
        val rows = if (appearance.numberRow && layer == Layer.LETTERS) 5 else 4
        return (Sizes.keyboardRows * rows / 4f * appearance.height.scale).roundToInt()
    }

    private fun recomputeHeight() {
        desiredHeightDp = when (subPage) {
            SubPage.MAIN -> mainBodyHeightDp()
            SubPage.CUSTOM_INSTRUCTIONS -> mainBodyHeightDp() +
                Sizes.subpageHeader.toInt() + Sizes.editorPane.toInt() + (Spacing.sm * 2).toInt()
        }
    }

    init {
        rebuild()
    }

    override fun onAttach(host: PageHost) {
        this.host = host
        requestEditorJob = pageScope.launch {
            controller.requestCustomEditor.collect {
                if (SensitiveFieldPolicy.isSensitive(editorInfo)) return@collect
                if (subPage == SubPage.CUSTOM_INSTRUCTIONS) return@collect
                go(SubPage.CUSTOM_INSTRUCTIONS)
            }
        }
        // Live re-style; first emission == seeded value (no-op), only real changes rebuild.
        appearanceJob = pageScope.launch {
            controller.keyboardAppearance.collect { app ->
                if (app == appearance) return@collect
                appearance = app
                factory = buildFactory()
                rowBuilder = buildRowBuilder()
                rebuild()
            }
        }
    }

    override fun onDetach() {
        requestEditorJob?.cancel()
        appearanceJob?.cancel()
        // Dismiss any open more-keys popup so its PopupWindow can't outlive the page (leaked token).
        popup.dismiss()
        // Drop any subpage-imposed header hide; the next page's onAttach owns it.
        controller.setHeaderVisible(true)
        host = null
    }

    override fun onDestroy() {
        pageScope.cancel()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        editorInfo = info
        // A field focus rebuilds nothing: only the action (enter) key depends on the field (and
        // labels only when shift was engaged), so both are refreshed in place, and only when changed.
        if (shift == Shift.SHIFT) {
            shift = Shift.OFF
            rowBuilder.applyShift()
        }
        val sig = actionSignature(info)
        if (sig != lastActionSig) {
            lastActionSig = sig
            rowBuilder.applyEditorInfo()
        }
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS && SensitiveFieldPolicy.isSensitive(info)) {
            go(SubPage.MAIN)
            return
        }
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) setHostConsumesInput(true)
        // Reset to letters; rebuild only when we weren't already there.
        if (layer != Layer.LETTERS) {
            layer = Layer.LETTERS
            rebuild()
        }
    }

    // What actionVisual()/actionStyle() key off — skip the action-key refresh when it's unchanged.
    private fun actionSignature(info: EditorInfo?): Int {
        val opts = info?.imeOptions ?: 0
        return opts and (EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        setHostConsumesInput(false)
        editorInfo = null
    }

    private fun setHostConsumesInput(consumes: Boolean) {
        host?.currentInputConnection?.setImeConsumesInput(consumes)
    }

    private fun rebuild() {
        editor.snapshot()
        root.removeAllViews()
        when (subPage) {
            SubPage.MAIN -> root.addView(qwertyArea())
            SubPage.CUSTOM_INSTRUCTIONS -> {
                root.addView(
                    editor.headerView(
                        title = "Custom instruction",
                        onCancel = ::onSubpageCancel,
                        onQueue = ::onSubpageQueue,
                        onApply = ::onSubpageApply,
                    ),
                )
                root.addView(editor.editorView())
                root.addView(qwertyArea())
            }
        }
        recomputeHeight()
        host?.notifyHeightChanged()
    }

    private fun qwertyArea(): View {
        val touchHost = KeyboardTouchHost(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        rowBuilder.cachedRows(layer).forEach { row ->
            (row.parent as? ViewGroup)?.removeView(row)
            touchHost.addView(row)
        }
        return touchHost
    }

    private fun go(page: SubPage) {
        if (subPage == page) return
        val leavingEditor = subPage == SubPage.CUSTOM_INSTRUCTIONS
        val enteringEditor = page == SubPage.CUSTOM_INSTRUCTIONS
        if (enteringEditor) {
            editor.setInitialText("")
        }
        subPage = page
        // Service-level TransformBar would double-stack our subpage header; hide it while we're in
        // the editor. Height is recomputed + broadcast inside rebuild().
        controller.setHeaderVisible(!enteringEditor)
        if (enteringEditor) setHostConsumesInput(true)
        else if (leavingEditor) setHostConsumesInput(false)
        rebuild()
    }

    private fun onSubpageCancel() {
        editor.reset()
        go(SubPage.MAIN)
    }

    private fun onSubpageApply() {
        val text = editor.currentText().trim()
        if (text.isEmpty()) return
        controller.runAdhocInstruction(text, TransformController.AdhocIntent.APPLY)
        editor.reset()
        go(SubPage.MAIN)
    }

    private fun onSubpageQueue() {
        val text = editor.currentText().trim()
        if (text.isEmpty()) return
        controller.runAdhocInstruction(text, TransformController.AdhocIntent.QUEUE)
        editor.reset()
        go(SubPage.MAIN)
    }

    private fun commit(text: String) {
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) {
            editor.insert(text)
        } else {
            host?.currentInputConnection?.commitText(text, 1)
        }
        if (shift == Shift.SHIFT) {
            shift = Shift.OFF
            rowBuilder.applyShift()
        }
    }

    private fun backspace() {
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) {
            editor.backspace()
            return
        }
        val ic = host?.currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun backspaceWord() {
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) {
            editor.backspaceWord()
            return
        }
        val ic = host?.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(KeyboardSpec.WORD_LOOKBACK_CHARS, 0) ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val toDelete = before.length - i
        if (toDelete > 0) ic.deleteSurroundingText(toDelete, 0)
    }

    private fun enter() {
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) {
            editor.insert("\n")
            return
        }
        val ic = host?.currentInputConnection ?: return
        val info = editorInfo
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            commit("\n")
        }
    }

    private fun nextShiftState(): Shift {
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTap < KeyboardSpec.CAPS_DOUBLE_TAP_MS
        lastShiftTap = now
        return when (shift) {
            Shift.OFF -> Shift.SHIFT
            Shift.SHIFT -> if (doubleTap) Shift.CAPS_LOCK else Shift.OFF
            Shift.CAPS_LOCK -> Shift.OFF
        }
    }
}
