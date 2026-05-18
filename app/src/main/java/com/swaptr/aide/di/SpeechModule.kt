package com.swaptr.aide.di

import android.content.Context
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.speech.SpeechAssetStorage
import com.swaptr.aide.domain.speech.SpeechProvider
import com.swaptr.aide.domain.speech.SpeechProviderId
import com.swaptr.aide.domain.speech.sherpa.SherpaSpeechProvider
import com.swaptr.aide.domain.speech.sherpa.SherpaSttEngine
import com.swaptr.aide.domain.speech.sherpa.SherpaTtsEngine
import com.swaptr.aide.domain.speech.sherpa.SherpaVadEngine
import com.swaptr.aide.domain.speech.system.SystemSpeechProvider
import com.swaptr.aide.domain.speech.system.SystemSttEngine
import com.swaptr.aide.domain.speech.system.SystemTtsEngine
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

@MapKey
annotation class SpeechProviderIdKey(val value: SpeechProviderId)

@Module
@InstallIn(SingletonComponent::class)
object SpeechModule {

    @Provides
    @Singleton
    @IntoMap
    @SpeechProviderIdKey(SpeechProviderId.ANDROID_SYSTEM)
    fun provideSystemSpeechProvider(
        @ApplicationContext context: Context,
        stt: SystemSttEngine,
        tts: SystemTtsEngine,
    ): SpeechProvider = SystemSpeechProvider(context, stt, tts)

    // Always bound; JNI handles aren't allocated until a consumer calls load().
    // Conditional binding would require reading DataStore before Hilt finishes wiring.
    @Provides
    @Singleton
    @IntoMap
    @SpeechProviderIdKey(SpeechProviderId.SHERPA_ONNX)
    fun provideSherpaSpeechProvider(
        stt: SherpaSttEngine,
        tts: SherpaTtsEngine,
        vad: SherpaVadEngine,
        storage: SpeechAssetStorage,
        prefs: UserPreferencesRepository,
    ): SpeechProvider = SherpaSpeechProvider(stt, tts, vad, storage, prefs)
}
