package com.sabreware.aide.desktop.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where each desktop keeps app state.
 *
 * This is the only place in the codebase where Linux, macOS and Windows are told apart, because they are
 * one Kotlin target and nothing in the module graph can separate them. That makes it worth pinning: a
 * regression here does not fail to compile, it silently writes a user's database somewhere their OS does
 * not expect and their backups do not cover.
 *
 * `env` and `home` are constructor parameters precisely so this can be asserted for all three hosts from
 * whichever one is running the test.
 */
class DesktopAppDirsTest {

    private fun dirs(
        host: DesktopHost,
        env: Map<String, String> = emptyMap(),
        home: String = "/home/u",
    ) = DesktopAppDirs(host = host, env = { env[it] }, home = home)

    @Test
    fun `linux follows XDG, and falls back to the spec's own defaults`() {
        val plain = dirs(DesktopHost.LINUX)
        assertEquals(File("/home/u/.local/share/aide"), plain.dataPath())
        assertEquals(File("/home/u/.cache/aide"), plain.cachePath())

        val xdg = dirs(
            DesktopHost.LINUX,
            env = mapOf("XDG_DATA_HOME" to "/data", "XDG_CACHE_HOME" to "/tmpcache"),
        )
        assertEquals(File("/data/aide"), xdg.dataPath())
        assertEquals(File("/tmpcache/aide"), xdg.cachePath())
    }

    /** The XDG spec says a relative value is invalid and must be ignored — not joined onto something. */
    @Test
    fun `a relative XDG value is ignored rather than resolved against the cwd`() {
        val d = dirs(DesktopHost.LINUX, env = mapOf("XDG_DATA_HOME" to "relative/path"))
        assertEquals(File("/home/u/.local/share/aide"), d.dataPath())
    }

    @Test
    fun `an empty XDG value is treated as unset`() {
        val d = dirs(DesktopHost.LINUX, env = mapOf("XDG_DATA_HOME" to "   "))
        assertEquals(File("/home/u/.local/share/aide"), d.dataPath())
    }

    @Test
    fun `macOS uses Application Support and Caches, not a dot-directory`() {
        val d = dirs(DesktopHost.MACOS, home = "/Users/u")
        assertEquals(File("/Users/u/Library/Application Support/AIDE"), d.dataPath())
        assertEquals(File("/Users/u/Library/Caches/AIDE"), d.cachePath())
    }

    @Test
    fun `windows splits roaming state from local cache`() {
        val d = dirs(
            DesktopHost.WINDOWS,
            env = mapOf("APPDATA" to "/C/Users/u/AppData/Roaming", "LOCALAPPDATA" to "/C/Users/u/AppData/Local"),
            home = "/C/Users/u",
        )
        assertEquals(File("/C/Users/u/AppData/Roaming/AIDE"), d.dataPath())
        assertEquals(File("/C/Users/u/AppData/Local/AIDE/Cache"), d.cachePath())
    }

    /** A Windows install with the variables missing still has to land somewhere sane, not at the fs root. */
    @Test
    fun `windows falls back under the home directory when the env is missing`() {
        val d = dirs(DesktopHost.WINDOWS, home = "/C/Users/u")
        assertEquals(File("/C/Users/u/AppData/Roaming/AIDE"), d.dataPath())
        assertEquals(File("/C/Users/u/AppData/Local/AIDE/Cache"), d.cachePath())
    }

    @Test
    fun `data and cache are always distinct — deleting the cache must not delete the database`() {
        DesktopHost.entries.forEach { host ->
            val d = dirs(host, env = mapOf("APPDATA" to "/roam", "LOCALAPPDATA" to "/local"))
            assertTrue(d.dataPath() != d.cachePath(), "$host reuses one directory for both")
        }
    }

    @Test
    fun `os names map to the right host`() {
        assertEquals(DesktopHost.MACOS, DesktopHost.current("Mac OS X"))
        assertEquals(DesktopHost.WINDOWS, DesktopHost.current("Windows 11"))
        assertEquals(DesktopHost.LINUX, DesktopHost.current("Linux"))
        // Unknown JVM hosts (FreeBSD, Solaris) behave as Unix rather than throwing.
        assertEquals(DesktopHost.LINUX, DesktopHost.current("FreeBSD"))
    }
}
