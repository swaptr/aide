package com.sabreware.aide.ui.chat

import androidx.compose.runtime.saveable.rememberSaveable
import com.sabreware.aide.core.designsystem.theme.LocalAideMediaColors
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.StreamingMarkdown
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import com.sabreware.aide.core.designsystem.theme.LocalAppSans
import com.sabreware.aide.core.designsystem.theme.LocalFontScale
import com.sabreware.aide.core.designsystem.theme.aideMarkdownBodyStyle
import com.sabreware.aide.core.designsystem.theme.scaleFont
import com.sabreware.aide.core.domain.chat.MessageStats
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun MessageItem(
    msg: ChatMessage,
    markdownColors: com.mikepenz.markdown.model.MarkdownColors,
    markdownTypography: com.mikepenz.markdown.model.MarkdownTypography,
    onToolClick: (ChatMessage.ToolInvocation) -> Unit,
    onThinkingClick: (ChatMessage.Thinking) -> Unit,
    onUserLongPress: (ChatMessage.User) -> Unit = {},
    // The reply-row actions are injected, not baked in: live chat gets the real handlers (the default,
    // [rememberReplyActions]); the font-size preview passes [ReplyActions.None] so the same row renders for
    // fidelity but every action is a no-op.
    replyActions: ReplyActions = rememberReplyActions(),
) {
    when (msg) {
        is ChatMessage.User -> UserMessageRow(msg, onLongPress = { onUserLongPress(msg) })
        is ChatMessage.Assistant -> AssistantMessageBlock(
            msg = msg,
            colors = markdownColors,
            typography = markdownTypography,
            actions = replyActions,
        )
        is ChatMessage.ToolInvocation -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            ToolCallChip(
                toolName = msg.toolName,
                isRunning = msg.isRunning,
                hasError = msg.error != null,
                onClick = { onToolClick(msg) },
            )
        }
        is ChatMessage.Thinking -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            ThinkingChip(
                isStreaming = msg.isStreaming,
                durationMs = msg.durationMs,
                onClick = { onThinkingClick(msg) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageRow(msg: ChatMessage.User, onLongPress: () -> Unit) {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalAppSans.current)
        .scaleFont(LocalFontScale.current)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // Long-press the user bubble to copy / select / edit the turn (combinedClickable adds
                // the long-press haptic). onClick is a no-op — the bubble has no tap action.
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (msg.imagePath != null) {
                AsyncImage(
                    model = msg.imagePath,
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                if (msg.text.isNotEmpty() || msg.audioPath != null) Spacer(Modifier.height(6.dp))
            }
            if (msg.audioPath != null) {
                AudioClipChip(path = msg.audioPath)
                if (msg.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }
            if (msg.documentName != null) {
                FileChip(name = msg.documentName)
                if (msg.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }
            if (msg.text.isNotEmpty() ||
                (msg.imagePath == null && msg.audioPath == null && msg.documentName == null)
            ) {
                Text(
                    text = msg.text,
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun AttachmentPreviewRow(
    path: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = path,
                contentDescription = "Pending attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .background(LocalAideMediaColors.current.scrim, CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_lc_x),
                    contentDescription = "Remove attachment",
                    tint = LocalAideMediaColors.current.onScrim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageBlock(
    msg: ChatMessage.Assistant,
    colors: com.mikepenz.markdown.model.MarkdownColors,
    typography: com.mikepenz.markdown.model.MarkdownTypography,
    actions: ReplyActions,
) {
    var showStats by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Before the first token arrives the reply text is blank — show an animated typing
        // indicator instead of a static "…" so the wait reads as live activity.
        if (msg.text.isBlank()) {
            TypingIndicator()
        } else {
            StreamingMarkdown(
                text = msg.text,
                colors = colors,
                typography = typography,
                textStyle = aideMarkdownBodyStyle(),
                textColor = MaterialTheme.colorScheme.onBackground,
                settled = !msg.isStreaming,
            )
        }
        if (!msg.isStreaming) {
            MessageActionsRow(
                text = msg.text,
                hasStats = msg.stats != null,
                onShowStats = { showStats = true },
                actions = actions,
            )
        }
    }
    val stats = msg.stats
    if (showStats && stats != null) {
        MessageStatsSheet(stats = stats, onDismiss = { showStats = false })
    }
}

/** Animated "assistant is generating" indicator: three dots rippling left-to-right in a scale + fade +
 *  slight-rise wave with a brief rest between cycles. Shown in place of the reply text until the first
 *  token lands. Sized to one body line so the swap to streamed text doesn't shift the layout. The
 *  per-frame animated values are read in the draw phase ([graphicsLayer]) so composition isn't re-run
 *  every frame. */
@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val lineHeight = with(LocalDensity.current) { aideMarkdownBodyStyle().lineHeight.toDp() }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier.height(lineHeight),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(TYPING_DOT_COUNT) { index ->
            val wave = transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = TYPING_CYCLE_MS
                        0f at 0
                        1f at TYPING_CYCLE_MS / 3 using FastOutSlowInEasing
                        0f at TYPING_CYCLE_MS * 2 / 3 using FastOutSlowInEasing
                        // holds at rest through the final third before the next ripple.
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * TYPING_STAGGER_MS),
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        val t = wave.value
                        val scale = 0.65f + 0.35f * t
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.35f + 0.65f * t
                        translationY = -t * 3.dp.toPx()
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

private const val TYPING_DOT_COUNT = 3
private const val TYPING_CYCLE_MS = 1200
private const val TYPING_STAGGER_MS = 180

/**
 * The action affordances under an assistant reply. Behaviour is injected via [ReplyActions] rather than
 * baked in, so the live chat passes real handlers ([rememberReplyActions]) and the font-size preview passes
 * [ReplyActions.None] — the identical row renders either way, the preview's taps just do nothing.
 */
@Composable
private fun MessageActionsRow(
    text: String,
    hasStats: Boolean,
    onShowStats: () -> Unit,
    actions: ReplyActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An affordance appears only when something is wired to it. Share, Play and Regenerate rendered
        // under every reply and did nothing — a button that does nothing is worse than an absent one,
        // because the user reads the silence as a failure. They come back when they do something.
        actions.onCopy?.let { copy -> ActionIcon(Res.drawable.ic_lc_copy, "Copy") { copy(text) } }
        actions.onShare?.let { share -> ActionIcon(Res.drawable.ic_lc_share, "Share") { share(text) } }
        actions.onPlay?.let { play -> ActionIcon(Res.drawable.ic_lc_play, "Play") { play(text) } }
        actions.onRegenerate?.let { again -> ActionIcon(Res.drawable.ic_lc_redo, "Regenerate") { again() } }
        if (hasStats) ActionIcon(Res.drawable.ic_lc_info, "Stats", onShowStats)
    }
}

/**
 * Hoisted, injectable behaviour for an assistant reply's [MessageActionsRow]. Live chat builds the real set
 * with [rememberReplyActions]; a static surface (the font-size preview) uses [None].
 *
 * **A null handler means the affordance is not rendered.** It used to default to a no-op lambda, which drew
 * three buttons that silently did nothing under every reply. Nullable makes "not implemented yet" and
 * "implemented" different at the type level, so an unwired action cannot ship as a dead button.
 */
@Immutable
class ReplyActions(
    val onCopy: ((text: String) -> Unit)? = null,
    val onShare: ((text: String) -> Unit)? = null,
    val onPlay: ((text: String) -> Unit)? = null,
    val onRegenerate: (() -> Unit)? = null,
) {
    companion object {
        /**
         * Every affordance present and inert — for a non-interactive preview that needs the row to LOOK
         * like the real one. Not for live surfaces: there, an action that does nothing is not rendered.
         */
        val None = ReplyActions(onCopy = {}, onShare = {}, onPlay = {}, onRegenerate = {})
    }
}

/** The real reply-row behaviour for live chat: Copy puts the reply on the clipboard. Share, Play and
 *  Regenerate are not wired, so they are not drawn. Remembered so the row isn't re-wired per recomposition. */
@Composable
internal fun rememberReplyActions(): ReplyActions {
    // Multiplatform clipboard write. On Android 13+ the system shows its own "Copied" confirmation.
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        ReplyActions(
            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
        )
    }
}

@Composable
private fun ActionIcon(
    iconRes: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // 44dp = the accessibility-minimum touch target; the glyph stays 18dp so the row reads the same.
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** One-decimal formatter (common-safe; String.format with a Locale isn't in commonMain). */
private fun oneDecimal(v: Double): String {
    val r = (v * 10).roundToInt()
    return "${r / 10}.${(r % 10 + 10) % 10}"
}

/** Per-message generation stats in plain words, surfaced from the row's info button. */
@Composable
private fun MessageStatsSheet(stats: MessageStats, onDismiss: () -> Unit) {
    AppDialog(onDismiss = onDismiss, title = "Reply stats") { _ ->
        AppMenu(
            items = listOfNotNull(
                stats.ttftMs?.let { stat("first", "Started replying after", seconds(it)) },
                stat("total", "Finished after", seconds(stats.totalMs)),
                stats.tokensPerSec?.let { stat("speed", "Speed", "${oneDecimal(it)} tokens per second") },
                stats.outputTokens?.let { stat("reply", "Reply size", tokens(it)) },
                stats.inputTokens?.let { stat("prompt", "Prompt size", tokens(it)) },
            ),
        )
    }
}

private fun stat(key: String, title: String, value: String) = AppMenuEntry(key = key, title = title, subtitle = value)

private fun seconds(ms: Long): String = "${oneDecimal(ms / 1000.0)} s"

private fun tokens(n: Int): String = if (n == 1) "1 token" else "$n tokens"
