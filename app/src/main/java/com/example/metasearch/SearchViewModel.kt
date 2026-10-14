package com.example.metasearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val searched: Boolean = false,
    val blocked: Boolean = false,
    val endReached: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val errors: List<String> = emptyList(),
    // Nama situs yang dipilih di filter; null = semua sumber
    val selectedSource: String? = null
) {
    /** Hasil yang ditampilkan: semua, atau hanya dari situs yang dipilih. */
    val visible: List<SearchResult>
        get() = selectedSource?.let { s -> results.filter { it.source == s } } ?: results
}

class SearchViewModel(
    private val repo: SearchRepository,
    private val historyStore: SearchHistory,
    private val suggestionProvider: SuggestionProvider = SuggestionProvider()
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val history: StateFlow<List<String>> = historyStore.queries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Teks yang sedang diketik, dipakai untuk saran
    private val typing = MutableStateFlow("")

    fun onQueryChange(text: String) {
        typing.value = text
    }

    /**
     * Saran kata kunci: riwayat yang cocok tampil duluan (instan), lalu saran online menyusul.
     * Query yang kena ContentFilter tidak diproses, dan saran online disaring dengan filter yang sama.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<List<String>> = typing
        .debounce(250)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isEmpty() || ContentFilter.isBlocked(q)) {
                flowOf(emptyList<String>())
            } else {
                flow {
                    val local = history.value.filter { it.contains(q, ignoreCase = true) }.take(3)
                    emit(cleanSuggestions(q, local))
                    emit(cleanSuggestions(q, local + suggestionProvider.fetch(q)))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun cleanSuggestions(query: String, items: List<String>): List<String> =
        items.map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(query, ignoreCase = true) }
            .filterNot { ContentFilter.isBlocked(it) }
            .distinctBy { it.lowercase() }
            .take(8)

    private var currentQuery = ""
    private var page = 1
    private var job: Job? = null

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        currentQuery = q
        page = 1
        job = viewModelScope.launch {
            _state.value = UiState(loading = true, searched = true)
            val outcome = repo.search(q, page = 1)
            if (!outcome.blocked) historyStore.add(q)
            _state.value = UiState(
                searched = true,
                blocked = outcome.blocked,
                endReached = outcome.blocked || outcome.results.isEmpty(),
                results = outcome.results,
                errors = outcome.errors
            )
        }
    }

    /** Dipanggil UI saat pengguna mendekati akhir daftar. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.blocked || s.results.isEmpty()) return
        val q = currentQuery
        if (q.isEmpty()) return

        job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val next = page + 1
            val outcome = repo.search(q, page = next)
            if (q != currentQuery) return@launch // query sudah berganti selama loading

            val existing = _state.value.results.mapTo(HashSet()) { it.url }
            val fresh = outcome.results.filter { it.url !in existing }
            if (fresh.isEmpty()) {
                _state.update { it.copy(loadingMore = false, endReached = true) }
            } else {
                page = next
                _state.update { it.copy(loadingMore = false, results = it.results + fresh) }
            }
        }
    }

    fun selectSource(source: String?) {
        _state.update { it.copy(selectedSource = source) }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { historyStore.remove(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }
}
