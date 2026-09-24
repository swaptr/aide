package com.sabreware.aide.app.data.model

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.data.llm.EngineLoadPolicy

/**
 * The Android half of loading a model: the two things a phone needs and a desktop does not.
 *
 * 1. **Pre-load memory trim** — give lmkd room before 3-4 GB of weights land in RAM. A Pixel 6a (6 GB) was
 *    observed cascade-killing aide mid-load; `AideApp.onTrimMemory` dispatches to the ResidencyManager,
 *    which drops unheld resident state.
 * 2. **GPU → CPU fallback** — GPU init can fail invisibly on older Mali drivers until first inference, so the
 *    fallback happens at load time; users never get a half-initialised engine.
 *
 * Both apply only to a locally-resident model; a network engine takes the direct path.
 */
class AndroidEngineLoadPolicy(private val appContext: Context) : EngineLoadPolicy {

    override suspend fun load(engine: LlmEngine, spec: ChatModelSpec, config: ChatGenerationConfig) {
        runCatching {
            // Intentional manual trim trigger so AideApp.onTrimMemory → ResidencyManager drops unheld
            // resident state before the weights land. The level constants are deprecated on API 35 (no
            // non-deprecated replacement for a broadcast trim), so suppress narrowly.
            @Suppress("DEPRECATION")
            (appContext.applicationContext as? Application)
                ?.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
        // Deliberate, and narrow: the trim above only DROPS references — the native allocator cannot reuse
        // that heap until the collector actually runs, and the next line asks for hundreds of megabytes of
        // it. This is the one place in the app where suggesting a collection is the point.
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        try {
            engine.load(spec, config)
        } catch (t: Throwable) {
            if (ProviderCatalog.of(spec.provider).local && config.backend == ModelBackend.GPU) {
                Log.w(TAG, "GPU load failed for ${spec.id}, falling back to CPU", t)
                engine.load(spec, config.copy(backend = ModelBackend.CPU))
            } else throw t
        }
    }

    private companion object {
        const val TAG = "AideEngine"
    }
}
