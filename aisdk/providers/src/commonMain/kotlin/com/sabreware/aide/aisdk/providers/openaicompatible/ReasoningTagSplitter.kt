package com.sabreware.aide.aisdk.providers.openaicompatible

/**
 * Pulls inline reasoning tags out of the text channel, tolerant of tags split across chunk boundaries.
 *
 * Some OpenAI-compatible servers — Groq in raw mode, self-hosted DeepSeek-R1 distills, some Fireworks
 * deployments — embed reasoning as `<think>…</think>` inside `content` rather than sending it on
 * `reasoning_content`. Without this it renders to the user as literal `<think>` text.
 *
 * Moved from AIDE, where it existed for a worse reason: Koog drops `reasoning_content` entirely on the
 * Chat Completions path, so tag-splitting was the ONLY reasoning channel that worked. Here it is what it
 * should be — an opt-in for servers that genuinely inline their reasoning, off by default, because the
 * native channel is handled properly.
 *
 * NOT thread-safe: one instance per stream. [push] per content delta, [flush] once at the end. A partial
 * tag at a chunk boundary (content ending `…<thi`) is held back until the next delta resolves it, so
 * text is never emitted that might turn out to be the start of a tag.
 */
public class ReasoningTagSplitter(
    private val openTags: List<String> = DEFAULT_OPEN,
    private val closeTags: List<String> = DEFAULT_CLOSE,
) {

    public data class Out(val text: String, val thinking: String)

    private val buf = StringBuilder()
    private var inside = false

    public fun push(delta: String): Out {
        if (delta.isEmpty()) return EMPTY
        buf.append(delta)
        val text = StringBuilder()
        val think = StringBuilder()
        while (buf.isNotEmpty()) {
            val tags = if (inside) closeTags else openTags
            val hit = firstTag(buf, tags)
            if (hit == null) {
                // No complete tag yet: emit everything except a suffix that could still become one.
                val keep = partialTailLen(buf, tags)
                val emitTo = buf.length - keep
                if (emitTo > 0) {
                    (if (inside) think else text).append(buf, 0, emitTo)
                    buf.deleteRange(0, emitTo)
                }
                break
            }
            if (hit.index > 0) (if (inside) think else text).append(buf, 0, hit.index)
            buf.deleteRange(0, hit.index + hit.length)
            inside = !inside
        }
        return Out(text.toString(), think.toString())
    }

    /** At end of stream: an unterminated block becomes thinking, anything else becomes text. */
    public fun flush(): Out {
        if (buf.isEmpty()) return EMPTY
        val rem = buf.toString()
        buf.setLength(0)
        return if (inside) Out("", rem) else Out(rem, "")
    }

    private data class Hit(val index: Int, val length: Int)

    private fun firstTag(s: CharSequence, tags: List<String>): Hit? {
        var bestIdx = -1
        var bestLen = 0
        for (tag in tags) {
            val idx = indexOf(s, tag)
            if (idx >= 0 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx
                bestLen = tag.length
            }
        }
        return if (bestIdx >= 0) Hit(bestIdx, bestLen) else null
    }

    /** Longest suffix of [s] that is a proper prefix of any tag — a tag possibly split across chunks. */
    private fun partialTailLen(s: CharSequence, tags: List<String>): Int {
        var best = 0
        for (tag in tags) {
            var k = minOf(tag.length - 1, s.length)
            while (k >= 1) {
                if (suffixIsPrefix(s, k, tag)) {
                    if (k > best) best = k
                    break
                }
                k--
            }
        }
        return best
    }

    private fun suffixIsPrefix(s: CharSequence, k: Int, tag: String): Boolean {
        val start = s.length - k
        for (i in 0 until k) if (s[start + i] != tag[i]) return false
        return true
    }

    private fun indexOf(s: CharSequence, sub: String): Int {
        if (sub.isEmpty()) return 0
        if (sub.length > s.length) return -1
        val first = sub[0]
        outer@ for (i in 0..(s.length - sub.length)) {
            if (s[i] != first) continue
            for (j in 1 until sub.length) if (s[i + j] != sub[j]) continue@outer
            return i
        }
        return -1
    }

    public companion object {
        public val DEFAULT_OPEN: List<String> = listOf("<think>", "<thinking>")
        public val DEFAULT_CLOSE: List<String> = listOf("</think>", "</thinking>")
        private val EMPTY = Out("", "")
    }
}
