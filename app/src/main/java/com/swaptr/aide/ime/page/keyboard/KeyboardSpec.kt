package com.swaptr.aide.ime.page.keyboard

internal enum class Layer { LETTERS, SYMBOLS, MORE_SYMBOLS }

internal enum class Shift { OFF, SHIFT, CAPS_LOCK }

internal enum class SubPage { MAIN, CUSTOM_INSTRUCTIONS }

internal object KeyboardSpec {

    /** Two consecutive shift taps within this window engage caps-lock. */
    const val CAPS_DOUBLE_TAP_MS: Long = 400L

    /** After this many backspace repeats, switch from char-delete to word-delete. */
    const val REPEAT_WORDS_AFTER: Int = 20

    const val WORD_LOOKBACK_CHARS: Int = 64

    val LETTER_ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val LETTER_ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val LETTER_ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")
    val LETTER_ROW_1_HINTS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    val SYMBOL_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val SYMBOL_ROW_2 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
    val SYMBOL_ROW_3 = listOf("*", "\"", "'", ":", ";", "!", "?")

    val MORE_ROW_1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")
    val MORE_ROW_2 = listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")
    val MORE_ROW_3 = listOf("%", "©", "®", "™", "✓", "[", "]")

    val LETTER_ALTS: Map<String, List<String>> = mapOf(
        "q" to listOf("1"),
        "w" to listOf("2"),
        "e" to listOf("3", "é", "è", "ê", "ë", "ē"),
        "r" to listOf("4"),
        "t" to listOf("5"),
        "y" to listOf("6", "ÿ"),
        "u" to listOf("7", "ú", "ù", "û", "ü", "ū"),
        "i" to listOf("8", "í", "ì", "î", "ï", "ī"),
        "o" to listOf("9", "ó", "ò", "ô", "ö", "õ", "ō", "œ"),
        "p" to listOf("0"),
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "ā", "æ"),
        "s" to listOf("ß", "ś", "š"),
        "d" to listOf("ð"),
        "n" to listOf("ñ", "ń"),
        "c" to listOf("ç", "ć", "č"),
        "z" to listOf("ž", "ź", "ż"),
    )

    val SYMBOL_ALTS: Map<String, List<String>> = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼", "⅛"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴", "⅘"),
        "5" to listOf("⁵", "⅝"),
        "6" to listOf("⁶"),
        "7" to listOf("⁷", "⅞"),
        "8" to listOf("⁸"),
        "9" to listOf("⁹"),
        "0" to listOf("⁰", "ⁿ", "∅"),
        "-" to listOf("–", "—", "·"),
    )

    val PUNCT_ALTS: Map<String, List<String>> = mapOf(
        "," to listOf(";", ":", "'", "\""),
        "." to listOf("…", "?", "!", "·"),
    )

    fun altsFor(layer: Layer, ch: String): List<String>? {
        val table = when (layer) {
            Layer.LETTERS -> LETTER_ALTS
            Layer.SYMBOLS -> SYMBOL_ALTS
            Layer.MORE_SYMBOLS -> emptyMap()
        }
        return table[ch] ?: PUNCT_ALTS[ch]
    }
}
