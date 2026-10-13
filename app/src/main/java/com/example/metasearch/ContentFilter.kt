package com.example.metasearch

/**
 * Filter konten yang menyangkut anak di bawah umur. Dipakai dua kali:
 * pada query (sebelum dikirim ke sumber) dan pada judul tiap hasil.
 *
 * Daftar ini sengaja konservatif. Tambah sendiri kalau ada istilah yang lolos.
 */
object ContentFilter {

    private val terms = listOf(
        "boti", "gay", "lgbt", "lgbtq+"
    )

    private val termRegexes = terms.map {
        Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE)
    }

    // Menangkap usia di bawah 18, mis. "15 yo", "16 years", "12 y/o"
    private val ageRegex = Regex(
        """\b(0?[1-1]|1[0-2])\s*(yo|y/o|yr|yrs|year|years)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isBlocked(text: String): Boolean =
        termRegexes.any { it.containsMatchIn(text) } || ageRegex.containsMatchIn(text)
}
