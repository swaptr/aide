package com.sabreware.aide.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.sabreware.aide.core.common.storage.PlatformPaths

/**
 * The app's one preferences file, built for every target — a new platform supplies [PlatformPaths] (which it
 * needs for downloads anyway) and gets storage for free.
 *
 * `filesDir/datastore/<name>` is exactly what Android's `preferencesDataStoreFile()` produces, so the
 * existing Android file is picked up unchanged.
 *
 * Internal because `datastore-preferences-core` is an `implementation` dependency of `:shared` — consumers
 * depend on [PreferenceStore].
 */
fun userPreferencesDataStore(
    paths: PlatformPaths,
    name: String = "user_prefs",
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath {
    paths.filesDir / "datastore" / "$name.preferences_pb"
}
