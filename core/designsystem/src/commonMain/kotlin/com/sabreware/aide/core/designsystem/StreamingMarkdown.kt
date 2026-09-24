package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * The app's one Markdown renderer for model output — used by both the chat assistant reply and the
 * reasoning-trace sheet so headings/bold/lists/code render identically and update in real time as
 * tokens stream in.
 *
 * Streaming is handled entirely by the markdown library, not by us:
 *  - [rememberMarkdownState] with `retainState = true` keeps the *last* parsed tree on screen while the
 *    next token re-parses, so the reply never blanks out between tokens (the default re-parses to a
 *    Loading state on every change — that is what makes a naive `Markdown(text)` show nothing until the
 *    very end). Formatting therefore appears live, token by token — no "raw `**bold**` now, formatted
 *    later" transition.
 *  - [markdownAnimations] `{ it }` strips the library's default per-block `animateContentSize`, which
 *    otherwise re-runs a size animation on every token and makes the text visibly jump/reflow.
 *
 * @param text the markdown source (grows token-by-token while a reply streams).
 * @param textStyle / [textColor] style for the [placeholder] shown before any text arrives.
 * @param placeholder shown when [text] is blank (e.g. "…", "Generating…", "(empty trace)").
 * @param settled the text is final. Only a settled tree is cached: a streaming reply's hundreds of partial
 *   trees would each evict a finished one.
 */
@Composable
fun StreamingMarkdown(
    text: String,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
    placeholder: String = "…",
    settled: Boolean = true,
) {
    if (text.isBlank()) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(text = placeholder, style = textStyle, color = textColor)
        }
        return
    }
    // A reply scrolled back into view is parsed already: its tree comes from the cache, so it draws at full
    // height on its first frame and costs no parse. Only text not seen yet (a streaming reply, a page of older
    // turns) is parsed, off the main thread, and cached once its tree matches the text.
    val cached = remember(text, settled) { if (settled) ParsedMarkdownCache[text] else null }
    val parsed: State = if (cached != null) {
        cached
    } else {
        val markdownState = rememberMarkdownState(content = text, retainState = true)
        val latest by markdownState.state.collectAsState()
        LaunchedEffect(latest, text, settled) {
            if (!settled) return@LaunchedEffect
            (latest as? State.Success)?.takeIf { it.content == text }?.let { ParsedMarkdownCache[text] = it }
        }
        latest
    }
    // No-op block animation: the library's default wraps each block in animateContentSize, which
    // re-runs a size animation on every token and makes streaming text visibly jump/reflow.
    val noBlockAnimation = remember { object : MarkdownAnimations { override val animateTextSize = NoOp } }
    // SelectionContainer makes the rendered text user-selectable (long-press / double-tap-to-select-word
    // with drag handles + the system copy toolbar). It wraps the markdown's own (non-lazy) block Column,
    // not the chat LazyColumn, so per-message selection is well-defined — wrapping a lazy list would drop
    // off-screen text from copy/select-all.
    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Markdown(
            state = parsed,
            colors = colors,
            typography = typography,
            animations = noBlockAnimation,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val NoOp: (Modifier) -> Modifier = { it }

/**
 * Parsed trees of recently drawn markdown, least recently used out first. Touched only from composition, so
 * it needs no lock. Sized to a few screens of replies: past that, a reply scrolled back to parses again.
 */
private object ParsedMarkdownCache {
    private const val CAPACITY = 48
    private val entries = LinkedHashMap<String, State.Success>()

    operator fun get(text: String): State.Success? =
        entries.remove(text)?.also { entries[text] = it }

    operator fun set(text: String, parsed: State.Success) {
        entries.remove(text)
        entries[text] = parsed
        if (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }
}
