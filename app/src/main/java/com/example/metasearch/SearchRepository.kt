package com.example.metasearch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SearchRepository(private val adapters: List<SearchAdapter>) {

    /** Jalankan semua adapter paralel, gabung, buang duplikat, dan saring. */
    suspend fun search(query: String, page: Int = 1): SearchOutcome {
        val q = query.trim()
        if (q.isEmpty()) return SearchOutcome(emptyList(), blocked = false)
        if (ContentFilter.isBlocked(q)) return SearchOutcome(emptyList(), blocked = true)

        val perSource = coroutineScope {
            adapters.map { adapter ->
                async {
                    // Satu sumber gagal tidak boleh menjatuhkan sumber lain
                    runCatching { adapter.search(q, page) }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val merged = interleave(perSource)
            .filterNot { ContentFilter.isBlocked(it.title) || ContentFilter.isBlocked(it.tags) }
            .distinctBy { it.url }

        return SearchOutcome(merged, blocked = false)
    }

    // Selang-seling antar sumber supaya satu situs tidak memenuhi bagian atas
    private fun interleave(lists: List<List<SearchResult>>): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) {
            lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        }
        return out
    }
}

data class SearchOutcome(val results: List<SearchResult>, val blocked: Boolean)
