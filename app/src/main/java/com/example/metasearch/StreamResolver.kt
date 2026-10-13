package com.example.metasearch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

data class StreamSource(val url: String, val quality: Int?, val isHls: Boolean)

/**
 * Mengambil link video yang bisa diputar langsung (mp4 / m3u8) dari halaman video.
 *
 * Cara kerjanya generik: halaman diunduh, lalu dicari URL yang berakhiran .mp4 atau .m3u8.
 * Kalau sebuah sumber menyembunyikan link di balik request lain (XHR/JS), hasilnya kosong
 * dan perlu resolver khusus untuk sumber itu.
 */
class StreamResolver(private val client: OkHttpClient = OkHttpClient()) {

    /** Pilih mp4 kualitas tertinggi; kalau tidak ada, pakai HLS (m3u8). */
    suspend fun resolveBest(pageUrl: String): StreamSource {
        val candidates = resolveAll(pageUrl)
        return candidates.filter { !it.isHls }.maxByOrNull { it.quality ?: 0 }
            ?: candidates.firstOrNull()
            ?: throw IOException(
                "Link video tidak ditemukan di halaman ini. " +
                    "Resolver perlu disesuaikan untuk sumber ini."
            )
    }

    suspend fun resolveAll(pageUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Halaman video: HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("Halaman video kosong")
        }

        // Buang escape umum di dalam JS/JSON supaya URL bisa dikenali
        val text = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val found = LinkedHashSet<String>()
        absoluteRegex.findAll(text).forEach { found.add(normalize(it.value, pageUrl)) }
        relativeRegex.findAll(text).forEach { found.add(normalize(it.groupValues[1], pageUrl)) }

        found.map { url ->
            StreamSource(
                url = url,
                quality = qualityRegex.find(url)?.groupValues?.get(1)?.toIntOrNull(),
                isHls = url.substringBefore('?').endsWith(".m3u8")
            )
        }
    }

    private fun normalize(raw: String, pageUrl: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        else -> URI.create(pageUrl).resolve(raw).toString()
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"

        private val absoluteRegex =
            Regex("""(?:https?:)?//[^\s"'<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'<>\\]*)?""")
        private val relativeRegex =
            Regex("""["'](/[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?)["']""")
        private val qualityRegex = Regex("""(2160|1440|1080|720|480|360|240)p""")
    }
}
