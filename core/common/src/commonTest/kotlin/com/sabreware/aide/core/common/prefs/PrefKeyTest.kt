package com.sabreware.aide.core.common.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(PrefStorageApi::class)
class PrefKeyTest {

    @Test
    fun `absent value reads the default`() {
        val key = stringKey("greeting", default = "hi")
        assertEquals("hi", key.read(mutablePreferencesOf()) ?: key.default)
    }

    @Test
    fun `round trips a written value`() {
        val key = intKey("count", default = 0)
        val prefs = mutablePreferencesOf()
        key.write(prefs, 7)
        assertEquals(7, key.read(prefs))
    }

    @Test
    fun `range clamps on write and on read`() {
        val key = floatKey("scale", default = 1f, range = 0.8f..1.4f)
        val prefs = mutablePreferencesOf()
        key.write(prefs, 9f)
        assertEquals(1.4f, key.read(prefs))

        // A value stored before the range existed (or by a newer build) is clamped coming back out too.
        prefs[androidx.datastore.preferences.core.floatPreferencesKey("scale")] = -3f
        assertEquals(0.8f, key.read(prefs))
    }

    /** Stands in for a real preference enum (e.g. ThemeMode) — declared here so this module's test does not
     *  reach up into the domain layer for a fixture. */
    private enum class Theme { System, Dark }

    @Test
    fun `enum survives a round trip and falls back when the constant is gone`() {
        val key = enumKey("theme", default = Theme.System)
        val prefs = mutablePreferencesOf()
        key.write(prefs, Theme.Dark)
        assertEquals(Theme.Dark, key.read(prefs))

        prefs[stringPreferencesKey("theme")] = "Solarized"
        assertEquals(null, key.read(prefs), "unknown constant must fall back, not throw")
    }

    @Test
    fun `tier ownership follows the ui prefix`() {
        assertTrue(Tier.UiState.owns("ui.sidebar_open"))
        assertTrue(Tier.Settings.owns("font_scale"))
        assertTrue(!Tier.Settings.owns("ui.sidebar_open"))
        assertTrue(!Tier.UiState.owns("font_scale"))
    }

    @Test
    fun `a mislabelled key fails loudly at declaration`() {
        assertFailsWith<IllegalArgumentException> { boolKey("sidebar_open", false, Tier.UiState) }
        assertFailsWith<IllegalArgumentException> { boolKey("ui.theme", false, Tier.Settings) }
    }
}
