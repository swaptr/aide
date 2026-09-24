package com.sabreware.aide.platform.android.surface.ime.page.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.sabreware.aide.platform.android.surface.ime.R
import com.sabreware.aide.platform.android.surface.ime.theme.Shapes
import com.sabreware.aide.platform.android.surface.ime.theme.Sizes
import com.sabreware.aide.platform.android.surface.ime.theme.Spacing
import com.sabreware.aide.platform.android.surface.ime.theme.Typography
import com.sabreware.aide.platform.android.surface.ime.theme.dp
import com.sabreware.aide.platform.android.surface.ime.widget.AideButton
import com.sabreware.aide.platform.android.surface.ime.widget.AideButtonStyle
import com.sabreware.aide.platform.android.surface.ime.widget.aideButton

// Shift/layer rebuilds destroy this EditText; snapshot()+restore preserve text+cursor.
internal class CustomInstructionsEditor(private val ctx: Context) {

    private var field: AppCompatEditText? = null
    private var text: String = ""
    private var cursor: Int = 0
    private var applyBtn: AideButton? = null
    private var queueBtn: AideButton? = null

    /** Seed the editor with text — call before [editorView] when entering the subpage. */
    fun setInitialText(t: String) {
        text = t
        cursor = t.length
    }

    /** Capture current edits before the parent view tree is torn down. */
    fun snapshot() {
        field?.let {
            text = it.text?.toString() ?: ""
            cursor = it.selectionStart.coerceAtLeast(0)
        }
        field = null
    }

    fun currentText(): String = field?.text?.toString() ?: text

    fun reset() {
        text = ""
        cursor = 0
        field = null
    }

    fun headerView(
        title: String,
        onCancel: () -> Unit,
        onQueue: () -> Unit,
        onApply: () -> Unit,
    ): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(Sizes.subpageHeader),
            )
            val h = ctx.dp(Spacing.sm)
            setPadding(h, 0, h, 0)
        }
        bar.addView(
            TextView(ctx).apply {
                text = title
                setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
                textSize = Typography.subpageTitle
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = ctx.dp(Spacing.lg) }
            },
        )
        bar.addView(
            View(ctx),
            LinearLayout.LayoutParams(0, 0, 1f),
        )
        val cancel = aideButton(
            ctx = ctx,
            shape = Shapes.chrome,
            style = AideButtonStyle.Tonal,
            iconRes = com.sabreware.aide.platform.android.R.drawable.ic_lc_x,
            contentDesc = "Cancel",
            onPress = onCancel,
        )
        val queue = aideButton(
            ctx = ctx,
            shape = Shapes.chrome,
            style = AideButtonStyle.Tonal,
            iconRes = R.drawable.ic_lc_list_plus,
            contentDesc = "Add to queue",
            onPress = onQueue,
        )
        val apply = aideButton(
            ctx = ctx,
            shape = Shapes.chrome,
            style = AideButtonStyle.Accent,
            iconRes = R.drawable.ic_lc_check,
            contentDesc = "Apply",
            onPress = onApply,
        )
        bar.addView(cancel)
        (queue.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = ctx.dp(Spacing.sm)
        bar.addView(queue)
        (apply.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = ctx.dp(Spacing.sm)
        bar.addView(apply)
        applyBtn = apply
        queueBtn = queue
        refreshActionEnablement()
        return bar
    }

    private fun refreshActionEnablement() {
        val hasText = currentText().isNotBlank()
        applyBtn?.let { it.isEnabled = hasText; it.alpha = if (hasText) 1f else 0.4f }
        queueBtn?.let { it.isEnabled = hasText; it.alpha = if (hasText) 1f else 0.4f }
    }

    fun editorView(): View {
        val initText = text
        val initCursor = cursor.coerceIn(0, initText.length)
        val f = AppCompatEditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface_variant))
            hint = "Type custom instructions"
            val p = ctx.dp(Spacing.xl)
            setPadding(p, p, p, p)
            textSize = Typography.subpageTitle
            background = null
            isFocusable = true
            isFocusableInTouchMode = true
            setShowSoftInputOnFocus(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(initText)
            setSelection(initCursor)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    refreshActionEnablement()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
            post { requestFocus() }
        }
        field = f
        refreshActionEnablement()

        val bg = GradientDrawable().apply {
            cornerRadius = ctx.dp(Shapes.panelCorner).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.aide_surface_container_high))
        }

        return ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(Sizes.editorPane),
            ).apply {
                val m = ctx.dp(Spacing.sm)
                setMargins(m, m, m, m)
            }
            background = bg
            isVerticalScrollBarEnabled = true
            addView(f)
        }
    }

    fun insert(text: String) {
        val f = field ?: return
        val s = f.selectionStart.coerceAtLeast(0)
        val e = f.selectionEnd.coerceAtLeast(s)
        f.text?.replace(s, e, text)
    }

    fun backspace() {
        val f = field ?: return
        val s = f.selectionStart
        val e = f.selectionEnd
        if (s != e) {
            f.text?.delete(minOf(s, e), maxOf(s, e))
            return
        }
        if (s <= 0) return
        f.text?.delete(s - 1, s)
    }

    fun backspaceWord() {
        val f = field ?: return
        val s = f.selectionStart
        val e = f.selectionEnd
        if (s != e) {
            f.text?.delete(minOf(s, e), maxOf(s, e))
            return
        }
        if (s <= 0) return
        val t = f.text ?: return
        var i = s
        while (i > 0 && t[i - 1].isWhitespace()) i--
        while (i > 0 && !t[i - 1].isWhitespace()) i--
        if (i < s) f.text?.delete(i, s)
    }
}
