package com.swaptr.aide.di

import android.content.Context
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.chat.AideDatabase
import com.swaptr.aide.data.chat.ChatDao
import com.swaptr.aide.data.chat.ChatRepository
import com.swaptr.aide.data.download.DownloadController
import com.swaptr.aide.data.download.DownloadEngine
import com.swaptr.aide.data.search.SearchHttp
import com.swaptr.aide.data.storage.ModelStorage
import com.swaptr.aide.data.task.TaskDao
import com.swaptr.aide.data.task.TaskGroupDao
import com.swaptr.aide.data.task.TaskRepository
import com.swaptr.aide.domain.llm.LiteRtLmEngine
import com.swaptr.aide.domain.llm.LocalProvider
import com.swaptr.aide.domain.llm.Provider
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@MapKey
annotation class ProviderIdKey(val value: ProviderId)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideModelStorage(@ApplicationContext context: Context): ModelStorage =
        ModelStorage(context)

    @Provides
    @Singleton
    fun provideDownloadEngine(storage: ModelStorage): DownloadEngine =
        DownloadEngine(storage)

    @Provides
    @Singleton
    fun provideDownloadController(
        @ApplicationContext context: Context,
        sources: Map<String, @JvmSuppressWildcards com.swaptr.aide.data.download.AssetSource>,
    ): DownloadController = DownloadController(context, sources)

    @Provides
    @Singleton
    @IntoMap
    @ProviderIdKey(ProviderId.LOCAL)
    fun provideLocalProvider(
        @ApplicationContext context: Context,
        storage: ModelStorage,
    ): Provider = LocalProvider(engine = LiteRtLmEngine(context, storage))

    @Provides
    @Singleton
    fun provideAideDatabase(@ApplicationContext context: Context): AideDatabase =
        AideDatabase.build(context)

    @Provides
    fun provideChatDao(db: AideDatabase): ChatDao = db.chatDao()

    @Provides
    @Singleton
    fun provideChatRepository(dao: ChatDao): ChatRepository = ChatRepository(dao)

    @Provides
    fun provideTaskDao(db: AideDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideTaskGroupDao(db: AideDatabase): TaskGroupDao = db.taskGroupDao()

    @Provides
    @Singleton
    fun provideTaskRepository(
        taskDao: TaskDao,
        groupDao: TaskGroupDao,
    ): TaskRepository = TaskRepository(taskDao, groupDao)

    // Isolated OkHttp instance so a slow search request can't block model downloads
    // (which need much longer timeouts), or vice versa.
    @Provides
    @Singleton
    @SearchHttp
    fun provideSearchHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
