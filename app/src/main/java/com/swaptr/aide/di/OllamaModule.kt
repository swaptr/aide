package com.swaptr.aide.di

import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.provider.OllamaTagsCache
import com.swaptr.aide.data.provider.ProviderConfigRepository
import com.swaptr.aide.domain.llm.Provider
import com.swaptr.aide.domain.llm.dispatch.ToolDispatcher
import com.swaptr.aide.domain.llm.ollama.OllamaClient
import com.swaptr.aide.domain.llm.ollama.OllamaHttp
import com.swaptr.aide.domain.llm.ollama.OllamaLlmEngine
import com.swaptr.aide.domain.llm.ollama.OllamaManagement
import com.swaptr.aide.domain.llm.ollama.OllamaProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OllamaModule {

    @Provides
    @Singleton
    @OllamaHttp
    fun provideOllamaHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Streaming generations: never time out reads or the whole call.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideOllamaClient(
        @OllamaHttp http: OkHttpClient,
        configRepo: ProviderConfigRepository,
        @ApplicationScope scope: CoroutineScope,
    ): OllamaClient {
        val configState = configRepo.ollamaConfigFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
        return OllamaClient(
            http = http,
            baseUrlProvider = { configState.value?.baseUrl },
            bearerProvider = { configState.value?.authToken },
        )
    }

    @Provides
    @Singleton
    fun provideOllamaManagement(
        client: OllamaClient,
        configRepo: ProviderConfigRepository,
        tagsCache: OllamaTagsCache,
        @ApplicationScope scope: CoroutineScope,
    ): OllamaManagement = OllamaManagement(client, configRepo, tagsCache, scope)

    @Provides
    @Singleton
    @IntoMap
    @ProviderIdKey(ProviderId.OLLAMA)
    fun provideOllamaProvider(
        client: OllamaClient,
        management: OllamaManagement,
        dispatcher: ToolDispatcher,
    ): Provider = OllamaProvider(
        engine = OllamaLlmEngine(client, dispatcher),
        management = management,
    )
}
