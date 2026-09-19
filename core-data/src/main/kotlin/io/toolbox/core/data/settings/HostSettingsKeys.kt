package io.toolbox.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object HostSettingsKeys {
    val catalogLayout = stringPreferencesKey("catalog_layout")
    val theme = stringPreferencesKey("theme")
    val backgroundEnabled = booleanPreferencesKey("background_enabled")
    val themeStyle = stringPreferencesKey("theme_style")
    val reduceTransparency = booleanPreferencesKey("reduce_transparency")
}
