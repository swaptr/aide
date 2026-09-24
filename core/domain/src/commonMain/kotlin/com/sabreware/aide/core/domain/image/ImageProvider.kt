package com.sabreware.aide.core.domain.image

import com.sabreware.aide.core.domain.llm.Provider
import com.sabreware.aide.core.domain.provider.ProviderRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Serves the image modality. A capability of a [Provider]; `id` is inherited. */
interface ImageProvider : Provider {
    val image: ImageEngine
}

/** Every image-capable provider the running application contributed. */
class ImageProviderRegistry(
    providers: Collection<ImageProvider>,
    connected: StateFlow<List<ImageProvider>?> = MutableStateFlow(emptyList<ImageProvider>()),
) : ProviderRegistry<ImageProvider>(providers, connected)
