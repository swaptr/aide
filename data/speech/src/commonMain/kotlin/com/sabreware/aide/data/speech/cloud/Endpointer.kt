package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.speech.VoiceActivityDetector
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Decides when one utterance is over, for an engine that has to send the whole recording at once.
 *
 * A batch transcription endpoint cannot tell us where the user stopped talking; the on-device path
 * asks Silero, but a cloud engine that needed a downloaded Sherpa asset before it could work would be a
 * hidden dependency the "add a key" flow never surfaces. So this rides the same scale-free energy
 * [VoiceActivityDetector] the mic meter already runs, with a longer hangover — Silero's own 0.8 s
 * minimum silence — and Silero's 30 s speech cap so a noisy room that never reads as silent still ends
 * with one bounded upload rather than an open mic.
 *
 * Speech has to be seen before silence can end anything: the leading quiet before the user speaks is
 * not an utterance.
 */
class Endpointer(
    hangoverMs: Int = DEFAULT_HANGOVER_MS,
    private val maxSpeechMs: Long = DEFAULT_MAX_SPEECH_MS,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    /** Where the endpointer stands after one frame. */
    enum class Verdict {
        /** Nobody has spoken yet. */
        Silence,

        /** The first frame of speech. */
        SpeechStart,

        /** Speech continues, or the hangover after it has not yet elapsed. */
        Speech,

        /** The utterance is complete — trailing silence elapsed, or the speech cap was hit. Terminal. */
        Utterance,
    }

    private val detector = VoiceActivityDetector(hangoverMs = hangoverMs)
    private var speechSeen = false
    private var speechMs = 0L

    /** True from [Verdict.SpeechStart] until the utterance ends. */
    val speaking: Boolean get() = speechSeen

    fun accept(frame: FloatArray): Verdict {
        val frameMs = frame.size * MS_PER_SECOND / sampleRate
        val reading = detector.accept(dbfs(frame), frameMs)
        return when {
            !speechSeen && reading.speaking -> {
                speechSeen = true
                speechMs = frameMs.toLong()
                Verdict.SpeechStart
            }
            !speechSeen -> Verdict.Silence
            !reading.speaking -> Verdict.Utterance
            else -> {
                speechMs += frameMs
                if (speechMs >= maxSpeechMs) Verdict.Utterance else Verdict.Speech
            }
        }
    }

    fun reset() {
        detector.reset()
        speechSeen = false
        speechMs = 0L
    }

    /** Frame RMS in dBFS, exactly as the capturers compute it for the meter. */
    private fun dbfs(frame: FloatArray): Float {
        if (frame.isEmpty()) return SILENT_DB
        var sumSq = 0.0
        for (sample in frame) sumSq += (sample * sample).toDouble()
        val rms = sqrt(sumSq / frame.size).toFloat()
        return if (rms > RMS_FLOOR) DB_PER_DECADE * log10(rms) else SILENT_DB
    }

    private companion object {
        const val DEFAULT_HANGOVER_MS = 800
        const val DEFAULT_MAX_SPEECH_MS = 30_000L
        const val SAMPLE_RATE = 16_000
        const val MS_PER_SECOND = 1000
        const val SILENT_DB = -60f
        const val RMS_FLOOR = 1e-6f
        const val DB_PER_DECADE = 20f
    }
}
