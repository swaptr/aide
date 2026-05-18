package com.swaptr.aide.ime.page

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.swaptr.aide.R
import com.swaptr.aide.ime.page.keyboard.CustomInstructionsEditor
import com.swaptr.aide.ime.page.keyboard.KeyFactory
import com.swaptr.aide.ime.page.keyboard.KeyboardCallbacks
import com.swaptr.aide.ime.page.keyboard.KeyboardRowBuilder
import com.swaptr.aide.ime.page.keyboard.KeyboardSpec
import com.swaptr.aide.ime.page.keyboard.Layer
import com.swaptr.aide.ime.page.keyboard.Shift
import com.swaptr.aide.ime.page.keyboard.SubPage
import com.swaptr.aide.ime.text.SensitiveFieldPolicy
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.Spacing
import com.swaptr.aide.ime.theme.dp
import com.swaptr.aide.ime.transform.TransformController
import com.swaptr.aide.ime.widget.KeyPopup
import com.swaptr.aide.ime.widget.KeyboardTouchHost
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

    private var shift: Shift = Shift.OFF
    private var lastShiftTap: Long = 0L
    private var layer: Layer = Layer.LETTERS
    private var subPage: SubPage = SubPage.MAIN

    private val popup = KeyPopup(ctx)

    private val callbacks: KeyboardCallbacks = object : KeyboardCallbacks {
        override fun onCommit(text: String) = commit(text)
        override fun onReplaceLastChar(text: String) = replaceLastChar(text)
        override fun onDeleteLastChar() = deleteLastChar()
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

    private val factory = KeyFactory(ctx, popup, callbacks)
    private val rowBuilder = KeyboardRowBuilder(ctx, factory, callbacks)
    private val editor = CustomInstructionsEditor(ctx)

    private val pageScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var requestEditorJob: Job? = null

    override val view: View get() = root

    override var desiredHeightDp: Int = Sizes.keyboardRows.toInt()
        private set

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
    }

    override fun onDetach() {
        requestEditorJob?.cancel()
        // Drop any subpage-imposed header hide; the next page's onAttach owns it.
        controller.setHeaderVisible(true)
        host = null
    }

    override fun onDestroy() {
        pageScope.cancel()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        editorInfo = info
        if (shift == Shift.SHIFT) shift = Shift.OFF
        layer = Layer.LETTERS
        // EditorInfo changed: action-key glyph + alts may differ.
        rowBuilder.invalidate()
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS && SensitiveFieldPolicy.isSensitive(info)) {
            go(SubPage.MAIN)
            return
        }
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) setHostConsumesInput(true)
        rebuild()
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
        desiredHeightDp = if (enteringEditor) {
            Sizes.keyboardRows.toInt() +
                Sizes.subpageHeader.toInt() +
                Sizes.editorPane.toInt() +
                (Spacing.sm * 2).toInt()
        } else {
            Sizes.keyboardRows.toInt()
        }
        // Service-level TransformBar would double-stack our subpage header;
        // hide it while we're in the editor.
        controller.setHeaderVisible(!enteringEditor)
        if (enteringEditor) setHostConsumesInput(true)
        else if (leavingEditor) setHostConsumesInput(false)
        rebuild()
        host?.notifyHeightChanged()
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

    private fun replaceLastChar(text: String) {
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) {
            editor.backspace()
            editor.insert(text)
            return
        }
        val ic = host?.currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun deleteLastChar() {
        if (subPage == SubPage.CUSTOM_INSTRUCTIONS) {
            editor.backspace()
            return
        }
        host?.currentInputConnection?.deleteSurroundingText(1, 0)
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
