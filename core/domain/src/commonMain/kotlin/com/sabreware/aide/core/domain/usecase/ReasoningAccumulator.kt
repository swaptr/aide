package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.ProviderPayload
import com.sabreware.aide.core.domain.chat.ProviderPayloadKeys
import com.sabreware.aide.core.domain.chat.findString
import kotlin.time.Clock

/**
 * The reasoning blocks of one assistant turn, accumulated as they stream.
 *
 * **Why this is one part per block and never one concatenation.** A provider's thinking signature signs
 * exactly the text of the block it was issued for. Merging two blocks under the second one's signature
 * therefore produces a turn that fails verification the moment the history is replayed — which is every
 * session rebind, not a rare case. Anthropic rejects the replayed turn outright and Gemini answers 400.
 *
 * The bug is invisible within a single turn, which is why it survived a test suite that checked two
 * tool rounds: the failure needs a second *turn* after a reload to appear at all.
 *
 * Extracted from `SendChatMessageUseCase` because it is a state machine with five interacting variables
 * and three entry points, and inline it was five locals that any nearby edit could desynchronise.
 */
internal class ReasoningAccumulator(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {

    private val closed = mutableListOf<AidePart.Thinking>()
    private val open = StringBuilder()
    private var totalMs: Long = 0L
    private var openedAt: Long = 0L

    /** Total reasoning time so far, which is what the UI shows beside the panel. */
    var durationMs: Long = 0L
        private set

    /** Whether a block is currently accreting, so a caller knows there is something to close. */
    val hasOpenBlock: Boolean get() = openedAt != 0L

    /**
     * The whole turn's reasoning, for display.
     *
     * The UI joins the persisted blocks exactly this way, so a block closing mid-stream does not make
     * the panel jump back to the start.
     */
    fun displayText(): String = (closed.map { it.text } + open.toString())
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

    /** Appends streamed reasoning text to the open block, opening one if none is. */
    fun append(text: String) {
        if (text.isEmpty()) return
        if (openedAt == 0L) openedAt = now()
        open.append(text)
        durationMs = totalMs + (now() - openedAt)
    }

    /**
     * Applies a payload that arrived with a reasoning delta.
     *
     * A payload CLOSES the block it signs — it is not a later patch — because the pairing of text to
     * signature is the thing being persisted, and a signature attached to a block that kept growing
     * afterwards signs a prefix of what gets replayed.
     *
     * A safety-redacted block is the exception: it arrives complete and carries no text of its own, so
     * it must not adopt the open block's. A redacted payload stamped onto a plain thought is rejected by
     * the vendor, which verifies the replayed sequence as a whole.
     */
    fun applyPayload(payload: ProviderPayload) {
        if (payload.findString(ProviderPayloadKeys.REDACTED) != null) {
            closeBlock()
            closed += AidePart.Thinking(text = "", durationMs = 0L, providerMetadata = payload)
        } else {
            closeBlock(payload)
        }
    }

    /** Closes the open block, attributing [payload] to it. */
    fun closeBlock(payload: ProviderPayload? = null) {
        if (open.isEmpty() && payload == null) {
            openedAt = 0L
            return
        }
        val elapsed = if (openedAt == 0L) 0L else now() - openedAt
        totalMs += elapsed
        closed += AidePart.Thinking(
            text = open.toString(),
            durationMs = elapsed,
            providerMetadata = payload,
        )
        open.clear()
        openedAt = 0L
        durationMs = totalMs
    }

    /**
     * Every block, including the one still accreting.
     *
     * The open block is included so a persist mid-stream keeps its text rather than dropping it — a
     * crash between two blocks would otherwise lose the reasoning the user was reading.
     */
    fun parts(): List<AidePart.Thinking> = buildList {
        addAll(closed)
        if (open.isNotEmpty()) {
            add(
                AidePart.Thinking(
                    text = open.toString(),
                    durationMs = if (openedAt == 0L) 0L else now() - openedAt,
                ),
            )
        }
    }
}
