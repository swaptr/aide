package com.swaptr.aide.di

import com.swaptr.aide.data.download.AssetSource
import com.swaptr.aide.data.download.ModelAssetSource
import com.swaptr.aide.data.download.SpeechAssetSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
abstract class AssetSourcesModule {

    @Binds
    @IntoMap
    @StringKey(ModelAssetSource.KIND)
    abstract fun bindModelSource(impl: ModelAssetSource): AssetSource

    @Binds
    @IntoMap
    @StringKey(SpeechAssetSource.KIND)
    abstract fun bindSpeechSource(impl: SpeechAssetSource): AssetSource
}
