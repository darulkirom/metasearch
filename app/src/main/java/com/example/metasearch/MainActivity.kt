package com.example.metasearch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {

    // Daftar sumber. Tambah sumber baru = tambah satu adapter di sini.
    // Isi endpoint dan nama field dari dokumentasi resmi situs yang lo pilih.
    private val repository = SearchRepository(
        listOf(
            // Eporner Webmaster API v2 (https://www.eporner.com/api/v2/)
            JsonFeedAdapter(
                name = "Eporner",
                urlTemplate = "https://www.eporner.com/api/v2/video/search/" +
                    "?query={query}&page={page}&per_page=30&thumbsize=big" +
                    "&order=most-popular&lq=0&format=json",
                listPath = "videos",
                titleKey = "title",
                thumbKey = "default_thumb.src",
                linkKey = "url",
                tagsKey = "keywords"
            )
        )
    )

    private val history by lazy { SearchHistory(applicationContext) }

    private val vm: SearchViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(repository, history) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SearchScreen(vm)
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: SearchViewModel) {
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Cari...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) })
        )

        Box(Modifier.fillMaxSize().padding(top = 12.dp)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.blocked -> Text(
                    "Pencarian ini diblokir oleh filter.",
                    Modifier.align(Alignment.Center)
                )
                !state.searched -> HistoryList(
                    items = history,
                    onPick = { picked ->
                        query = picked
                        vm.search(picked)
                    },
                    onRemove = vm::removeHistory,
                    onClear = vm::clearHistory
                )
                state.results.isEmpty() -> Text(
                    "Tidak ada hasil.",
                    Modifier.align(Alignment.Center)
                )
                else -> ResultsGrid(state = state, onLoadMore = vm::loadMore)
            }
        }
    }
}

@Composable
private fun HistoryList(
    items: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    if (items.isEmpty()) {
        Text(
            "Belum ada riwayat.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClear) { Text("Hapus semua") }
            }
            LazyColumn {
                items(items, key = { it }) { q ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(q) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(q, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onRemove(q) }) { Text("✕") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsGrid(state: UiState, onLoadMore: () -> Unit) {
    // State grid dibuat ulang tiap pencarian baru (grid keluar dari komposisi saat loading),
    // jadi posisi scroll otomatis kembali ke atas.
    val gridState = rememberLazyGridState()

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }

    // results.size ikut jadi key: kalau halaman baru masih pendek dan akhir daftar
    // tetap terlihat, pemuatan berikutnya dipicu lagi.
    LaunchedEffect(nearEnd, state.results.size) {
        if (nearEnd) onLoadMore()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.results, key = { it.url }) { ResultCard(it) }

        if (state.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: SearchResult) {
    val context = LocalContext.current
    // Thumbnail blur sampai diketuk sekali; ketukan kedua buka link
    var revealed by remember(result.url) { mutableStateOf(false) }
    // Modifier.blur baru berfungsi di Android 12+; di bawah itu pakai scrim hampir solid
    val scrimAlpha = if (Build.VERSION.SDK_INT >= 31) 0.25f else 0.92f

    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                if (!revealed) revealed = true
                else context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                )
            }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            AsyncImage(
                model = result.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (revealed) Modifier
                        else Modifier.blur(28.dp, BlurredEdgeTreatment.Unbounded)
                    )
            )
            if (!revealed) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
            }
        }
        Text(
            result.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            result.source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
