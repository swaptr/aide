package com.swaptr.aide.data.model

import android.util.Log
import com.swaptr.aide.data.model.ResidentModelGuard.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Hilt qualifier per guarded role so the four guards can be distinguished at injection. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class LlmGuard
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SttGuard
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class TtsGuard
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class VadGuard

/** App-scope coroutine for fire-and-forget work outside any UI scope. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

// Trim hook runs release on app scope (non-blocking) — centralises memory-pressure routing.
@Singleton
class ResidentModelGuardRegistry @Inject constructor(
    @LlmGuard val llm: ResidentModelGuard,
    @SttGuard val stt: ResidentModelGuard,
    @TtsGuard val tts: ResidentModelGuard,
    @VadGuard val vad: ResidentModelGuard,
    private val policy: TrimPolicy,
    @AppScope private val scope: CoroutineScope,
) {

    fun all(): List<ResidentModelGuard> = listOf(llm, stt, tts, vad)

    fun forRole(role: Role): ResidentModelGuard = when (role) {
        Role.LLM -> llm
        Role.STT -> stt
        Role.TTS -> tts
        Role.VAD -> vad
    }

    // Held models (refCount > 0) never yanked.
    fun dispatchTrim(level: Int) {
        Log.i(TAG, "onTrimMemory level=$level — evaluating ${all().size} guards")
        for (guard in all()) {
            val threshold = policy.thresholdFor(guard.role)
            if (level >= threshold) {
                scope.launch { guard.forceRelease() }
            }
        }
    }

    fun snapshot(): Map<Role, ResidentModelGuard.Snapshot> =
        all().associate { it.role to it.snapshot() }

    companion object {
        private const val TAG = "GuardRegistry"
    }
}
