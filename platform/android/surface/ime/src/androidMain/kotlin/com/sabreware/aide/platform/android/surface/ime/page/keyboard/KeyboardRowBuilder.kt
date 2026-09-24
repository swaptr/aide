package com.sabreware.aide.platform.android.surface.ime.page.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.sabreware.aide.platform.android.surface.ime.R
import com.sabreware.aide.platform.android.surface.ime.theme.Shapes
import com.sabreware.aide.platform.android.surface.ime.widget.AideButton
import com.sabreware.aide.platform.android.surface.ime.widget.Key

internal class KeyboardRowBuilder(
    private val ctx: Context,
    private val factory: KeyFactory,
    private val callbacks: KeyboardCallbacks,
    // Adds a dedicated digit row atop the LETTERS layer; the qwerty row then drops its digit hints.
    private val numberRow: Boolean,
) {

    private val rowsCache: MutableMap<Layer, List<View>> = mutableMapOf()
    private val letterKeys: MutableList<Key> = mutableListOf()
    private val actionKeys: MutableList<Key> = mutableListOf()
    private var letterShiftKey: Key? = null

    fun cachedRows(layer: Layer): List<View> = rowsCache.getOrPut(layer) {
        when (layer) {
            Layer.LETTERS -> letterRows()
            Layer.SYMBOLS -> symbolRows()
            Layer.MORE_SYMBOLS -> moreSymbolRows()
        }
    }

    /** Refresh the action (enter) keys for the current EditorInfo, in place — a field focus needn't
     *  rebuild the keyboard, since nothing else depends on the field. */
    fun applyEditorInfo() {
        for (k in actionKeys) factory.applyAction(k.view)
    }

    fun applyShift() {
        letterShiftKey?.let {
            it.view.applyStyle(ctx, factory.shiftStyle(), Shapes.key)
            it.view.contentDescription = factory.shiftContentDesc()
        }
        val upper = callbacks.shift() != Shift.OFF
        for (k in letterKeys) {
            val base = k.baseChar ?: continue
            k.label?.text = if (upper) base.uppercase() else base
        }
    }

    private fun letterRows(): List<View> = buildList {
        if (numberRow) {
            add(
                row {
                    KeyboardSpec.LETTER_ROW_1_HINTS.forEach {
                        addView(factory.charKey(it, weight = 1f))
                    }
                },
            )
        }
        add(
            row {
                KeyboardSpec.LETTER_ROW_1.forEachIndexed { i, ch ->
                    addView(
                        trackChar(
                            factory.charKey(
                                ch = ch,
                                weight = 1f,
                                // Digit hints are redundant once the dedicated number row is shown.
                                superscript = if (numberRow) null else KeyboardSpec.LETTER_ROW_1_HINTS[i],
                            ),
                        ),
                    )
                }
            },
        )
        add(charRow(KeyboardSpec.LETTER_ROW_2, sidePadWeight = 0.5f))
        add(
            row {
                addView(trackShift(factory.shiftKey(weight = 1.5f)))
                KeyboardSpec.LETTER_ROW_3.forEach {
                    addView(trackChar(factory.charKey(it, weight = 1f)))
                }
                addView(factory.backspaceKey(weight = 1.5f))
            },
        )
        add(bottomRow(layerSwitchLabel = "?123", showAbcIcon = false))
    }

    private fun symbolRows(): List<View> = listOf(
        charRow(KeyboardSpec.SYMBOL_ROW_1),
        charRow(KeyboardSpec.SYMBOL_ROW_2),
        row {
            addView(
                factory.modifierKey(
                    weight = 1.5f,
                    label = "=\\<",
                    onPress = { callbacks.onLayerChange(next = Layer.MORE_SYMBOLS) },
                ),
            )
            KeyboardSpec.SYMBOL_ROW_3.forEach { addView(factory.charKey(it, weight = 1f)) }
            addView(factory.backspaceKey(weight = 1.5f))
        },
        bottomRow(layerSwitchLabel = "ABC", showAbcIcon = true),
    )

    private fun moreSymbolRows(): List<View> = listOf(
        charRow(KeyboardSpec.MORE_ROW_1),
        charRow(KeyboardSpec.MORE_ROW_2),
        row {
            addView(
                factory.modifierKey(
                    weight = 1.5f,
                    label = "?123",
                    onPress = { callbacks.onLayerChange(next = Layer.SYMBOLS) },
                ),
            )
            KeyboardSpec.MORE_ROW_3.forEach { addView(factory.charKey(it, weight = 1f)) }
            addView(factory.backspaceKey(weight = 1.5f))
        },
        bottomRow(layerSwitchLabel = "ABC", showAbcIcon = true),
    )

    private fun bottomRow(layerSwitchLabel: String, showAbcIcon: Boolean): View = row {
        addView(
            if (showAbcIcon) {
                factory.modifierKey(
                    weight = 1.5f,
                    iconRes = R.drawable.ic_lc_case_sensitive,
                    contentDesc = "Letters",
                    onPress = { callbacks.onLayerChange(next = Layer.LETTERS) },
                )
            } else {
                factory.modifierKey(
                    weight = 1.5f,
                    label = layerSwitchLabel,
                    onPress = { callbacks.onLayerChange(next = Layer.SYMBOLS) },
                )
            },
        )
        addView(factory.charKey(",", weight = 1f))
        addView(factory.emojiKey(weight = 1f))
        addView(factory.spaceKey(weight = 4f))
        addView(factory.charKey(".", weight = 1f))
        addView(trackAction(factory.actionKey(weight = 1.5f)))
    }

    private fun charRow(chars: List<String>, sidePadWeight: Float = 0f): View = row {
        if (sidePadWeight > 0f) addView(factory.spacerView(sidePadWeight))
        // trackChar is no-op on non-letter layers; letter rows 1/3 call it at site.
        chars.forEach { addView(trackChar(factory.charKey(it, weight = 1f))) }
        if (sidePadWeight > 0f) addView(factory.spacerView(sidePadWeight))
    }

    private fun row(build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            build()
        }

    private fun trackChar(view: AideButton): View {
        if (callbacks.layer() == Layer.LETTERS) view.key?.let { letterKeys.add(it) }
        return view
    }

    private fun trackShift(view: AideButton): View {
        letterShiftKey = view.key
        return view
    }

    private fun trackAction(view: AideButton): View {
        view.key?.let { actionKeys.add(it) }
        return view
    }
}
