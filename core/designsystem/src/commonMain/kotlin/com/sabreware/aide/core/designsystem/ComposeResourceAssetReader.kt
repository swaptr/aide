package com.sabreware.aide.core.designsystem

import com.sabreware.aide.core.common.storage.BundledAssetReader
import com.sabreware.aide.core.designsystem.resources.Res

/**
 * [BundledAssetReader] over the Compose Multiplatform resource bundle. It lives here because this module
 * owns `composeResources/` and the generated `Res` accessor; consumers below the UI layer (e.g. the
 * models.dev registry in `:data`) take the port and get this implementation injected.
 */
object ComposeResourceAssetReader : BundledAssetReader {
    override suspend fun readBytes(path: String): ByteArray = Res.readBytes(path)
}
