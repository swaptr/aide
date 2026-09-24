package com.sabreware.aide.desktop.storage

import com.sabreware.aide.core.common.storage.PlatformPaths
import okio.Path
import okio.Path.Companion.toPath

/**
 * Desktop [PlatformPaths] over [DesktopAppDirs], so commonMain storage code composes subtrees under the
 * directory THIS host expects — `~/.local/share/aide` on Linux, `~/Library/Application Support/AIDE` on
 * macOS, `%APPDATA%\AIDE` on Windows.
 */
class DesktopPlatformPaths(dirs: DesktopAppDirs) : PlatformPaths {
    override val filesDir: Path = dirs.data.absolutePath.toPath()
    override val cacheDir: Path = dirs.cache.absolutePath.toPath()
}
