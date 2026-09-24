package com.sabreware.aide.platform.android.surface.ime.widget

import com.sabreware.aide.core.domain.model.ModelGateState
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sabreware.aide.platform.android.surface.ime.R
import com.sabreware.aide.platform.android.surface.ime.theme.Shapes
import com.sabreware.aide.platform.android.surface.ime.theme.Sizes
import com.sabreware.aide.platform.android.surface.ime.theme.Spacing
import com.sabreware.aide.platform.android.surface.ime.theme.Typography
import com.sabreware.aide.platform.android.surface.ime.theme.dp
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch


class ResponsePopupView(
    rawContext: Context,
    private val controller: TransformController,
    private val connectionProvider: () -> InputConnection?,
) : LinearLayout(rawContext) {

    private val ctx: Context = ContextThemeWrapper(rawContext, R.style.Theme_Aide_Ime)

    private val responseText: TextView
    private val responseScroll: ScrollView
    private val rejectBtn: AideButton
    private val queueBtn: AideButton
    private val acceptBtn: AideButton

    private var observerJob: Job? = null
    private var lastOutput: String = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(
            ContextCompat.getColor(ctx, R.color.aide_surface_container_high),
        )

        responseText = TextView(ctx).apply {
            textSize = Typography.responseBody
            setLineSpacing(0f, 1.15f)
            setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
            setPadding(
                ctx.dp(Spacing.xl), ctx.dp(Spacing.responsePadV),
                ctx.dp(Spacing.xl), ctx.dp(Spacing.responsePadV),
            )
        }
        responseScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                responseText,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addView(
            responseScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        rejectBtn = aideButton(
            ctx = ctx,
            shape = Shapes.chrome,
            style = AideButtonStyle.Tonal,
            heightDp = Sizes.popupButtonSize,
            widthDp = Sizes.popupButtonSize,
            iconRes = com.sabreware.aide.platform.android.R.drawable.ic_lc_x,
            contentDesc = "Reject",
            onPress = { onRejectClicked() },
        )
        queueBtn = aideButton(
            ctx = ctx,
            shape = Shapes.chrome,
            style = AideButtonStyle.Tonal,
            heightDp = Sizes.popupButtonSize,
            widthDp = Sizes.popupButtonSize,
            iconRes = R.drawable.ic_lc_list_plus,
            contentDesc = "Add to queue",
            onPress = { onQueueClicked() },
        )
        acceptBtn = aideButton(
            ctx = ctx,
            shape = Shapes.chrome,
            style = AideButtonStyle.Accent,
            heightDp = Sizes.popupButtonSize,
            widthDp = Sizes.popupButtonSize,
            iconRes = R.drawable.ic_lc_check,
            contentDesc = "Apply",
            onPress = { onAcceptClicked() },
        )

        val actions = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(rejectBtn)
            (queueBtn.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = ctx.dp(Spacing.sm)
            addView(queueBtn)
            (acceptBtn.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = ctx.dp(Spacing.sm)
            addView(acceptBtn)
        }
        addView(
            actions,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(
                    ctx.dp(Spacing.lg), ctx.dp(Spacing.sm),
                    ctx.dp(Spacing.lg), ctx.dp(Spacing.md),
                )
            },
        )
    }

    fun start(scope: CoroutineScope) {
        stop()
        observerJob = scope.launch {
            controller.preview
                .combine(controller.mode) { p, m -> p to m }
                .collect { (preview, mode) -> render(preview, mode) }
        }
    }

    fun stop() {
        observerJob?.cancel()
        observerJob = null
    }

    fun resetScroll() {
        lastOutput = ""
        responseScroll.scrollTo(0, 0)
    }

    private fun render(
        preview: TransformController.Preview?,
        mode: TransformController.Mode,
    ) {
        if (preview == null) return

        val isFinal = preview.isFinal
        val streaming = mode is TransformController.Mode.Loading ||
            mode is TransformController.Mode.Generating

        // A reroute (the user's own setting) still sends their text to a model they did not pick — the
        // keyboard runs inside other apps, so it says which model is answering, and for whom.
        val rerouted = (controller.gateState.value as? ModelGateState.Ready)
            ?.let { gate -> gate.reroutedFrom?.let { "${gate.spec.displayName} (${it.displayName} unavailable)" } }
        val newText = when {
            mode is TransformController.Mode.Error -> mode.message
            preview.output.isNotBlank() -> preview.output
            mode is TransformController.Mode.Loading -> "Loading model…"
            rerouted != null -> "Generating with $rerouted…"
            else -> "Generating…"
        }
        if (responseText.text?.toString() != newText) {
            val isAppend = newText.startsWith(lastOutput) && newText.length > lastOutput.length
            responseText.text = newText
            if (streaming && isAppend && lastOutput.isNotEmpty()) {
                responseScroll.post { responseScroll.fullScroll(View.FOCUS_DOWN) }
            } else {
                responseScroll.scrollTo(0, 0)
            }
            lastOutput = newText
        }
        val placeholder = preview.output.isBlank() || mode is TransformController.Mode.Error
        responseText.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (placeholder) R.color.aide_on_surface_variant else R.color.aide_on_surface,
            ),
        )

        acceptBtn.isEnabled = isFinal
        acceptBtn.alpha = if (isFinal) 1f else 0.4f
        queueBtn.isEnabled = isFinal
        queueBtn.alpha = if (isFinal) 1f else 0.4f
        rejectBtn.contentDescription = if (streaming) "Cancel" else "Reject"
    }

    private fun onAcceptClicked() {
        controller.applyToField(connectionProvider())
    }

    private fun onQueueClicked() {
        controller.addPreviewToChain()
    }

    private fun onRejectClicked() {
        controller.discardPreview()
    }
}
