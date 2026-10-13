package com.example.metasearch

/**
 * Satu adapter = satu sumber. Kalau sebuah sumber rusak atau berubah format,
 * cukup perbaiki adapter itu saja tanpa menyentuh bagian lain.
 */
interface SearchAdapter {
    val name: String
    suspend fun search(query: String, page: Int = 1): List<SearchResult>
}
