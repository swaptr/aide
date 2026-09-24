package com.sabreware.aide.core.domain.model

/**
 * How a model occupies memory once "in use", which decides whether the [ResidencyManager]
 * refcounts and evicts it.
 *
 *  - [NONE] — stateless to us. Remote chat engines (Ollama) and the Android system recognizer/
 *    synthesizer hold no native weights *we* manage, so there is nothing to keep resident or evict.
 *    [ResidencyManager.acquire] hands back a no-op handle: never refcounted, timed, or trim-evicted.
 *  - [LOADED] — holds native weights in our process (LiteRT-LM, Sherpa-ONNX). Refcounted so it is
 *    never closed mid-use, idle-released on a keepAlive timer once the last hold drops, and
 *    LRU-evictable under memory pressure.
 *
 * The trait lets the manager stay backend-agnostic — it switches on residency class, never on which
 * provider produced the model.
 */
enum class Residency { NONE, LOADED }
