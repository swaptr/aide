package com.swaptr.aide

import android.app.Application
import com.swaptr.aide.data.catalog.ModelCatalog
import com.swaptr.aide.data.model.ResidentModelGuardRegistry
import com.swaptr.aide.data.speech.ActiveSpeechBootstrap
import com.swaptr.aide.data.task.TaskRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AideApp : Application() {

    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var activeSpeechBootstrap: ActiveSpeechBootstrap
    @Inject lateinit var modelGuards: ResidentModelGuardRegistry

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Must precede any Hilt singleton that reads ModelCatalog (download repos,
        // registry) — they're constructed lazily on first injection.
        ModelCatalog.init(applicationContext)
        appScope.launch { taskRepository.seedBuiltInsIfMissing() }
        activeSpeechBootstrap.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        modelGuards.dispatchTrim(level)
    }
}
