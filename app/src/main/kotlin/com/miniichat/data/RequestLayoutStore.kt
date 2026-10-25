package com.miniichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.layoutDataStore: DataStore<Preferences> by preferencesDataStore(name = "request_layout")

/**
 * Saves the card list of the Request Control Panel.
 * While nothing is saved (null), the app builds the cards from the old Settings.
 */
class RequestLayoutStore(private val context: Context) {
    private val key = stringPreferencesKey("layout_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val layoutFlow: Flow<RequestLayout?> = context.layoutDataStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map null
        runCatching { json.decodeFromString(RequestLayout.serializer(), raw) }.getOrNull()
    }

    suspend fun snapshot(): RequestLayout? = layoutFlow.first()

    suspend fun save(layout: RequestLayout) {
        context.layoutDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(RequestLayout.serializer(), layout)
        }
    }
}
