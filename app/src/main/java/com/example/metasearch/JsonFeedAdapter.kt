package com.example.metasearch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Adapter generik untuk sumber yang punya feed/API JSON.
 *
 * Isi parameternya dari dokumentasi resmi (webmaster/affiliate feed) situs yang lo pilih:
 *  - urlTemplate : URL endpoint, pakai {query} dan {page} sebagai placeholder
 *  - listPath    : path ke array hasil di JSON, mis. "videos" atau "data.items"
 *  - titleKey / thumbKey / linkKey : path field di tiap item (boleh nested pakai titik)
 *  - tagsKey     : (opsional) path field kata kunci/tag, ikut disaring oleh ContentFilter
 */
class JsonFeedAdapter(
    override val name: String,
    private val urlTemplate: String,
    private val listPath: String,
    private val titleKey: String,
    private val thumbKey: String,
    private val linkKey: String,
    private val tagsKey: String? = null,
    private val client: OkHttpClient = OkHttpClient()
) : SearchAdapter {

    override suspend fun search(query: String, page: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = urlTemplate
                .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                .replace("{page}", page.toString())

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MetaSearch/1.0")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string() ?: return@use emptyList()
                val arr = resolve(JSONObject(body), listPath) as? JSONArray
                    ?: return@use emptyList()

                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = resolve(item, titleKey)?.toString().orEmpty()
                    val thumb = resolve(item, thumbKey)?.toString().orEmpty()
                    val link = resolve(item, linkKey)?.toString().orEmpty()
                    val tags = tagsKey?.let { resolve(item, it)?.toString() }.orEmpty()
                    if (title.isBlank() || thumb.isBlank() || link.isBlank()) null
                    else SearchResult(title, thumb, link, name, tags)
                }
            }
        }

    private fun resolve(start: Any?, path: String): Any? {
        var cur: Any? = start
        for (key in path.split(".")) {
            cur = (cur as? JSONObject)?.opt(key) ?: return null
        }
        return cur
    }
}
