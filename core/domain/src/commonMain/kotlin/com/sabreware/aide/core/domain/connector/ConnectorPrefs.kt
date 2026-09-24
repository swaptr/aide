package com.sabreware.aide.core.domain.connector

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.enumKey
import com.sabreware.aide.core.common.prefs.stringSetKey
import com.sabreware.aide.core.domain.prefs.OAuthRedirectStrategy

/** Connector preferences. Key names and defaults match what `UserPreferencesRepository` stored. */
object ConnectorPrefs {
    /** Which redirect strategy MCP-connector OAuth uses (loopback vs custom scheme). */
    val OAuthRedirect = enumKey("oauth_redirect_strategy", default = OAuthRedirectStrategy.LOOPBACK)

    /** The directories the user turned OFF. Storing the *disabled* set means a newly added built-in
     *  directory defaults to ON. */
    val DisabledDirectories = stringSetKey("disabled_connector_directories")
}

/** Enable/disable one connector directory by id (toggles membership of the stored disabled set). */
suspend fun PreferenceStore.setConnectorDirectoryEnabled(id: String, enabled: Boolean) {
    update(ConnectorPrefs.DisabledDirectories) { disabled ->
        if (enabled) disabled - id else disabled + id
    }
}
