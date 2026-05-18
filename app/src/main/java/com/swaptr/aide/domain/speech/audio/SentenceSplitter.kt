package com.swaptr.aide.domain.speech.audio

// Early sentence emission overlaps LLM streaming with TTS playback to hide Piper's
// 1-3 s first-audio latency on phone CPU.
object SentenceSplitter {

    private val terminators = charArrayOf('.', '!', '?', ';', '\n')

    fun drainComplete(buffer: StringBuilder): List<String>? {
        if (buffer.isEmpty()) return null
        val out = mutableListOf<String>()
        var cut = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c in terminators) {
                var j = i + 1
                while (j < buffer.length && (buffer[j] in terminators || buffer[j].isWhitespace())) j++
                if (j == buffer.length) {
                    // Tail may still grow; only commit on newline or terminator+whitespace.
                    val sawNewline = (i until j).any { buffer[it] == '\n' }
                    if (sawNewline) {
                        out += buffer.substring(cut, j).trim()
                        cut = j
                    }
                    i = j
                } else {
                    out += buffer.substring(cut, j).trim()
                    cut = j
                    i = j
                }
            } else {
                i++
            }
        }
        if (cut > 0) buffer.delete(0, cut)
        return out.takeIf { it.isNotEmpty() }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    }
}
