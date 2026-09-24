package com.sabreware.aide.core.common.storage

import okio.Path

/**
 * App-private storage roots as okio paths. The platform supplies the concrete dirs (Android `Context`,
 * desktop `~/.aide`) via Koin; commonMain download/storage code composes subtrees under [filesDir] /
 * [cacheDir] without touching `java.io.File`.
 */
interface PlatformPaths {
    val filesDir: Path
    val cacheDir: Path
}
