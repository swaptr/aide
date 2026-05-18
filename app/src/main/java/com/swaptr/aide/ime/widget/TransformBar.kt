package com.swaptr.aide.ime.widget

import android.content.Context
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swaptr.aide.R
import com.swaptr.aide.data.model.ModelGateState
import com.swaptr.aide.data.task.GroupChipItem
import com.swaptr.aide.data.task.TaskEntity
import com.swaptr.aide.domain.speech.dictation.DictationController
import com.swaptr.aide.domain.speech.dictation.DictationSurfaceId
import com.swaptr.aide.domain.speech.dictation.sink.InputConnectionSink
import com.swaptr.aide.ime.theme.Shapes
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.Spacing
import com.swaptr.aide.ime.theme.Typography
import com.swaptr.aide.ime.theme.dp
import com.swaptr.aide.ime.transform.TransformController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TransformBar(
    rawContext: Context,
    private val controller: TransformController,
    onTaskTap: (TaskEntity) -> Unit,
    onGroupTap: (GroupChipItem) -> Unit,
    onAddNew: () -> Unit,
    onAddInstruction: () -> Unit,
    onMagicTap: () -> Unit,
    onQueueTap: () -> Unit,
    onTaskLong: (TaskEntity) -> Unit,
    onGroupLong: (GroupChipItem) -> Unit,
    private val dictationController: DictationController?,
    private val dictationSurface: DictationSurfaceId,
    private val dictationSink: InputConnectionSink,
    private val editorInfoProvider: () -> EditorInfo?,
    private val onOpenModels: () -> Unit,
) : FrameLayout(rawContext) {

    private val ctx: Context = ContextThemeWrapper(rawContext, R.style.Theme_Aide_Ime)

    private val taskStrip: TaskStripView = TaskStripView(
        context = ctx,
        chipsFlow = controller.chips,
        modeFlow = controller.mode,
        onTaskTap = onTaskTap,
        onGroupTap = onGroupTap,
        onAddNew = onAddNew,
        onAddInstruction = onAddInstruction,
        onTaskLong = onTaskLong,
        onGroupLong = onGroupLong,
    )

    private val magicBtn: AideButton = aideButton(
        ctx = ctx,
        shape = Shapes.chrome,
        style = AideButtonStyle.Accent,
        iconRes = R.drawable.ic_lc_sparkles,
        contentDesc = "Generate from prompt",
        onPress = { onMagicTap() },
    )

    private val queueBtn: AideButton = aideButton(
        ctx = ctx,
        shape = Shapes.chrome,
        style = AideButtonStyle.Tonal,
        iconRes = R.drawable.ic_lc_list_plus,
        contentDesc = "Open queue",
        onPress = { onQueueTap() },
    )

    private val queueBadge: TextView = TextView(ctx).apply {
        textSize = Typography.popupBadge
        setTextColor(android.graphics.Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(ctx.dp(Spacing.sm), 0, ctx.dp(Spacing.sm), 0)
        minWidth = ctx.dp(Sizes.badgeMin)
        visibility = View.GONE
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(ContextCompat.getColor(ctx, R.color.aide_accent))
        }
    }

    private val queueWrap: FrameLayout = FrameLayout(ctx).apply {
        val px = ctx.dp(Sizes.buttonSize)
        layoutParams = ViewGroup.MarginLayoutParams(px, px)
        addView(queueBtn, FrameLayout.LayoutParams(px, px))
        addView(
            queueBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = ctx.dp(Spacing.xxs)
                marginEnd = ctx.dp(Spacing.xxs)
            },
        )
    }

    private val readyRow: ImeActionBar = ImeActionBar(
        context = ctx,
        scrollableMiddle = false,
    )

    private val overlay: BarOverlay = BarOverlay(ctx)

    private val noModelContent: View by lazy {
        buildOverlayContent("Tap to set up a model.")
    }

    private val sensitiveContent: View by lazy {
        buildOverlayContent("Aide hidden on private fields.")
    }

    private var micButton: AideButton? = null
    private var dictationJob: Job? = null
    private var gateJob: Job? = null
    private var chainJob: Job? = null
    private var modeJob: Job? = null
    private var sensitiveJob: Job? = null
    private var gateLocked: Boolean = false
    private var sensitive: Boolean = false

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            ctx.dp(Sizes.barHeight),
        )
        addView(
            readyRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        rebuildReadyRow()
    }

    fun start(scope: CoroutineScope) {
        stop()
        taskStrip.start(scope)
        dictationController?.let { ctrl ->
            dictationJob = scope.launch {
                ctrl.stateFor(dictationSurface).collect { state ->
                    val btn = micButton ?: return@collect
                    btn.alpha = if (state.isDictating) 0.55f else 1f
                    btn.contentDescription =
                        if (state.isDictating) "Stop dictation" else "Voice input"
                }
            }
        }
        gateJob = scope.launch {
            controller.gateState.collect(::applyGate)
        }
        chainJob = scope.launch {
            controller.chain.collect { renderQueueBadge() }
        }
        modeJob = scope.launch {
            controller.mode.collect { renderMagicEnablement() }
        }
        sensitiveJob = scope.launch {
            controller.fieldSensitive.collect { sens ->
                sensitive = sens
                renderOverlay()
            }
        }
    }

    fun stop() {
        taskStrip.stop()
        dictationJob?.cancel(); dictationJob = null
        gateJob?.cancel(); gateJob = null
        chainJob?.cancel(); chainJob = null
        modeJob?.cancel(); modeJob = null
        sensitiveJob?.cancel(); sensitiveJob = null
    }

    fun refreshMicVisibility() {
        rebuildReadyRow()
    }

    private fun applyGate(state: ModelGateState) {
        gateLocked = state !is ModelGateState.Ready
        renderOverlay()
    }

    private fun renderOverlay() {
        when {
            sensitive -> overlay.show(sensitiveContent)
            gateLocked -> overlay.show(noModelContent, onTap = { onOpenModels() })
            else -> overlay.hide()
        }
    }

    private fun buildOverlayContent(message: String): View = TextView(ctx).apply {
        text = message
        textSize = Typography.queueRow
        setTypeface(typeface, Typeface.NORMAL)
        setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
        val h = ctx.dp(Spacing.xl)
        setPadding(h, 0, h, 0)
    }

    private fun renderQueueBadge() {
        val count = controller.chain.value.size
        queueBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
        queueBadge.text = count.toString()
    }

    private fun renderMagicEnablement() {
        val gateLocked = controller.mode.value is TransformController.Mode.NoModel ||
            controller.mode.value is TransformController.Mode.DownloadingModel
        magicBtn.isEnabled = !gateLocked
        queueBtn.isEnabled = !gateLocked
    }

    private fun rebuildReadyRow() {
        readyRow.setMiddle(taskStrip)

        // Sensitive-field hide is service-owned; here we just decide if the mic exists at all.
        val leading = mutableListOf<View>(queueWrap)
        val ctrl = dictationController
        if (ctrl != null) {
            val mic = aideButton(
                ctx = ctx,
                shape = Shapes.chrome,
                style = AideButtonStyle.Tonal,
                iconRes = R.drawable.ic_lc_mic,
                contentDesc = "Voice input",
                onPress = {
                    ctrl.toggle(
                        surface = dictationSurface,
                        sink = dictationSink,
                        editorInfo = editorInfoProvider(),
                    )
                },
            )
            micButton = mic
            leading += mic
        } else {
            micButton = null
        }
        readyRow.setLeadingChildren(leading)
        readyRow.setTrailing(magicBtn)
        renderQueueBadge()
        renderMagicEnablement()
    }
}
