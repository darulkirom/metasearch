package com.example.metasearch

data class SearchResult(
    val title: String,
    val thumbnailUrl: String,
    val url: String,
    val source: String,
    // Kata kunci/tag dari sumber (kalau ada), ikut disaring oleh ContentFilter
    val tags: String = ""
)
