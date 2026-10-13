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
 * Tiap kandidat diperiksa dulu (status HTTP dan jenis konten) sebelum dikirim ke pemutar,
 * supaya halaman error atau file yang salah tidak sampai ke Vortex Player.
 *
 * Kalau sebuah sumber menyembunyikan link di balik request lain (XHR/JS), hasilnya kosong
 * dan perlu resolver khusus untuk sumber itu.
 */
class StreamResolver(private val client: OkHttpClient = OkHttpClient()) {

    /** Pilih mp4 kualitas tertinggi yang lolos pemeriksaan; HLS (m3u8) sebagai cadangan. */
    suspend fun resolveBest(pageUrl: String): StreamSource {
        val candidates = resolveAll(pageUrl)
        if (candidates.isEmpty()) {
            throw IOException(
                "Link video tidak ditemukan di halaman ini. " +
                    "Resolver perlu disesuaikan untuk sumber ini."
            )
        }

        val ordered = candidates.filter { !it.isHls }.sortedByDescending { it.quality ?: 0 } +
            candidates.filter { it.isHls }

        val problems = mutableListOf<String>()
        for (candidate in ordered.take(MAX_TRIES)) {
            val problem = check(candidate, pageUrl) ?: return candidate
            problems.add(problem)
        }
        throw IOException("Link ditemukan tapi tidak bisa diputar:\n" + problems.joinToString("\n"))
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

    /** Mengembalikan null kalau link layak diputar, atau alasan gagalnya. */
    private suspend fun check(source: StreamSource, pageUrl: String): String? =
        withContext(Dispatchers.IO) {
            val label = shorten(source.url)
            try {
                val request = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", pageUrl)
                    .header("Range", "bytes=0-1023")
                    .build()

                client.newCall(request).execute().use { resp ->
                    val type = resp.header("Content-Type").orEmpty().lowercase()
                    when {
                        resp.code != 200 && resp.code != 206 -> "HTTP ${resp.code}: $label"
                        type.startsWith("text/html") || type.contains("json") ->
                            "balasan $type, bukan video: $label"
                        source.isHls &&
                            resp.body?.string().orEmpty().trimStart().startsWith("#EXTM3U").not() ->
                            "bukan playlist HLS: $label"
                        else -> null
                    }
                }
            } catch (e: IOException) {
                "${e.javaClass.simpleName}: $label"
            }
        }

    private fun shorten(url: String): String {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return url.take(60)
        val last = uri.path.orEmpty().substringAfterLast('/')
        return "${uri.host}/…/${last.take(40)}"
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

        private const val MAX_TRIES = 4

        private val absoluteRegex =
            Regex("""(?:https?:)?//[^\s"'<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'<>\\]*)?""")
        private val relativeRegex =
            Regex("""["'](/[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?)["']""")
        private val qualityRegex = Regex("""(2160|1440|1080|720|480|360|240)p""")
    }
}
