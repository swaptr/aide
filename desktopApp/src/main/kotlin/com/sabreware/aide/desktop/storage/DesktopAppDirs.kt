package com.sabreware.aide.desktop.storage

import java.io.File
import java.util.Locale

/**
 * Which desktop the app is running on.
 *
 * Linux, macOS and Windows are **one Kotlin target** — the same `jvm("desktop")` binary runs on all three —
 * so they can never be told apart by a source set or a module. They are told apart here, once, and the
 * answer is used to pick a *binding*, never to branch inside an implementation.
 */
enum class DesktopHost {
    LINUX,
    MACOS,
    WINDOWS,
    ;

    companion object {
        fun current(osName: String = System.getProperty("os.name").orEmpty()): DesktopHost {
            val name = osName.lowercase(Locale.ROOT)
            return when {
                name.contains("mac") || name.contains("darwin") -> MACOS
                name.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * Where this host keeps an application's private state, following each one's own convention rather than
 * ours. Everything on-device — the Room DB, DataStore, the secret store, the connector cache — roots here,
 * so a user's whole footprint is two directories they can find with their file manager.
 *
 * |         | data                             | cache                        |
 * |---------|----------------------------------|------------------------------|
 * | Linux   | `$XDG_DATA_HOME/aide`            | `$XDG_CACHE_HOME/aide`       |
 * | macOS   | `~/Library/Application Support/AIDE` | `~/Library/Caches/AIDE`  |
 * | Windows | `%APPDATA%\AIDE`                 | `%LOCALAPPDATA%\AIDE\Cache`  |
 *
 * This replaced a single hard-coded `~/.aide` that lived — of all places — inside the Room database file,
 * and was imported from there by the secret store, the connector cache and `PlatformPaths`. One dot-dir for
 * every host is wrong on two of the three, and owning the answer from `:data` inverted the layering: the
 * platform decides where state lives, and the database is told.
 *
 * [env] and [home] are parameters rather than direct `System` calls so the resolution is testable without
 * mutating process globals — the same reason every other policy in this codebase is injected data.
 */
class DesktopAppDirs(
    private val host: DesktopHost = DesktopHost.current(),
    private val env: (String) -> String? = { System.getenv(it) },
    private val home: String = System.getProperty("user.home").orEmpty(),
) {
    /**
     * Where durable state goes: the database, preferences, secrets, imported models.
     *
     * Resolution and creation are separate ([data] creates) so the host conventions can be asserted without
     * a test writing directories into whatever home the test machine happens to have.
     */
    fun dataPath(): File = when (host) {
        DesktopHost.LINUX -> File(xdg("XDG_DATA_HOME", ".local/share"), APP_DIR_LOWER)
        DesktopHost.MACOS -> File(home, "Library/Application Support/$APP_DIR")
        DesktopHost.WINDOWS -> File(envDir("APPDATA") ?: File(home, "AppData/Roaming"), APP_DIR)
    }

    /** Where discardable state goes. Deleting it must never lose anything the user typed. */
    fun cachePath(): File = when (host) {
        DesktopHost.LINUX -> File(xdg("XDG_CACHE_HOME", ".cache"), APP_DIR_LOWER)
        DesktopHost.MACOS -> File(home, "Library/Caches/$APP_DIR")
        DesktopHost.WINDOWS ->
            File(envDir("LOCALAPPDATA") ?: File(home, "AppData/Local"), "$APP_DIR/Cache")
    }

    /** An XDG variable counts only when set AND absolute — the spec says to ignore a relative one. */
    private fun xdg(variable: String, fallback: String): File =
        envDir(variable) ?: File(home, fallback)

    private fun envDir(variable: String): File? =
        env(variable)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute }

    /** The durable directory, created. */
    val data: File by lazy { dataPath().apply { mkdirs() } }

    /** The cache directory, created. */
    val cache: File by lazy { cachePath().apply { mkdirs() } }

    private companion object {
        const val APP_DIR = "AIDE"
        const val APP_DIR_LOWER = "aide"
    }
}
