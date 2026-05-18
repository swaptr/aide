package com.swaptr.aide.ime.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.swaptr.aide.R
import com.swaptr.aide.data.task.GroupChipItem
import com.swaptr.aide.data.task.TaskEntity
import com.swaptr.aide.ime.theme.Shapes
import com.swaptr.aide.ime.theme.Spacing
import com.swaptr.aide.ime.theme.Typography
import com.swaptr.aide.ime.theme.dp

class ChipDetailSheet(
    rawContext: Context,
    private val onMemberTap: (TaskEntity) -> Unit,
) {

    private val ctx: Context = ContextThemeWrapper(rawContext, R.style.Theme_Aide_Ime)

    private val slide: SlideOverPanel = SlideOverPanel(ctx, side = PanelSide.End)

    private val descriptionTitle: TextView = TextView(ctx).apply {
        textSize = Typography.responseBody
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
    }
    private val descriptionBody: TextView = TextView(ctx).apply {
        textSize = Typography.queueRow
        setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface_variant))
        setPadding(ctx.dp(Spacing.xl), ctx.dp(Spacing.sm), ctx.dp(Spacing.xl), ctx.dp(Spacing.xl))
    }
    private val descriptionPanel: View = buildPanel(
        header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                descriptionTitle,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(closeButton())
        },
        body = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(descriptionBody)
        },
    )

    private val groupTitle: TextView = TextView(ctx).apply {
        textSize = Typography.responseBody
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
    }
    private val groupList: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val groupPanel: View = buildPanel(
        header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                groupTitle,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(closeButton())
        },
        body = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(groupList)
        },
    )

    private enum class Mode { None, Description, Group }
    private var mode: Mode = Mode.None
    // Identifies which chip/task the panel is currently showing so a second
    // tap on the same source can close it. `null` means the panel is hidden.
    private var sourceKey: String? = null

    val root: View get() = slide.root

    fun setContent(content: View) {
        slide.setContent(content)
    }

    fun showGroup(item: GroupChipItem) {
        val key = "group:${item.group.id}"
        if (slide.isOpen && sourceKey == key) {
            hide()
            return
        }
        groupTitle.text = item.group.name
        groupList.removeAllViews()
        item.members.forEach { task ->
            groupList.addView(memberRow(task.name) {
                hide()
                onMemberTap(task)
            })
        }
        open(Mode.Group, groupPanel, key)
    }

    fun showTaskDescription(task: TaskEntity) {
        showDescription("task-desc:${task.id}", task.name, task.description)
    }

    fun showGroupDescription(item: GroupChipItem) {
        val variants = item.members.joinToString("\n") { "• ${it.name}" }
        val body = item.group.description?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n$variants" }
            ?: variants
        showDescription(
            key = "group-desc:${item.group.id}",
            title = item.group.name,
            body = body,
        )
    }

    private fun showDescription(key: String, title: String, body: String) {
        if (slide.isOpen && sourceKey == key) {
            hide()
            return
        }
        descriptionTitle.text = title
        descriptionBody.text = body.ifBlank { "No description provided." }
        open(Mode.Description, descriptionPanel, key)
    }

    fun hide() {
        slide.close()
        mode = Mode.None
        sourceKey = null
    }

    private fun open(next: Mode, content: View, key: String) {
        if (mode != next) {
            slide.setPanel(content)
            mode = next
        }
        sourceKey = key
        slide.open()
    }

    private fun buildPanel(header: View, body: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.aide_surface_container_high))
        header.setPadding(ctx.dp(Spacing.xl), ctx.dp(Spacing.md), ctx.dp(Spacing.xl), ctx.dp(Spacing.md))
        addView(header)
        addView(
            body,
            // weight=1 gives body a bounded height so the inner ScrollView can actually scroll.
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    private fun closeButton(): View = aideButton(
        ctx = ctx,
        shape = Shapes.chrome,
        iconRes = R.drawable.ic_lc_x,
        contentDesc = "Close",
        onPress = { hide() },
    )

    private fun memberRow(
        label: CharSequence,
        onClick: () -> Unit,
    ): MaterialCardView {
        val tv = TextView(ctx).apply {
            text = label
            textSize = Typography.responseBody
            setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface))
            setPadding(ctx.dp(Spacing.xl), ctx.dp(Spacing.xl), ctx.dp(Spacing.xl), ctx.dp(Spacing.xl))
            setLineSpacing(ctx.dp(Spacing.xxs).toFloat(), 1f)
            isClickable = false
            isFocusable = false
        }
        return MaterialCardView(ctx).apply {
            radius = 0f
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

}
