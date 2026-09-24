package com.sabreware.aide.platform.android.surface.ime.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sabreware.aide.platform.android.surface.ime.R
import com.sabreware.aide.platform.android.surface.ime.theme.Shapes
import com.sabreware.aide.platform.android.surface.ime.theme.Spacing
import com.sabreware.aide.platform.android.surface.ime.theme.Typography
import com.sabreware.aide.platform.android.surface.ime.theme.dp
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@SuppressLint("SetTextI18n")
class QueueSheet(
    rawContext: Context,
    private val controller: TransformController,
) {

    private val ctx: Context = ContextThemeWrapper(rawContext, R.style.Theme_Aide_Ime)

    private val slide: SlideOverPanel = SlideOverPanel(ctx, side = PanelSide.Start)

    private val listContainer: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val undoBtn: AideButton = chromeIconButton(R.drawable.ic_lc_undo, "Undo") {
        controller.undo()
    }
    private val redoBtn: AideButton = chromeIconButton(R.drawable.ic_lc_redo, "Redo") {
        controller.redo()
    }
    private val closeBtn: AideButton = chromeIconButton(com.sabreware.aide.platform.android.R.drawable.ic_lc_x, "Close queue") {
        hide()
    }

    private val panel: View = run {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(ctx).apply {
                    text = "Queue"
                    textSize = Typography.responseBody
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(undoBtn)
            addGap()
            addView(redoBtn)
            addGap()
            addView(closeBtn)
        }
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.aide_surface_container_high))
            header.setPadding(
                ctx.dp(Spacing.xl),
                ctx.dp(Spacing.md),
                ctx.dp(Spacing.xl),
                ctx.dp(Spacing.md),
            )
            addView(header)
            addView(
                ScrollView(ctx).apply {
                    isVerticalScrollBarEnabled = false
                    addView(listContainer)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private var chainJob: Job? = null
    private var modeJob: Job? = null

    val root: View get() = slide.root
    val isOpen: Boolean get() = slide.isOpen

    init {
        slide.setPanel(panel)
        renderList(emptyList())
        renderButtons()
    }

    fun setContent(content: View) {
        slide.setContent(content)
    }

    fun start(scope: CoroutineScope) {
        stop()
        chainJob = scope.launch {
            controller.chain.collect { steps ->
                renderList(steps)
                renderButtons()
            }
        }
        modeJob = scope.launch {
            controller.mode.collect { renderButtons() }
        }
    }

    fun stop() {
        chainJob?.cancel(); chainJob = null
        modeJob?.cancel(); modeJob = null
    }

    fun toggle() {
        if (slide.isOpen) slide.close() else slide.open()
    }

    fun hide() {
        slide.close()
    }

    private fun renderList(steps: List<TransformController.ChainStep>) {
        listContainer.removeAllViews()
        if (steps.isEmpty()) {
            listContainer.addView(emptyState())
            return
        }
        steps.forEachIndexed { index, step ->
            listContainer.addView(stepRow(index + 1, step.taskName) {
                controller.removeChainStep(index)
            })
        }
    }

    private fun renderButtons() {
        val gateLocked = controller.mode.value is TransformController.Mode.NoModel ||
            controller.mode.value is TransformController.Mode.DownloadingModel
        undoBtn.isEnabled = !gateLocked && controller.canUndo()
        redoBtn.isEnabled = !gateLocked && controller.canRedo()
    }

    private fun emptyState(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            ctx.dp(Spacing.lg),
            ctx.dp(Spacing.xxl),
            ctx.dp(Spacing.lg),
            ctx.dp(Spacing.xxl),
        )
        addView(TextView(ctx).apply {
            text = "Queue is empty"
            textSize = Typography.responseBody
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
        })
        addView(TextView(ctx).apply {
            text = "Tap \"Add to queue\" on a result to stage it here."
            textSize = Typography.queueRow
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface_variant))
            setPadding(0, ctx.dp(Spacing.xs), 0, 0)
        })
    }

    private fun stepRow(index: Int, name: String, onRemove: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                ctx.dp(Spacing.lg),
                ctx.dp(Spacing.xxs),
                ctx.dp(Spacing.sm),
                ctx.dp(Spacing.xxs),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(
            TextView(ctx).apply {
                text = "$index. $name"
                textSize = Typography.queueRow
                setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(chromeIconButton(com.sabreware.aide.platform.android.R.drawable.ic_lc_x, "Remove", onRemove))
        return row
    }

    private fun chromeIconButton(
        iconRes: Int,
        contentDesc: String,
        onPress: () -> Unit,
    ): AideButton = aideButton(
        ctx = ctx,
        shape = Shapes.pill,
        style = AideButtonStyle.Tonal,
        iconRes = iconRes,
        contentDesc = contentDesc,
        onPress = onPress,
    )

    private fun ViewGroup.addGap() {
        addView(View(ctx), ViewGroup.LayoutParams(ctx.dp(Spacing.sm), 0))
    }
}
