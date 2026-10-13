package com.example.metasearch

import kotlinx.coroutines.CancellationException
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
                    // Satu sumber gagal tidak boleh menjatuhkan sumber lain,
                    // tapi errornya dicatat supaya bisa ditampilkan di UI
                    try {
                        SourceResult(adapter.search(q, page), null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SourceResult(
                            emptyList(),
                            "${adapter.name}: ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }.awaitAll()
        }

        val merged = interleave(perSource.map { it.items })
            .filterNot { ContentFilter.isBlocked(it.title) || ContentFilter.isBlocked(it.tags) }
            .distinctBy { it.url }

        return SearchOutcome(
            results = merged,
            blocked = false,
            errors = perSource.mapNotNull { it.error }
        )
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

private data class SourceResult(val items: List<SearchResult>, val error: String?)

data class SearchOutcome(
    val results: List<SearchResult>,
    val blocked: Boolean,
    val errors: List<String> = emptyList()
)
