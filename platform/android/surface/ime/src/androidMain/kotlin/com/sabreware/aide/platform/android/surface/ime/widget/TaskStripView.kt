package com.sabreware.aide.platform.android.surface.ime.widget

import kotlinx.coroutines.flow.filterNotNull
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.sabreware.aide.feature.tasks.domain.GroupChipItem
import com.sabreware.aide.feature.tasks.domain.Task
import com.sabreware.aide.platform.android.surface.ime.R
import com.sabreware.aide.platform.android.surface.ime.theme.Spacing
import com.sabreware.aide.platform.android.surface.ime.theme.dp
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TaskStripView(
    context: Context,
    private val chipsFlow: StateFlow<List<GroupChipItem>?>,
    private val modeFlow: StateFlow<TransformController.Mode>,
    private val onTaskTap: (Task) -> Unit,
    private val onGroupTap: (GroupChipItem) -> Unit,
    private val onAddNew: () -> Unit,
    private val onAddInstruction: () -> Unit,
    private val onTaskLong: ((Task) -> Unit)? = null,
    private val onGroupLong: ((GroupChipItem) -> Unit)? = null,
) : HorizontalScrollView(context) {

    private val ctx: Context = context

    private val gapPx: Int = ctx.dp(Spacing.rowGap)

    private val container: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private var chipsJob: Job? = null
    private var modeJob: Job? = null

    init {
        isHorizontalScrollBarEnabled = false
        // Vertical inset comes from the parent [ImeActionBar] so every chrome
        // bar shares one source of truth for top/bottom breathing room.
        clipChildren = false
        clipToPadding = false
        addView(container)
    }

    fun start(scope: CoroutineScope) {
        stop()
        // Not built until the task store answers: the strip would otherwise draw only the instruction chip
        // and then grow as the saved tasks land.
        chipsJob = scope.launch { chipsFlow.filterNotNull().collect { rebuild(it) } }
        modeJob = scope.launch { modeFlow.collect { applyGate(it) } }
    }

    fun stop() {
        chipsJob?.cancel(); chipsJob = null
        modeJob?.cancel(); modeJob = null
    }

    private fun rebuild(items: List<GroupChipItem>) {
        container.removeAllViews()
        addChip(instructionChip())
        items.forEach { item ->
            val view = if (item.members.size == 1) {
                taskChip(item.members.first())
            } else {
                groupChip(item)
            }
            addChip(view)
        }
        addChip(addNewChip())
        applyGate(modeFlow.value)
    }

    private fun instructionChip(): AideButton = aideButton(
        ctx = ctx,
        shape = AideButtonShape.Pill,
        style = AideButtonStyle.Tonal,
        iconRes = R.drawable.ic_lc_pencil,
        contentDesc = "Add custom instruction",
        onPress = { onAddInstruction() },
    )

    // Preserve aideButton's intrinsic height; WRAP_CONTENT here would collapse to text height.
    private fun addChip(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { view.layoutParams = it }
        lp.marginEnd = gapPx
        container.addView(view)
    }

    private fun applyGate(mode: TransformController.Mode) {
        val locked = mode is TransformController.Mode.NoModel ||
            mode is TransformController.Mode.DownloadingModel
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            c.isEnabled = !locked
            c.isClickable = !locked
            c.alpha = if (locked) 0.4f else 1f
        }
    }

    private fun taskChip(task: Task): AideButton = aideButton(
        ctx = ctx,
        shape = AideButtonShape.Pill,
        style = AideButtonStyle.Standard,
        label = task.name,
        onPress = { onTaskTap(task) },
        onLongPress = onTaskLong?.let { cb -> { cb(task) } },
    )

    private fun groupChip(item: GroupChipItem): AideButton {
        val iconRes = item.group.iconName?.let { resolveDrawable(it) }
        return if (iconRes != null) {
            aideButton(
                ctx = ctx,
                shape = AideButtonShape.Pill,
                style = AideButtonStyle.Tonal,
                label = null,
                iconRes = iconRes,
                contentDesc = item.group.name,
                onPress = { onGroupTap(item) },
                onLongPress = onGroupLong?.let { cb -> { cb(item) } },
            )
        } else {
            aideButton(
                ctx = ctx,
                shape = AideButtonShape.Pill,
                style = AideButtonStyle.Tonal,
                label = item.group.name,
                iconRes = R.drawable.ic_lc_chevron_down,
                iconPosition = IconPosition.End,
                onPress = { onGroupTap(item) },
                onLongPress = onGroupLong?.let { cb -> { cb(item) } },
            )
        }
    }

    private fun resolveDrawable(name: String): Int? {
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        return if (id != 0) id else null
    }

    private fun addNewChip(): AideButton = aideButton(
        ctx = ctx,
        shape = AideButtonShape.Pill,
        style = AideButtonStyle.Standard,
        label = "+ New",
        onPress = { onAddNew() },
    )

}
