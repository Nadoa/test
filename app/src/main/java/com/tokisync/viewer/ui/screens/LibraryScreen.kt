package com.tokisync.viewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tokisync.viewer.data.Series
import com.tokisync.viewer.ui.AppViewModel
import com.tokisync.viewer.ui.Screen

private enum class SortMode(val label: String) { RECENT("최근 읽음"), UPDATED("최근 업데이트"), NAME("이름순") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val state by vm.library.collectAsState()
    val historyMap by vm.history.entries.collectAsState()
    val syncing by vm.syncing.collectAsState()

    var tab by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(SortMode.RECENT.name) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = SortMode.valueOf(sortName)

    val categories = remember(state.items) {
        state.items.map { it.category }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sorted()
    }
    val lastRead = remember(historyMap) { vm.history.lastReadBySeries() }

    val visible = remember(state.items, tab, query, sort, lastRead) {
        val q = query.trim().lowercase()
        val filtered = state.items.filter { s ->
            !s.name.startsWith("_") &&
                (tab == "all" || s.categoryKey == tab) &&
                (q.isEmpty() || s.name.lowercase().contains(q) || s.author.lowercase().contains(q))
        }
        when (sort) {
            SortMode.NAME -> filtered.sortedBy { it.name }
            SortMode.UPDATED -> filtered.sortedByDescending { it.lastModified }
            SortMode.RECENT -> filtered.sortedWith(
                compareByDescending<Series> { lastRead[it.id].orEmpty() }.thenByDescending { it.lastModified }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("제목·작가 검색") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("TokiSync")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) query = ""
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "검색")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "정렬")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text((if (m == sort) "✓ " else "   ") + m.label) },
                                    onClick = { sortName = m.name; sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.syncHistory(showResult = true) }, enabled = !syncing) {
                        Icon(Icons.Filled.Sync, contentDescription = "읽기 기록 동기화")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (categories.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = tab == "all", onClick = { tab = "all" }, label = { Text("전체") })
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = tab == cat.lowercase(),
                            onClick = { tab = cat.lowercase() },
                            label = { Text(cat) },
                        )
                    }
                }
            }
            if (state.loading && state.items.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.error != null && state.items.isNotEmpty()) {
                Text(
                    "⚠ ${state.error} (저장된 목록 표시 중)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = state.loading && state.items.isEmpty(),
                onRefresh = { vm.loadLibrary(force = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.items.isEmpty() && state.loading -> CenterMessage("라이브러리를 불러오는 중…\n(처음에는 GAS가 색인을 만드느라 오래 걸릴 수 있습니다)")
                    state.items.isEmpty() && state.error != null -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("❌ ${state.error}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.loadLibrary(force = false) }) { Text("다시 시도") }
                    }
                    visible.isEmpty() -> CenterMessage(if (state.items.isEmpty()) "작품이 없습니다" else "검색 결과가 없습니다")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visible, key = { it.id }) { s ->
                            SeriesCard(vm, s, hasRead = lastRead.containsKey(s.id)) { vm.openSeries(s) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(vm: AppViewModel, series: Series, hasRead: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            SeriesCover(
                vm, series,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            if (hasRead) {
                Text(
                    "읽는 중",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC2F5FB3))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            series.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOf(series.category.takeIf { it != "Unknown" }, series.booksCount.takeIf { it > 0 }?.let { "${it}화" })
            .filterNotNull().joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
