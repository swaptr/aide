package com.sabreware.aide.core.domain.image

import com.sabreware.aide.core.domain.model.ImageModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The image models the user's connections serve, as the UI and the image tool see them.
 *
 * A port for the same reason [com.sabreware.aide.core.domain.catalog.ModelCatalog] is one: `:ui` may
 * depend on ports and never on `:data`. Each connection contributes its vendor's curated rows — image
 * endpoints do not advertise which of their models draw — under its own ids, so two accounts of a vendor
 * list the same model twice, once per account, and a pick names the account it draws with.
 */
interface ImageModelCatalog {
    /** Every connection's image models; null until the connections have been read. */
    val models: StateFlow<List<ImageModelSpec>?>

    /** What is known right now. For painting. */
    val all: List<ImageModelSpec> get() = models.value.orEmpty()

    fun findById(id: String): ImageModelSpec? = all.firstOrNull { it.id == id }

    /** The models one connection serves, in display order. */
    fun forProvider(provider: ProviderId): List<ImageModelSpec> = all.filter { it.provider == provider }

    /** Used when the user has not picked one; null when no connection draws. */
    val default: ImageModelSpec? get() = all.firstOrNull()
}

/** [ImageModelCatalog.findById] once the connections are known — for acting, never for painting. */
suspend fun ImageModelCatalog.awaitById(id: String): ImageModelSpec? =
    models.filterNotNull().first().firstOrNull { it.id == id }

/** The recommended default once the connections are known. */
suspend fun ImageModelCatalog.awaitDefault(): ImageModelSpec? = models.filterNotNull().first().firstOrNull()
