package com.swaptr.aide.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.core.DragIndication
import com.composables.core.ModalBottomSheet
import com.composables.core.Scrim
import com.composables.core.Sheet
import com.composables.core.SheetDetent
import com.composables.core.rememberModalBottomSheetState

interface AppSheetController {
    fun close(andThen: () -> Unit = {})
}

@Composable
fun AppSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    contentSpacing: Dp = 16.dp,
    scrollableContent: Boolean = true,
    content: @Composable ColumnScope.(AppSheetController) -> Unit,
) {
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val screenHeight = with(density) { containerSize.height.toDp() }
    val sheetMaxHeight = screenHeight * 0.75f
    val maxDetent = remember {
        SheetDetent(identifier = "max-75") { containerHeight, _ ->
            containerHeight * 0.75f
        }
    }
    val sheetState = rememberModalBottomSheetState(
        initialDetent = SheetDetent.Hidden,
        detents = listOf(SheetDetent.Hidden, maxDetent),
    )
    val latestDismiss by rememberUpdatedState(onDismiss)
    var pendingAndThen by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(sheetState) {
        sheetState.animateTo(maxDetent)
    }

    val controller = remember(sheetState) {
        object : AppSheetController {
            override fun close(andThen: () -> Unit) {
                pendingAndThen = andThen
                sheetState.targetDetent = SheetDetent.Hidden
            }
        }
    }

    ModalBottomSheet(
        state = sheetState,
        onDismiss = {
            val andThen = pendingAndThen
            pendingAndThen = null
            andThen?.invoke()
            latestDismiss()
        },
    ) {
        Scrim(
            scrimColor = Color.Black.copy(alpha = 0.32f),
            enter = fadeIn(),
            exit = fadeOut(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Sheet(
                modifier = modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        DragIndication(
                            modifier = Modifier
                                .padding(top = 12.dp, bottom = 4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(100),
                                )
                                .height(4.dp)
                                .width(32.dp),
                        )
                    }
                    if (title != null) {
                        SheetTitle(title = title, subtitle = subtitle)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = !scrollableContent)
                            .animateContentSize(animationSpec = tween(durationMillis = 250))
                            .then(
                                if (scrollableContent) {
                                    Modifier.verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            )
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(contentSpacing),
                    ) { content(controller) }
                }
            }
        }
    }
}
