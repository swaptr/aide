package com.sabreware.aide.data.llm

import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.model.ChatModelSpec

/**
 * How a platform puts a model into an engine. The router around it ([LlmEngineRepositoryImpl]) is identical
 * everywhere; what differs is what has to happen *around* the load — Android trims memory before several GB
 * of weights land and retries on CPU when a GPU delegate initialises but fails, a desktop that only talks to
 * network engines does neither. Injecting that step is what keeps the router one class.
 */
fun interface EngineLoadPolicy {

    suspend fun load(engine: LlmEngine, spec: ChatModelSpec, config: ChatGenerationConfig)

    companion object {
        /** No pre-flight, no fallback — correct wherever nothing resides weights in local RAM. */
        val Direct = EngineLoadPolicy { engine, spec, config -> engine.load(spec, config) }
    }
}
