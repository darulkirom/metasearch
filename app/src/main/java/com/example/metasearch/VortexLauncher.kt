package com.example.metasearch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Mengirim link video ke Vortex Player lewat Intent ACTION_VIEW.
 *
 * Vortex Player harus punya intent-filter untuk VIEW dengan skema http/https dan
 * mime video (lihat README). Extra yang dikirim:
 *  - "title"   : judul video
 *  - "headers" : String[] {"Referer", ..., "User-Agent", ...}, dibutuhkan kalau CDN
 *                menolak request tanpa header itu
 *  - "urls"    : String[] semua link yang lolos pemeriksaan (elemen pertama = data intent)
 *  - "labels"  : String[] label tiap link ("1080p", "720p", "HLS", ...), sejajar dengan "urls"
 */
object VortexLauncher {

    // GANTI dengan applicationId Vortex Player lo (ada di app/build.gradle proyek Vortex)
    const val VORTEX_PACKAGE = "com.vortex.player"

    /** [streams] tidak boleh kosong; elemen pertama diputar dulu, sisanya jadi pilihan kualitas. */
    fun play(context: Context, streams: List<StreamSource>, title: String, referer: String) {
        val stream = streams.first()
        val mime = if (stream.isHls) "application/x-mpegURL" else "video/mp4"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(stream.url), mime)
            putExtra("title", title)
            putExtra("urls", streams.map { it.url }.toTypedArray())
            putExtra("labels", labelsFor(streams))
            putExtra(
                "headers",
                arrayOf("Referer", referer, "User-Agent", StreamResolver.USER_AGENT)
            )
        }

        try {
            context.startActivity(Intent(intent).setPackage(VORTEX_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            // Vortex Player belum terpasang / package salah / intent-filter belum ada
            context.startActivity(Intent.createChooser(intent, "Putar dengan"))
        }
    }

    private fun labelsFor(streams: List<StreamSource>): Array<String> {
        val seen = HashMap<String, Int>()
        return streams.map { s ->
            val base = s.quality?.let { "${it}p" } ?: if (s.isHls) "HLS" else "MP4"
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n > 1) "$base ($n)" else base
        }.toTypedArray()
    }
}
