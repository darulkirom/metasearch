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
 */
object VortexLauncher {

    // GANTI dengan applicationId Vortex Player lo (ada di app/build.gradle proyek Vortex)
    const val VORTEX_PACKAGE = "com.vortex.player"

    fun play(context: Context, stream: StreamSource, title: String, referer: String) {
        val mime = if (stream.isHls) "application/x-mpegURL" else "video/mp4"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(stream.url), mime)
            putExtra("title", title)
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
}
