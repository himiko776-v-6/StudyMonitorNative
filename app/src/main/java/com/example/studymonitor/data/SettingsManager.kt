package com.example.studymonitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        const val API_MODE_NONE = 0
        const val API_MODE_QIANWEN = 1
        const val API_MODE_LOCAL = 2

        private val KEY_API_MODE = intPreferencesKey("api_mode")
        private val KEY_QIANWEN_API_KEY = stringPreferencesKey("qianwen_api_key")
        private val KEY_CLOUD_API_URL = stringPreferencesKey("cloud_api_url")
        private val KEY_LOCAL_API_URL = stringPreferencesKey("local_api_url")
        private val KEY_CUSTOM_INTERVAL = intPreferencesKey("custom_interval")
    }

    var apiMode: Int
        get() = runBlocking {
            context.dataStore.data.map { prefs ->
                prefs[KEY_API_MODE] ?: API_MODE_NONE
            }.first()
        }
        set(value) = runBlocking {
            context.dataStore.edit { prefs ->
                prefs[KEY_API_MODE] = value
            }
        }

    var qianwenApiKey: String
        get() = runBlocking {
            context.dataStore.data.map { prefs ->
                prefs[KEY_QIANWEN_API_KEY] ?: ""
            }.first()
        }
        set(value) = runBlocking {
            context.dataStore.edit { prefs ->
                prefs[KEY_QIANWEN_API_KEY] = value
            }
        }

    var localApiUrl: String
        get() = runBlocking {
            context.dataStore.data.map { prefs ->
                prefs[KEY_LOCAL_API_URL] ?: ""
            }.first()
        }
        set(value) = runBlocking {
            context.dataStore.edit { prefs ->
                prefs[KEY_LOCAL_API_URL] = value
            }
        }

    var customInterval: Int
        get() = runBlocking {
            context.dataStore.data.map { prefs ->
                prefs[KEY_CUSTOM_INTERVAL] ?: 180
            }.first()
        }
        set(value) = runBlocking {
            context.dataStore.edit { prefs ->
                prefs[KEY_CUSTOM_INTERVAL] = value
            }
        }

    var cloudApiUrl: String
        get() = runBlocking {
            context.dataStore.data.map { prefs ->
                prefs[KEY_CLOUD_API_URL] ?: ""
            }.first()
        }
        set(value) = runBlocking {
            context.dataStore.edit { prefs ->
                prefs[KEY_CLOUD_API_URL] = value
            }
        }
}