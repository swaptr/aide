package com.swaptr.aide.assistant

import android.util.Log
import com.swaptr.aide.data.chat.ChatRepository
import com.swaptr.aide.data.chat.PersistentChatTranscript
import com.swaptr.aide.data.model.ModelGateState
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.model.ResidentModelGuardRegistry
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.prefs.VoiceTurnPolicyMode
import com.swaptr.aide.data.speech.SpeechEngineRepository
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.speech.VoiceLoopState
import com.swaptr.aide.domain.speech.loop.VoicePipeline
import com.swaptr.aide.domain.speech.loop.VoiceTurnPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Process-singleton — survives overlay hide/show so re-summoning resumes the
// same conversation while guards keep models warm under their idle timers.
@Singleton
class AssistantVoiceController @Inject constructor(
    private val voicePipeline: VoicePipeline,
    private val chatRepository: ChatRepository,
    private val speechEngine: SpeechEngineRepository,
    private val prefs: UserPreferencesRepository,
    private val guards: ResidentModelGuardRegistry,
    modelRegistry: ModelRegistryRepository,
) {
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val voiceSession = VoicePipeline.DefaultVoiceSession()

    private val _state = MutableStateFlow<VoiceLoopState>(VoiceLoopState.Idle)
    val state: StateFlow<VoiceLoopState> = _state.asStateFlow()

    val needsModelSetup: StateFlow<Boolean> = modelRegistry.gateStateFlow
        .map { it !is ModelGateState.Ready }
        .stateIn(scope, SharingStarted.Eagerly, true)

    private val _voiceChatId = MutableStateFlow<String?>(null)
    val voiceChatId: StateFlow<String?> = _voiceChatId.asStateFlow()

    private var loopJob: Job? = null

    fun onTapMic() {
        if (loopJob?.isActive == true) stop()
        else start()
    }

    // Idempotent — re-show mid-turn must not restart an active loop.
    fun startIfIdle() {
        if (loopJob?.isActive != true) start()
    }

    // Preload STT + Silero VAD so the first mic tap doesn't pay JNI init latency.
    fun warmUpStt() {
        scope.launch { runCatching { speechEngine.warmUpStt() } }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = VoiceLoopState.Idle
    }

    private fun start() {
        Log.i(TAG, "start() — cancelling prior job=$loopJob")
        loopJob?.cancel()
        loopJob = scope.launch {
            try {
                val chatId = ensureVoiceChat()
                if (chatId == null) {
                    Log.w(TAG, "no model picked — surfacing recoverable error")
                    _state.value = VoiceLoopState.Error(
                        "Pick a default model in the main Aide app first.",
                        recoverable = true,
                    )
                    return@launch
                }
                voiceSession.transcript = PersistentChatTranscript(chatRepository, chatId)
                val policy = resolvePolicy()
                Log.i(TAG, "policy=$policy chatId=$chatId — acquiring guards")
                // Refcount the four roles; 60s idle release lets quick re-summons skip cold reload.
                guards.llm.use {
                    guards.stt.use {
                        guards.vad.use {
                            guards.tts.use {
                                Log.i(TAG, "guards acquired — entering pipeline")
                                voicePipeline.invoke(voiceSession, policy)
                                    .collect { state ->
                                        if (state !is VoiceLoopState.Listening) {
                                            Log.d(TAG, "state=$state")
                                        }
                                        _state.value = state
                                    }
                                Log.i(TAG, "pipeline flow completed normally")
                            }
                        }
                    }
                }
                Log.i(TAG, "guards released")
            } catch (ce: CancellationException) {
                Log.i(TAG, "loop cancelled")
                _state.value = VoiceLoopState.Idle
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "voice loop ended with throwable", t)
                _state.value = VoiceLoopState.Error(
                    t.message ?: "Voice loop failed",
                    recoverable = true,
                )
            }
        }
    }

    private suspend fun resolvePolicy(): VoiceTurnPolicy {
        val mode = prefs.voiceTurnPolicyModeFlow.firstOrNull()
            ?: VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE
        val idleMs = prefs.voiceTurnPolicyIdleMsFlow.firstOrNull() ?: 15_000L
        return when (mode) {
            VoiceTurnPolicyMode.OFF_AFTER_REPLY -> VoiceTurnPolicy.OffAfterReply
            VoiceTurnPolicyMode.KEEP_LISTENING -> VoiceTurnPolicy.KeepListening
            VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE ->
                VoiceTurnPolicy.OffAfterIdleSilence(idleMs)
        }
    }

    // Returns `null` when no model is configured — caller surfaces an error state.
    private suspend fun ensureVoiceChat(): String? {
        _voiceChatId.value?.let { return it }
        // Avoid creating an orphan chat row when there's no active model — the loop
        // can't run without one and the row would just clutter the drawer.
        voicePipeline.resolveActiveModelId() ?: return null
        val chat = chatRepository.createChat(
            title = "Voice chat",
            surface = Surface.VOICE,
        )
        _voiceChatId.value = chat.id
        return chat.id
    }

    fun release() {
        stop()
        scope.cancel()
        runCatching { voiceSession.session?.close() }
        voiceSession.session = null
        _voiceChatId.value = null
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun assistantVoiceController(): AssistantVoiceController
    }

    companion object {
        private const val TAG = "AssistantVoiceCtl"
    }
}
