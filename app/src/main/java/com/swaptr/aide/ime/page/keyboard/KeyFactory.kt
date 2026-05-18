package com.swaptr.aide.ime.page.keyboard

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import com.swaptr.aide.R
import com.swaptr.aide.ime.theme.Shapes
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.Spacing
import com.swaptr.aide.ime.theme.Typography
import com.swaptr.aide.ime.theme.dp
import com.swaptr.aide.ime.widget.AideButton
import com.swaptr.aide.ime.widget.AideButtonStyle
import com.swaptr.aide.ime.widget.AideButtonTouchMode
import com.swaptr.aide.ime.widget.KeyPopup
import com.swaptr.aide.ime.widget.aideButton

internal class KeyFactory(
    private val ctx: Context,
    private val popup: KeyPopup,
    private val callbacks: KeyboardCallbacks,
) {

    fun charKey(ch: String, weight: Float, superscript: String? = null): AideButton {
        val alts = KeyboardSpec.altsFor(callbacks.layer(), ch)
        return aideButton(
            ctx = ctx,
            shape = Shapes.key,
            style = AideButtonStyle.Standard,
            heightDp = Sizes.keyHeight,
            weight = weight,
            marginDp = Spacing.keyMargin,
            label = shiftedLabel(ch),
            secondaryLabel = superscript,
            labelTextSp = Typography.keyLabel,
            paddingHorizDp = Spacing.sm,
            paddingVertDp = Spacing.none,
            touchMode = AideButtonTouchMode.DispatchedByParent,
            baseChar = ch,
            alts = alts,
            popup = popup,
            onAltChosen = if (alts != null) { alt -> callbacks.onReplaceLastChar(alt) } else null,
            onCancelBase = if (alts != null) { { callbacks.onDeleteLastChar() } } else null,
            onPress = { callbacks.onCommit(shiftedLabel(ch)) },
        )
    }

    fun modifierKey(
        weight: Float,
        label: String? = null,
        iconRes: Int? = null,
        contentDesc: String? = null,
        onPress: () -> Unit,
    ): AideButton = aideButton(
        ctx = ctx,
        shape = Shapes.key,
        style = AideButtonStyle.Tonal,
        heightDp = Sizes.keyHeight,
        weight = weight,
        marginDp = Spacing.keyMargin,
        label = label,
        iconRes = iconRes,
        contentDesc = contentDesc,
        touchMode = AideButtonTouchMode.DispatchedByParent,
        onPress = onPress,
    )

    fun shiftKey(weight: Float): AideButton = keyButton(
        weight = weight,
        style = shiftStyle(),
        iconRes = R.drawable.ic_lc_arrow_big_up,
        contentDesc = shiftContentDesc(),
        onPress = { callbacks.onShiftTap() },
    )

    fun backspaceKey(weight: Float): AideButton = keyButton(
        weight = weight,
        style = AideButtonStyle.Tonal,
        iconRes = R.drawable.ic_lc_delete,
        contentDesc = "Backspace",
        onPress = { callbacks.onBackspace() },
        onRepeat = { count ->
            if (count < KeyboardSpec.REPEAT_WORDS_AFTER) callbacks.onBackspace()
            else callbacks.onBackspaceWord()
        },
    )

    fun spaceKey(weight: Float): AideButton = keyButton(
        weight = weight,
        style = AideButtonStyle.Standard,
        iconRes = R.drawable.ic_lc_space,
        contentDesc = "Space",
        onPress = { callbacks.onCommit(" ") },
    )

    fun actionKey(weight: Float): AideButton {
        val (iconRes, desc) = actionVisual()
        return keyButton(
            weight = weight,
            style = actionStyle(),
            iconRes = iconRes,
            contentDesc = desc,
            onPress = { callbacks.onEnter() },
        )
    }

    fun emojiKey(weight: Float): AideButton = keyButton(
        weight = weight,
        style = AideButtonStyle.Standard,
        iconRes = R.drawable.ic_lc_smile,
        contentDesc = "Emoji",
        // Emoji picker not yet implemented; key reserves the spot so the layout
        // matches the final design.
        onPress = { /* no-op */ },
    )

    fun keyButton(
        weight: Float,
        style: AideButtonStyle,
        label: String? = null,
        iconRes: Int? = null,
        contentDesc: String? = null,
        onPress: () -> Unit,
        onRepeat: ((Int) -> Unit)? = null,
    ): AideButton = aideButton(
        ctx = ctx,
        shape = Shapes.key,
        style = style,
        heightDp = Sizes.keyHeight,
        weight = weight,
        marginDp = Spacing.keyMargin,
        label = label,
        iconRes = iconRes,
        contentDesc = contentDesc,
        touchMode = AideButtonTouchMode.DispatchedByParent,
        onPress = onPress,
        onRepeat = onRepeat,
    )

    fun spacerView(weight: Float): View = View(ctx).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(0, ctx.dp(Sizes.keyHeight), weight)
    }

    fun shiftStyle(): AideButtonStyle = when (callbacks.shift()) {
        Shift.OFF -> AideButtonStyle.Tonal
        Shift.SHIFT -> AideButtonStyle.ModifierActive
        Shift.CAPS_LOCK -> AideButtonStyle.Accent
    }

    fun shiftContentDesc(): String = when (callbacks.shift()) {
        Shift.OFF -> "Shift"
        Shift.SHIFT -> "Shift on"
        Shift.CAPS_LOCK -> "Caps lock"
    }

    private fun shiftedLabel(ch: String): String =
        if (callbacks.layer() == Layer.LETTERS && callbacks.shift() != Shift.OFF) ch.uppercase() else ch

    private fun actionStyle(): AideButtonStyle {
        val opts = callbacks.editorInfo()?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (noEnterAction) return AideButtonStyle.Tonal
        return when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS -> AideButtonStyle.Accent
            else -> AideButtonStyle.Tonal
        }
    }

    private fun actionVisual(): Pair<Int, String> {
        val opts = callbacks.editorInfo()?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (noEnterAction) return R.drawable.ic_lc_corner_down_left to "Enter"
        return when (action) {
            EditorInfo.IME_ACTION_GO -> R.drawable.ic_lc_arrow_right to "Go"
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_lc_search to "Search"
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_lc_send to "Send"
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_lc_check to "Done"
            EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_lc_arrow_right to "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_lc_arrow_left to "Previous"
            else -> R.drawable.ic_lc_corner_down_left to "Enter"
        }
    }
}
