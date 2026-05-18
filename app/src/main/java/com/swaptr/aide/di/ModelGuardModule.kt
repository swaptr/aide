package com.swaptr.aide.di

import com.swaptr.aide.data.model.AppScope
import com.swaptr.aide.data.model.LlmEngineRepository
import com.swaptr.aide.data.model.LlmGuard
import com.swaptr.aide.data.model.ResidentModelGuard
import com.swaptr.aide.data.model.SttGuard
import com.swaptr.aide.data.model.TrimPolicy
import com.swaptr.aide.data.model.TtsGuard
import com.swaptr.aide.data.model.VadGuard
import com.swaptr.aide.domain.speech.sherpa.SherpaSttEngine
import com.swaptr.aide.domain.speech.sherpa.SherpaTtsEngine
import com.swaptr.aide.domain.speech.sherpa.SherpaVadEngine
import com.swaptr.aide.domain.speech.system.SystemTtsEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

// STT release: only Sherpa needs close (System recogniser is per-call).
// TTS release: System backend caches a TextToSpeech instance worth shutting down.
@Module
@InstallIn(SingletonComponent::class)
object ModelGuardModule {

    @Provides @Singleton @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun provideTrimPolicy(): TrimPolicy = TrimPolicy()

    @Provides @Singleton @LlmGuard
    fun provideLlmGuard(
        @AppScope scope: CoroutineScope,
        repo: LlmEngineRepository,
    ): ResidentModelGuard = ResidentModelGuard(
        role = ResidentModelGuard.Role.LLM,
        idleMs = ResidentModelGuard.DEFAULT_IDLE_MS,
        scope = scope,
        release = { repo.unload() },
    )

    @Provides @Singleton @SttGuard
    fun provideSttGuard(
        @AppScope scope: CoroutineScope,
        sherpa: SherpaSttEngine,
    ): ResidentModelGuard = ResidentModelGuard(
        role = ResidentModelGuard.Role.STT,
        idleMs = ResidentModelGuard.DEFAULT_IDLE_MS,
        scope = scope,
        release = { sherpa.close() },
    )

    @Provides @Singleton @TtsGuard
    fun provideTtsGuard(
        @AppScope scope: CoroutineScope,
        sherpa: SherpaTtsEngine,
        system: SystemTtsEngine,
    ): ResidentModelGuard = ResidentModelGuard(
        role = ResidentModelGuard.Role.TTS,
        idleMs = ResidentModelGuard.DEFAULT_IDLE_MS,
        scope = scope,
        release = {
            runCatching { sherpa.close() }
            runCatching { system.close() }
        },
    )

    @Provides @Singleton @VadGuard
    fun provideVadGuard(
        @AppScope scope: CoroutineScope,
        sherpa: SherpaVadEngine,
    ): ResidentModelGuard = ResidentModelGuard(
        role = ResidentModelGuard.Role.VAD,
        idleMs = ResidentModelGuard.DEFAULT_IDLE_MS,
        scope = scope,
        release = { sherpa.close() },
    )
}
