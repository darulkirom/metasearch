package com.example.metasearch

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.historyStore by preferencesDataStore(name = "search_history")

/**
 * Riwayat pencarian yang disimpan lokal di perangkat (tidak dikirim ke server).
 * Query yang diblokir filter tidak pernah disimpan.
 */
class SearchHistory(context: Context) {

    private val store = context.applicationContext.historyStore
    private val key = stringPreferencesKey("queries")

    val queries: Flow<List<String>> = store.data.map { decode(it[key]) }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        store.edit { prefs ->
            val current = decode(prefs[key])
            val updated = (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) })
                .take(MAX_ITEMS)
            prefs[key] = encode(updated)
        }
    }

    suspend fun remove(query: String) {
        store.edit { prefs ->
            prefs[key] = encode(decode(prefs[key]).filterNot { it == query })
        }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun encode(list: List<String>): String = JSONArray(list).toString()

    private companion object {
        const val MAX_ITEMS = 30
    }
}
