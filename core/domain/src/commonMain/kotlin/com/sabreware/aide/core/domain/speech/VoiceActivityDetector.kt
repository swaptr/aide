package com.sabreware.aide.core.domain.speech

import kotlin.math.max

/**
 * Energy voice-activity detector over per-frame RMS in dB.
 *
 * Scale-free by construction: every decision keys off SNR *above a tracked noise floor*, never an
 * absolute loudness — so the same detector works on our capturer's dBFS frames and on any other dB-ish
 * source. The floor drops fast toward quiet frames and creeps up slowly under sustained loudness, so a
 * noisy room is learned in about a second while a long sentence is never swallowed into the floor.
 *
 * Attack/hangover are milliseconds, not frames, because sources deliver at different cadences (32 ms
 * from AudioRecord, ~50 ms from a system recogniser's rms callback).
 */
class VoiceActivityDetector(
    private val onSnrDb: Float = 9f,
    private val offSnrDb: Float = 4.5f,
    private val attackMs: Int = 64,
    private val hangoverMs: Int = 420,
) {
    /** [speaking] gates the UI's motion; [level] is a 0..1 envelope for how far the bars swing. */
    data class Reading(val speaking: Boolean, val level: Float)

    private var floorDb = Float.NaN
    private var speaking = false
    private var aboveMs = 0
    private var belowMs = 0
    private var envelope = 0f

    fun reset() {
        floorDb = Float.NaN
        speaking = false
        aboveMs = 0
        belowMs = 0
        envelope = 0f
    }

    fun accept(db: Float, frameMs: Int): Reading {
        if (floorDb.isNaN()) floorDb = db
        // Cadence-normalised so a 50 ms callback tracks at the same speed as a 32 ms frame.
        val pace = (frameMs / NominalFrameMs).coerceIn(0.25f, 4f)
        val rate = if (db < floorDb) FloorFallRate else FloorRiseRate
        floorDb += (db - floorDb) * (rate * pace).coerceAtMost(1f)

        // Clamping the floor stops a digitally-silent room (-60 dBFS) from turning faint hiss into a
        // huge SNR. On a source whose silence already sits above this, the clamp is inert.
        val snr = db - max(floorDb, MinFloorDb)

        if (speaking) {
            if (snr <= offSnrDb) {
                belowMs += frameMs
                if (belowMs >= hangoverMs) {
                    speaking = false
                    aboveMs = 0
                }
            } else {
                belowMs = 0
            }
        } else {
            if (snr >= onSnrDb) {
                aboveMs += frameMs
                if (aboveMs >= attackMs) {
                    speaking = true
                    belowMs = 0
                }
            } else {
                aboveMs = 0
            }
        }

        val raw = ((snr - offSnrDb) / (FullScaleSnrDb - offSnrDb)).coerceIn(0f, 1f)
        // Fast attack / slow release: the meter jumps onto a syllable but decays through it, which is
        // what makes bars read as a voice rather than as noise.
        val follow = if (raw > envelope) EnvelopeAttack else EnvelopeRelease
        envelope += (raw - envelope) * (follow * pace).coerceAtMost(1f)

        return Reading(speaking, envelope)
    }

    private companion object {
        const val NominalFrameMs = 32f
        const val FloorFallRate = 0.25f
        const val FloorRiseRate = 0.003f
        const val MinFloorDb = -55f
        const val FullScaleSnrDb = 30f
        const val EnvelopeAttack = 0.55f
        const val EnvelopeRelease = 0.16f
    }
}
