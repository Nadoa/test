package com.tokisync.viewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tokisync.viewer.data.Episode
import com.tokisync.viewer.data.Series
import com.tokisync.viewer.ui.AppViewModel
import com.tokisync.viewer.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodesScreen(vm: AppViewModel, series: Series, snackbar: SnackbarHostState) {
    val state by vm.episodes.collectAsState()
    val historyMap by vm.history.entries.collectAsState()
    val downloaded by vm.store.downloaded.collectAsState()
    val active by vm.store.active.collectAsState()

    var descending by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<Episode>?>(null) }

    val items = if (state.seriesId == series.id) state.items else emptyList()
    val shown = remember(items, descending) { if (descending) items.reversed() else items }
    val latest = remember(historyMap, series.id) { vm.history.latestForSeries(series.id) }
    val latestEp = remember(latest, items) { latest?.let { l -> items.firstOrNull { it.id == l.episodeId } } }

    val listState = rememberLazyListState()
    var scrolledOnce by rememberSaveable(series.id) { mutableStateOf(false) }
    LaunchedEffect(shown, latestEp) {
        if (!scrolledOnce && shown.isNotEmpty()) {
            scrolledOnce = true
            val idx = latestEp?.let { e -> shown.indexOfFirst { it.id == e.id } } ?: -1
            if (idx > 2) listState.scrollToItem(idx + 1 - 2) // 헤더(1) 고려, 살짝 위에서 보이도록
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(series.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = { descending = !descending }) {
                        Icon(
                            if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            contentDescription = "정렬 순서",
                        )
                    }
                    IconButton(onClick = { vm.loadEpisodes(series, force = true) }, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "더보기")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("안 읽은 회차 모두 받기") },
                                onClick = {
                                    menu = false
                                    vm.downloadEpisodes(items.filter { !historyMap.containsKey(it.id) })
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("전체 회차 받기") },
                                onClick = { menu = false; vm.downloadEpisodes(items) },
                            )
                            if (active.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("다운로드 중지") },
                                    onClick = { menu = false; vm.cancelDownloads() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("받은 회차 모두 삭제") },
                                onClick = {
                                    menu = false
                                    confirmDelete = items.filter { downloaded.contains(it.id) }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                items.isEmpty() && state.loading -> CenterMessage("회차 목록을 불러오는 중…")
                items.isEmpty() && state.error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("❌ ${state.error}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.loadEpisodes(series, force = false) }) { Text("다시 시도") }
                }
                items.isEmpty() -> CenterMessage("회차가 없습니다")
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Header(vm, series, items, latestEp)
                    }
                    items(shown, key = { it.id }) { ep ->
                        val h = historyMap[ep.id]
                        EpisodeRow(
                            ep = ep,
                            isRead = h != null,
                            percent = h?.percent,
                            isLatest = ep.id == latestEp?.id,
                            isDownloaded = downloaded.contains(ep.id),
                            progress = active[ep.id],
                            onOpen = { vm.openEpisode(series, ep) },
                            onDownload = { vm.downloadEpisodes(listOf(ep)) },
                            onDelete = { confirmDelete = listOf(ep) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("받은 파일 삭제") },
            text = { Text("${targets.size}개 회차의 기기 저장 파일을 삭제합니다. (Drive 원본은 그대로)") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDownloads(targets)
                    confirmDelete = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun Header(vm: AppViewModel, series: Series, items: List<Episode>, latestEp: Episode?) {
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        SeriesCover(
            vm, series,
            Modifier
                .width(96.dp)
                .height(134.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(series.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (series.author.isNotBlank()) {
                Text(series.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${series.category} · ${items.size}화",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (latestEp != null) {
                Button(onClick = { vm.openEpisode(series, latestEp) }) {
                    Text("이어 보기 · ${latestEp.displayTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val next = vm.neighbor(latestEp, +1)
                if (next != null) {
                    OutlinedButton(onClick = { vm.openEpisode(series, next) }) {
                        Text("다음 화", maxLines = 1)
                    }
                }
            } else {
                items.firstOrNull { !it.isFolder }?.let { first ->
                    Button(onClick = { vm.openEpisode(series, first) }) { Text("첫 화 보기") }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    isRead: Boolean,
    percent: Int?,
    isLatest: Boolean,
    isDownloaded: Boolean,
    progress: Float?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (isLatest) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ep.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isRead && !isLatest) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurface,
            )
            val sub = buildList {
                if (isLatest) add("최근 읽음")
                else if (isRead) add("읽음")
                if (percent != null && percent in 1..99) add("$percent%")
                if (ep.size > 0) add(formatBytes(ep.size))
                if (ep.isFolder) add("폴더형(미지원)")
            }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                progress != null -> if (progress < 0f) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                isDownloaded -> IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DownloadDone, contentDescription = "받음 (눌러서 삭제)", tint = MaterialTheme.colorScheme.primary)
                }
                !ep.isFolder -> IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "받기")
                }
            }
        }
    }
}
