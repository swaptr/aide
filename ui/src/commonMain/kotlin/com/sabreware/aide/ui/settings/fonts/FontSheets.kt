package com.sabreware.aide.ui.settings.fonts

import androidx.compose.runtime.Composable
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.domain.prefs.ChatFontStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/** Named stops, so text size is a plain single-select list. Values stay inside the store's 0.8–1.4 clamp. */
enum class ChatTextSize(val scale: Float, val label: String) {
    Small(0.9f, "Small"),
    Default(1f, "Default"),
    Large(1.1f, "Large"),
    ExtraLarge(1.25f, "Extra large"),
    Largest(1.4f, "Largest");

    val percentLabel: String get() = "${(scale * 100).roundToInt()}%"

    companion object {
        /** Nearest stop, so a value stored off-grid still resolves to a selected row. */
        fun nearest(scale: Float): ChatTextSize = entries.minBy { abs(it.scale - scale) }
    }
}

/** Human label for a [ChatFontStyle] — shared by the Settings row and the picker. */
fun chatFontStyleLabel(style: ChatFontStyle): String = when (style) {
    ChatFontStyle.Serif -> "Serif"
    ChatFontStyle.Sans -> "Sans"
    ChatFontStyle.Mono -> "Mono"
}

/** Applies on tap and closes — same interaction as [com.sabreware.aide.ui.settings.AppearanceSheet]. */
@Composable
fun ChatTextSizeSheet(
    selected: ChatTextSize,
    onSelect: (ChatTextSize) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Text size",
    ) { controller ->
        AppMenu(
            items = ChatTextSize.entries.map { size ->
                AppMenuEntry(
                    title = size.label,
                    subtitle = size.percentLabel,
                    selected = size == selected,
                    onClick = { onSelect(size); controller.close() },
                )
            },
        )
    }
}

@Composable
fun ChatFontStyleSheet(
    selected: ChatFontStyle,
    onSelect: (ChatFontStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Font style",
    ) { controller ->
        AppMenu(
            items = listOf(
                AppMenuEntry(
                    title = chatFontStyleLabel(ChatFontStyle.Serif),
                    subtitle = "Libre Baskerville. Easy to read.",
                    selected = selected == ChatFontStyle.Serif,
                    onClick = { onSelect(ChatFontStyle.Serif); controller.close() },
                ),
                AppMenuEntry(
                    title = chatFontStyleLabel(ChatFontStyle.Sans),
                    subtitle = "Google Sans. Matches the app.",
                    selected = selected == ChatFontStyle.Sans,
                    onClick = { onSelect(ChatFontStyle.Sans); controller.close() },
                ),
                AppMenuEntry(
                    title = chatFontStyleLabel(ChatFontStyle.Mono),
                    subtitle = "Fixed width. Good for code.",
                    selected = selected == ChatFontStyle.Mono,
                    onClick = { onSelect(ChatFontStyle.Mono); controller.close() },
                ),
            ),
        )
    }
}
