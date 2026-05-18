package com.swaptr.aide.di

import com.swaptr.aide.data.search.providers.BraveWebSearchProvider
import com.swaptr.aide.data.search.providers.DuckDuckGoWebSearchProvider
import com.swaptr.aide.data.search.providers.OllamaWebSearchProvider
import com.swaptr.aide.data.search.providers.TavilyWebSearchProvider
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.search.WebSearchProviderId
import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@MapKey
annotation class WebSearchProviderIdKey(val value: WebSearchProviderId)

@Module
@InstallIn(SingletonComponent::class)
abstract class WebSearchModule {

    @Binds
    @IntoMap
    @WebSearchProviderIdKey(WebSearchProviderId.DUCKDUCKGO)
    abstract fun bindDuckDuckGo(impl: DuckDuckGoWebSearchProvider): WebSearchProvider

    @Binds
    @IntoMap
    @WebSearchProviderIdKey(WebSearchProviderId.OLLAMA)
    abstract fun bindOllama(impl: OllamaWebSearchProvider): WebSearchProvider

    @Binds
    @IntoMap
    @WebSearchProviderIdKey(WebSearchProviderId.BRAVE)
    abstract fun bindBrave(impl: BraveWebSearchProvider): WebSearchProvider

    @Binds
    @IntoMap
    @WebSearchProviderIdKey(WebSearchProviderId.TAVILY)
    abstract fun bindTavily(impl: TavilyWebSearchProvider): WebSearchProvider
}
