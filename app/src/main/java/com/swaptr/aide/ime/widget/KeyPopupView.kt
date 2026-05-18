package com.swaptr.aide.ime.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import com.swaptr.aide.R
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.Typography
import kotlin.math.ceil
import kotlin.math.min

// Selection/animation logic preserved verbatim from the reference app so touch feel matches.
class KeyPopupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var _options: List<String> = emptyList()
    private var _selectedIndex: Int = 0
    private var _showing: Boolean = false

    val options: List<String> get() = _options
    val selectedIndex: Int get() = _selectedIndex
    val isShowing: Boolean get() = _showing

    private val maxColumns = Sizes.popupMaxColumns

    var cols: Int = 0; private set
    var rows: Int = 0; private set

    val cellWidth = context.resources.getDimension(R.dimen.aide_popup_cell_width)
    val cellHeight = context.resources.getDimension(R.dimen.aide_popup_cell_height)
    private val cornerRadius = context.resources.getDimension(R.dimen.aide_popup_corner_radius)
    private val textSize = context.resources.getDimension(R.dimen.aide_popup_text_size)
    private val selectedTextSize = textSize * Typography.popupSelectedFactor
    private val density = context.resources.displayMetrics.density

    private val highlightRadius = (min(cellWidth, cellHeight) / 2f) - 2f * density

    private var animHighlightCx = 0f
    private var animHighlightCy = 0f
    private var animHighlightScale = 1f
    private var animTextSizeFactor = 1f
    private var prevSelectedIndex = -1
    private var highlightAnimator: ValueAnimator? = null
    private var scaleAnimator: ValueAnimator? = null
    private var textSizeAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.aide_popup_background)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.aide_popup_highlight)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.aide_on_surface)
        this.textSize = this@KeyPopupView.textSize
        textAlign = Paint.Align.CENTER
    }
    private val selectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.aide_popup_highlight_text)
        this.textSize = this@KeyPopupView.selectedTextSize
        textAlign = Paint.Align.CENTER
    }

    private val bgRect = RectF()

    val popupWidth: Float get() = cols * cellWidth
    val popupHeight: Float get() = rows * cellHeight

    fun show(options: List<String>, selectedIndex: Int) {
        _options = options
        cols = min(options.size, maxColumns)
        rows = if (cols > 0) ceil(options.size.toFloat() / cols).toInt() else 0
        _selectedIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        _showing = true
        visibility = VISIBLE

        val (cx, cy) = cellCenter(_selectedIndex)
        animHighlightCx = cx
        animHighlightCy = cy
        animHighlightScale = 1f
        animTextSizeFactor = 1f
        prevSelectedIndex = _selectedIndex

        requestLayout()
        invalidate()
    }

    fun updateSelection(index: Int) {
        val newIndex = index.coerceIn(0, (_options.size - 1).coerceAtLeast(0))
        if (newIndex != _selectedIndex) {
            _selectedIndex = newIndex
            animateToSelection(newIndex)
        }
    }

    fun hide() {
        _showing = false
        visibility = GONE
        highlightAnimator?.cancel()
        scaleAnimator?.cancel()
        textSizeAnimator?.cancel()
    }

    private fun cellCenter(index: Int): Pair<Float, Float> {
        val (viewRow, col) = indexToGrid(index)
        val offsetX = rowOffsetX(viewRow)
        val cx = offsetX + col * cellWidth + cellWidth / 2f
        val cy = viewRow * cellHeight + cellHeight / 2f
        return cx to cy
    }

    private fun animateToSelection(index: Int) {
        val (targetCx, targetCy) = cellCenter(index)
        prevSelectedIndex = index

        highlightAnimator?.cancel()
        val startCx = animHighlightCx
        val startCy = animHighlightCy
        highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                animHighlightCx = startCx + (targetCx - startCx) * t
                animHighlightCy = startCy + (targetCy - startCy) * t
                invalidate()
            }
            start()
        }

        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(1.15f, 1f).apply {
            duration = 150
            interpolator = OvershootInterpolator(2f)
            addUpdateListener { anim ->
                animHighlightScale = anim.animatedValue as Float
                invalidate()
            }
            start()
        }

        textSizeAnimator?.cancel()
        animTextSizeFactor = 0f
        textSizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120
            addUpdateListener { anim ->
                animTextSizeFactor = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // Bottom row (rows-1) holds primary options closest to the held key; upper rows = overflow.
    private fun indexToGrid(index: Int): Pair<Int, Int> {
        val rowFromBottom = index / cols
        val col = index % cols
        val viewRow = rows - 1 - rowFromBottom
        return viewRow to col
    }

    private fun itemsInViewRow(viewRow: Int): Int {
        val rowFromBottom = rows - 1 - viewRow
        val startIdx = rowFromBottom * cols
        return min(cols, _options.size - startIdx)
    }

    private fun rowOffsetX(viewRow: Int): Float {
        val itemCount = itemsInViewRow(viewRow)
        return (cols - itemCount) * cellWidth / 2f
    }

    fun gridToIndex(viewRow: Int, col: Int): Int {
        val clampedRow = viewRow.coerceIn(0, rows - 1)
        val rowFromBottom = rows - 1 - clampedRow
        val startIdx = rowFromBottom * cols
        val itemCount = min(cols, _options.size - startIdx)
        val clampedCol = col.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        val index = startIdx + clampedCol
        return if (index < _options.size) index else _options.size - 1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (_options.isEmpty()) {
            setMeasuredDimension(0, 0)
            return
        }
        setMeasuredDimension(popupWidth.toInt(), popupHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (_options.isEmpty()) return

        val w = popupWidth
        val h = popupHeight

        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        val animRadius = highlightRadius * animHighlightScale
        canvas.drawCircle(animHighlightCx, animHighlightCy, animRadius, highlightPaint)

        for (i in _options.indices) {
            val (cx, cy) = cellCenter(i)
            if (i == _selectedIndex) {
                val animSize = textSize + (selectedTextSize - textSize) * animTextSizeFactor
                selectedTextPaint.textSize = animSize
                val textY = cy - (selectedTextPaint.descent() + selectedTextPaint.ascent()) / 2f
                canvas.drawText(_options[i], cx, textY, selectedTextPaint)
            } else {
                textPaint.textSize = textSize
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(_options[i], cx, textY, textPaint)
            }
        }
    }
}
