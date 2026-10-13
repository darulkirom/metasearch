package com.example.metasearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Saran kata kunci dari layanan autocomplete publik (format OpenSearch:
 * ["kata", ["saran 1", "saran 2", ...]]).
 *
 * - Yang dikirim ke layanan itu hanya teks yang sedang diketik; riwayat tetap lokal.
 * - Kalau gagal / offline, hasilnya kosong saja (tidak ada error di UI).
 * - Mau ganti penyedia atau mematikan saran online: ubah [ENDPOINT] atau [ENABLED].
 */
class SuggestionProvider(
    client: OkHttpClient = OkHttpClient()
) {
    private val http = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()

    suspend fun fetch(query: String): List<String> {
        if (!ENABLED) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ENDPOINT + URLEncoder.encode(query, "UTF-8"))
                    .header("User-Agent", "Mozilla/5.0 (Android) MetaSearch/1.0")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<String>()
                    val body = resp.body?.string() ?: return@use emptyList<String>()
                    val arr = JSONArray(body).optJSONArray(1) ?: return@use emptyList<String>()
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<String>()
            }
        }
    }

    private companion object {
        const val ENABLED = true
        const val ENDPOINT = "https://suggestqueries.google.com/complete/search?client=firefox&q="
    }
}
