package com.swaptr.aide.ime.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.swaptr.aide.R

// DispatchedByParent makes view inert + tags R.id.aide_key so KeyboardTouchHost can
// centralise multi-touch / motion / slide-to-alt / repeat / chord handling.
class AideButton(ctx: Context) : FrameLayout(ctx) {
    var label: AppCompatTextView? = null
        internal set
    var iconView: AppCompatImageView? = null
        internal set
    var superscriptView: AppCompatTextView? = null
        internal set

    var key: Key? = null
        internal set

    private var pillShape: Boolean = false

    internal fun configurePillIfNeeded(isPill: Boolean) {
        pillShape = isPill
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!pillShape || h <= 0) return
        // Pill needs half-height radius; factory doesn't know measured height at build time.
        val r = h / 2f
        val bg = background as? RippleDrawable ?: return
        for (i in 0 until bg.numberOfLayers) {
            (bg.getDrawable(i) as? GradientDrawable)?.cornerRadius = r
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // Visual-only; mutating touch flags would re-enable clicks on DispatchedByParent QWERTY keys.
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    companion object {
        private const val DISABLED_ALPHA = 0.38f

        fun imeIconPx(ctx: Context): Int =
            ctx.resources.getDimensionPixelSize(R.dimen.ime_icon_size)
    }

    // In-place swap so the shift state machine doesn't have to rebuild cached rows.
    fun applyStyle(ctx: Context, style: AideButtonStyle, shape: AideButtonShape = AideButtonShape.Rounded()) {
        val resolved = style.resolve(ctx)
        background = buildBackground(ctx, shape, resolved)
        label?.let {
            it.setTextColor(resolved.fg)
            // Modifier-ish styles bold their label; Standard stays normal.
            val bold = style !is AideButtonStyle.Standard && style !is AideButtonStyle.Ghost
            it.setTypeface(it.typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        iconView?.imageTintList = ColorStateList.valueOf(resolved.fg)
    }
}

fun keyOf(view: View): Key? = view.getTag(R.id.aide_key) as? Key

// `label` exposed so shift-state changes can re-case labels without rebuilding the row.
class Key(
    val view: AideButton,
    val label: AppCompatTextView?,
    val baseChar: String?,
    val onTap: () -> Unit,
    val onRepeat: ((Int) -> Unit)? = null,
    val alts: List<String>? = null,
    val popup: KeyPopup? = null,
    val onAltChosen: ((String) -> Unit)? = null,
    val onCancelBase: (() -> Unit)? = null,
)

object AideButtonMetrics {
    const val PAD_HORIZ_DP: Float = 12f
    const val PAD_VERT_DP: Float = 8f
    const val TEXT_SP: Float = 14f
}

sealed class AideButtonShape {
    object Pill : AideButtonShape()
    data class Rounded(val radiusDp: Float = 8f) : AideButtonShape()
}

sealed class AideButtonStyle {
    object Standard : AideButtonStyle()

    object Tonal : AideButtonStyle()

    object Accent : AideButtonStyle()

    object ModifierActive : AideButtonStyle()

    object Ghost : AideButtonStyle()

    data class Custom(
        val bg: ColorStateList,
        val fg: Int,
        val rippleColor: ColorStateList,
    ) : AideButtonStyle()
}

internal class ResolvedStyle(
    val bg: ColorStateList,
    val fg: Int,
    val ripple: ColorStateList,
)

internal fun AideButtonStyle.resolve(ctx: Context): ResolvedStyle {
    fun csl(@androidx.annotation.ColorRes c: Int) =
        ColorStateList.valueOf(ContextCompat.getColor(ctx, c))
    return when (this) {
        AideButtonStyle.Standard -> ResolvedStyle(
            bg = csl(R.color.aide_surface_container_high),
            fg = ContextCompat.getColor(ctx, R.color.aide_on_surface),
            ripple = csl(R.color.aide_outline_variant),
        )
        AideButtonStyle.Tonal -> ResolvedStyle(
            bg = csl(R.color.aide_secondary_container),
            fg = ContextCompat.getColor(ctx, R.color.aide_on_secondary_container),
            ripple = csl(R.color.aide_outline_variant),
        )
        AideButtonStyle.Accent, AideButtonStyle.ModifierActive -> ResolvedStyle(
            bg = csl(R.color.aide_accent),
            fg = ContextCompat.getColor(ctx, R.color.aide_on_primary),
            ripple = ColorStateList.valueOf(0x33000000),
        )
        AideButtonStyle.Ghost -> ResolvedStyle(
            bg = ColorStateList.valueOf(0x00000000),
            fg = ContextCompat.getColor(ctx, R.color.aide_on_surface),
            ripple = csl(R.color.aide_outline_variant),
        )
        is AideButtonStyle.Custom -> ResolvedStyle(bg, fg, rippleColor)
    }
}

enum class AideButtonTouchMode {
    Native,

    DispatchedByParent,
}

enum class IconPosition { Start, End }

@SuppressLint("UseCompatLoadingForDrawables")
fun aideButton(
    ctx: Context,
    shape: AideButtonShape = AideButtonShape.Rounded(),
    style: AideButtonStyle = AideButtonStyle.Standard,
    widthDp: Float? = null,
    heightDp: Float? = null,
    weight: Float = 0f,
    paddingHorizDp: Float? = null,
    paddingVertDp: Float? = null,
    marginDp: Float = 0f,
    label: CharSequence? = null,
    iconRes: Int? = null,
    iconPosition: IconPosition = IconPosition.Start,
    trailingIconRes: Int? = null,
    secondaryLabel: CharSequence? = null,
    contentDesc: CharSequence? = null,
    labelTextSp: Float? = null,
    touchMode: AideButtonTouchMode = AideButtonTouchMode.Native,
    onPress: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onRepeat: ((Int) -> Unit)? = null,
    alts: List<String>? = null,
    popup: KeyPopup? = null,
    onAltChosen: ((String) -> Unit)? = null,
    onCancelBase: (() -> Unit)? = null,
    baseChar: String? = null,
    haptic: Boolean = true,
    enabled: Boolean = true,
): AideButton {
    require(label != null || iconRes != null) {
        "aideButton requires at least one of label or iconRes"
    }
    require(trailingIconRes == null || iconRes != null) {
        "aideButton: trailingIconRes requires iconRes (used for icon+chevron pills without a label)"
    }
    if (alts != null) {
        require(popup != null && onAltChosen != null) {
            "aideButton: alts requires popup and onAltChosen"
        }
        require(touchMode == AideButtonTouchMode.DispatchedByParent) {
            "aideButton: alts only works with DispatchedByParent"
        }
    }

    val resolved = style.resolve(ctx)
    val isPill = shape is AideButtonShape.Pill

    val container = AideButton(ctx).apply {
        background = buildBackground(ctx, shape, resolved)
        contentDescription = contentDesc ?: label
        isEnabled = enabled
        configurePillIfNeeded(isPill)
    }

    val defaultHeightPx = ctx.resources.getDimensionPixelSize(R.dimen.ime_button_size)
    val hPx = heightDp?.let { ctx.dpToPx(it) } ?: defaultHeightPx
    val wPx = widthDp?.let { ctx.dpToPx(it) }
    val marginPx = ctx.dpToPx(marginDp)

    val lp: ViewGroup.MarginLayoutParams = when {
        weight > 0f -> LinearLayout.LayoutParams(0, hPx, weight)
        wPx != null -> ViewGroup.MarginLayoutParams(wPx, hPx)
        label == null && trailingIconRes == null ->
            ViewGroup.MarginLayoutParams(hPx, hPx)
        else -> ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            hPx,
        )
    }
    if (marginPx > 0) lp.setMargins(marginPx, marginPx, marginPx, marginPx)
    container.layoutParams = lp

    val padH = ctx.dpToPx(paddingHorizDp ?: AideButtonMetrics.PAD_HORIZ_DP)
    @Suppress("UNUSED_VARIABLE")
    val padV = ctx.dpToPx(paddingVertDp ?: AideButtonMetrics.PAD_VERT_DP)

    val imeIconPx = AideButton.imeIconPx(ctx)
    val imeGapPx = ctx.resources.getDimensionPixelSize(R.dimen.ime_gap)

    if (label == null) {
        if (trailingIconRes == null) {
            val icon = AppCompatImageView(ctx).apply {
                setImageResource(iconRes!!)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(resolved.fg)
            }
            container.iconView = icon
            container.addView(
                icon,
                FrameLayout.LayoutParams(imeIconPx, imeIconPx, Gravity.CENTER),
            )
        } else {
            val gap = imeGapPx
            val tint = ColorStateList.valueOf(resolved.fg)
            val leading = AppCompatImageView(ctx).apply {
                setImageResource(iconRes!!)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = tint
            }
            val trailing = AppCompatImageView(ctx).apply {
                setImageResource(trailingIconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = tint
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, 0, padH, 0)
                addView(
                    leading,
                    LinearLayout.LayoutParams(imeIconPx, imeIconPx).apply { marginEnd = gap },
                )
                addView(
                    trailing,
                    LinearLayout.LayoutParams(imeIconPx, imeIconPx),
                )
            }
            container.iconView = leading
            container.addView(
                row,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        }
    } else {
        val tv = AppCompatTextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelTextSp ?: AideButtonMetrics.TEXT_SP)
            setTextColor(resolved.fg)
            setPadding(padH, 0, padH, 0)
            val isBold = style !is AideButtonStyle.Standard && style !is AideButtonStyle.Ghost
            if (isBold) setTypeface(typeface, Typeface.BOLD)

            if (iconRes != null) {
                val ico = ContextCompat.getDrawable(ctx, iconRes)?.mutate()?.apply {
                    setBounds(0, 0, imeIconPx, imeIconPx)
                    setTint(resolved.fg)
                }
                compoundDrawablePadding = imeGapPx
                when (iconPosition) {
                    IconPosition.Start -> setCompoundDrawables(ico, null, null, null)
                    IconPosition.End -> setCompoundDrawables(null, null, ico, null)
                }
            }
        }
        container.label = tv
        container.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
    }

    if (secondaryLabel != null) {
        val sup = AppCompatTextView(ctx).apply {
            text = secondaryLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(ContextCompat.getColor(ctx, R.color.aide_on_surface_variant))
            includeFontPadding = false
            // Tight inset for the small "1/2/3" hint dots on QWERTY top row.
            setPadding(0, ctx.dpToPx(3f), ctx.dpToPx(5f), 0)
        }
        container.superscriptView = sup
        container.addView(
            sup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
    }

    when (touchMode) {
        AideButtonTouchMode.DispatchedByParent -> {
            container.isClickable = false
            container.isFocusable = false
            val key = Key(
                view = container,
                label = container.label,
                baseChar = baseChar,
                onTap = onPress ?: {},
                onRepeat = onRepeat,
                alts = alts,
                popup = popup,
                onAltChosen = onAltChosen,
                onCancelBase = onCancelBase,
            )
            container.key = key
            container.setTag(R.id.aide_key, key)
        }
        AideButtonTouchMode.Native -> {
            container.isClickable = true
            container.isFocusable = true
            container.isHapticFeedbackEnabled = haptic
            onPress?.let { cb ->
                container.setOnClickListener {
                    if (haptic) it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    cb()
                }
            }
            onLongPress?.let { cb ->
                container.setOnLongClickListener {
                    if (haptic) it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    cb()
                    true
                }
            }
        }
    }

    return container
}

private fun buildBackground(
    ctx: Context,
    shape: AideButtonShape,
    style: ResolvedStyle,
): RippleDrawable {
    val radiusPx = when (shape) {
        is AideButtonShape.Rounded -> ctx.dpToPx(shape.radiusDp).toFloat()
        AideButtonShape.Pill -> 0f // recomputed in onSizeChanged
    }
    val fill = GradientDrawable().apply {
        cornerRadius = radiusPx
        color = style.bg
    }
    val mask = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(0xFFFFFFFF.toInt())
    }
    return RippleDrawable(style.ripple, fill, mask)
}

