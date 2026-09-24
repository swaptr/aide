package com.sabreware.aide.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sabreware.aide.core.designsystem.AppScaffold
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.HeaderPlacement
import com.sabreware.aide.core.designsystem.ReadingMaxWidth

/**
 * Chat's scaffold — the app's one deliberately fill-height page (a weighted message `LazyColumn`
 * plus a pinned composer). Delegates chrome + insets to [AppScaffold] and lays content in a plain
 * (non-scrolling) [Column] carrying the inset modifier, so the composer's `imePadding` rides the
 * keyboard while the message list keeps its own scroll.
 */
@Composable
fun ChatScaffold(
    titleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    headerColor: Color = MaterialTheme.colorScheme.surface,
    // The top banner docks outside the content, above the header; see AppScaffold.
    topBanner: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    AppScaffold(
        titleContent = titleContent,
        modifier = modifier,
        leadingAction = leadingAction,
        trailingActions = trailingActions,
        headerColor = headerColor,
        topBanner = topBanner,
        // The model picker sits on the screen's center line, not the middle of whatever the slots leave.
        placement = HeaderPlacement.CenteredPage,
    ) { contentModifier ->
        // Full-bleed background + app bar (owned by AppScaffold); the conversation + composer cap at a
        // readable width and center on a wide window so lines don't run painfully long. On a phone the cap
        // is wider than the screen, so it's edge-to-edge as before.
        Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ReadingMaxWidth).fillMaxSize(),
                content = content,
            )
        }
    }
}
