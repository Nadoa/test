package com.tokisync.viewer.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.tokisync.viewer.data.Episode
import com.tokisync.viewer.data.NovelTheme
import com.tokisync.viewer.data.PageImage
import com.tokisync.viewer.data.ReaderContent
import com.tokisync.viewer.data.ReaderMode
import com.tokisync.viewer.data.TextBlock
import com.tokisync.viewer.data.ViewerPrefs
import com.tokisync.viewer.ui.AppViewModel
import com.tokisync.viewer.ui.ReaderState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun ReaderScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val st by vm.reader.collectAsState()
    val prefs by vm.settings.viewer.collectAsState()
    var controls by remember { mutableStateOf(false) }
    val content = st.content

    val bg = if (content is ReaderContent.Text) novelColors(prefs.novelTheme).first else Color.Black
    SystemBars(hidden = content != null && !controls)

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            st.error != null -> StatusView(vm, st) {
                Text("❌ ${st.error}", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.back() }) { Text("뒤로") }
                    Button(onClick = { vm.retryReader() }) { Text("다시 시도") }
                }
            }
            content == null -> StatusView(vm, st) {
                if (st.progress >= 0f) {
                    CircularProgressIndicator(progress = { st.progress }, color = Color.White)
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
                Spacer(Modifier.height(16.dp))
                Text(st.status, color = Color.White)
            }
            else -> key(st.session, st.mode) {
                ReaderBody(
                    vm = vm,
                    st = st,
                    content = content,
                    prefs = prefs,
                    controlsVisible = controls,
                    onToggleControls = { controls = !controls },
                )
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (controls) 96.dp else 8.dp),
        )
    }
}

@Composable
private fun StatusView(vm: AppViewModel, st: ReaderState, body: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Color.White)
            }
            Text(
                st.episode?.displayTitle.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { body() }
    }
}

@Composable
private fun ReaderBody(
    vm: AppViewModel,
    st: ReaderState,
    content: ReaderContent,
    prefs: ViewerPrefs,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
) {
    val total = content.size
    val mode = st.mode
    val episode = st.episode ?: return
    val next = remember(episode.id) { vm.neighbor(episode, +1) }
    val prev = remember(episode.id) { vm.neighbor(episode, -1) }
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = st.startIndex)
    val pagerState = rememberPagerState(initialPage = st.startIndex) { total + 1 } // 마지막: '다음 화' 페이지

    val currentIndex by remember(mode) {
        derivedStateOf {
            val raw = if (mode == ReaderMode.PAGE) pagerState.currentPage else listState.firstVisibleItemIndex
            raw.coerceIn(0, (total - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(mode) {
        snapshotFlow { currentIndex }.distinctUntilChanged().collect { vm.onReaderPosition(it, total) }
    }

    fun pageStep(direction: Int) {
        scope.launch {
            if (mode == ReaderMode.PAGE) {
                val target = (pagerState.currentPage + direction).coerceIn(0, total)
                pagerState.animateScrollToPage(target)
            } else {
                val h = listState.layoutInfo.viewportSize.height
                listState.animateScrollBy(direction * h * 0.85f)
            }
        }
    }

    LaunchedEffect(mode) {
        vm.pageKeys.collect { dir -> pageStep(dir) }
    }

    val endBlock: @Composable () -> Unit = {
        EndOfEpisode(
            next = next,
            dark = content !is ReaderContent.Text || prefs.novelTheme == NovelTheme.DARK,
            onNext = { vm.goNeighbor(+1) },
            onBack = { vm.back() },
        )
    }

    Box(Modifier.fillMaxSize()) {
        when {
            content is ReaderContent.Text -> NovelText(
                blocks = content.blocks,
                listState = listState,
                prefs = prefs,
                onTapCenter = onToggleControls,
                onPage = { pageStep(it) },
                footer = endBlock,
            )
            content is ReaderContent.Images && mode == ReaderMode.PAGE -> PagedImages(
                pages = content.pages,
                pagerState = pagerState,
                rtl = prefs.pageRtl,
                onTapCenter = onToggleControls,
                endPage = endBlock,
            )
            content is ReaderContent.Images -> ScrollImages(
                pages = content.pages,
                listState = listState,
                onTap = onToggleControls,
                footer = endBlock,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopControls(vm, st, content, prefs)
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomControls(
                current = currentIndex,
                total = total,
                hasPrev = prev != null,
                hasNext = next != null,
                onPrev = { vm.goNeighbor(-1) },
                onNext = { vm.goNeighbor(+1) },
                onSeek = { idx ->
                    scope.launch {
                        if (mode == ReaderMode.PAGE) pagerState.scrollToPage(idx) else listState.scrollToItem(idx)
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ scroll (webtoon)

@Composable
private fun ScrollImages(
    pages: List<PageImage>,
    listState: LazyListState,
    onTap: () -> Unit,
    footer: @Composable () -> Unit,
) {
    val zoom = remember { ZoomState() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .zoomGestures(zoom, PanMode.HORIZONTAL_ONLY)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos -> zoom.toggle(pos, size, target = 2f) },
                )
            },
    ) {
        val widthPx = constraints.maxWidth
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .zoomTransform(zoom),
        ) {
            itemsIndexed(pages, key = { _, p -> p.file.name }) { _, p ->
                PageImageView(
                    page = p,
                    maxWidthPx = widthPx,
                    maxHeightPx = Int.MAX_VALUE,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(p.width.toFloat() / p.height.toFloat()),
                )
            }
            item(key = "__end") { footer() }
        }
    }
}

// ------------------------------------------------------------------ pages (manga)

@Composable
private fun PagedImages(
    pages: List<PageImage>,
    pagerState: PagerState,
    rtl: Boolean,
    onTapCenter: () -> Unit,
    endPage: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    HorizontalPager(
        state = pagerState,
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        if (index >= pages.size) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { endPage() }
        } else {
            val page = pages[index]
            val zoom = remember { ZoomState() }
            LaunchedEffect(pagerState.settledPage) {
                if (pagerState.settledPage != index) zoom.reset()
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .zoomGestures(zoom, PanMode.ALL)
                    .pointerInput(rtl) {
                        detectTapGestures(
                            onTap = { pos ->
                                val third = size.width / 3f
                                val dir = when {
                                    zoom.isZoomed -> 0
                                    pos.x < third -> if (rtl) +1 else -1
                                    pos.x > third * 2 -> if (rtl) -1 else +1
                                    else -> 0
                                }
                                if (dir == 0) {
                                    onTapCenter()
                                } else {
                                    val target = (pagerState.currentPage + dir).coerceIn(0, pagerState.pageCount - 1)
                                    scope.launch { pagerState.animateScrollToPage(target) }
                                }
                            },
                            onDoubleTap = { pos -> zoom.toggle(pos, size) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                PageImageView(
                    page = page,
                    maxWidthPx = (constraints.maxWidth * 1.5f).toInt(),
                    maxHeightPx = (constraints.maxHeight * 1.5f).toInt(),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomTransform(zoom),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ novel

fun novelColors(theme: NovelTheme): Pair<Color, Color> = when (theme) {
    NovelTheme.LIGHT -> Color(0xFFFFFFFF) to Color(0xFF222222)
    NovelTheme.SEPIA -> Color(0xFFF4ECD8) to Color(0xFF5B4636)
    NovelTheme.DARK -> Color(0xFF121212) to Color(0xFFD8D8D8)
}

@Composable
private fun NovelText(
    blocks: List<TextBlock>,
    listState: LazyListState,
    prefs: ViewerPrefs,
    onTapCenter: () -> Unit,
    onPage: (Int) -> Unit,
    footer: @Composable () -> Unit,
) {
    val (bg, fg) = novelColors(prefs.novelTheme)
    val font = prefs.novelFontSp
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos ->
                    val h = size.height
                    when {
                        pos.y < h * 0.25f -> onPage(-1)
                        pos.y > h * 0.75f -> onPage(+1)
                        else -> onTapCenter()
                    }
                })
            },
    ) {
        val widthPx = constraints.maxWidth
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 56.dp),
        ) {
            itemsIndexed(blocks) { _, b ->
                when (b) {
                    is TextBlock.Paragraph -> Text(
                        b.text,
                        color = fg,
                        fontSize = font.sp,
                        lineHeight = (font * prefs.novelLineHeight).sp,
                        modifier = Modifier.padding(bottom = (font * 0.7f).dp),
                    )
                    is TextBlock.Heading -> Text(
                        b.text,
                        color = fg,
                        fontSize = (font * 1.3f).sp,
                        lineHeight = (font * 1.3f * 1.4f).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                    )
                    is TextBlock.Picture -> PageImageView(
                        page = b.image,
                        maxWidthPx = widthPx,
                        maxHeightPx = Int.MAX_VALUE,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .aspectRatio(b.image.width.toFloat() / b.image.height.toFloat()),
                    )
                    TextBlock.Divider -> HorizontalDivider(
                        Modifier.padding(vertical = 36.dp),
                        color = fg.copy(alpha = 0.25f),
                    )
                }
            }
            item(key = "__end") { footer() }
        }
    }
}

// ------------------------------------------------------------------ shared pieces

@Composable
private fun PageImageView(
    page: PageImage,
    maxWidthPx: Int,
    maxHeightPx: Int,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val request = remember(page.file, maxWidthPx, maxHeightPx) {
        // 화면 폭에 맞춘 크기로 디코딩 + 지나치게 큰 비트맵(긴 웹툰 컷) 방지
        var w = minOf(page.width, maxWidthPx.coerceAtLeast(1))
        var h = (page.height.toLong() * w / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        if (h > maxHeightPx) {
            h = maxHeightPx
            w = (page.width.toLong() * h / page.height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        }
        val maxPixels = 16_000_000.0
        val pixels = w.toDouble() * h
        if (pixels > maxPixels) {
            val f = sqrt(maxPixels / pixels)
            w = (w * f).toInt().coerceAtLeast(1)
            h = (h * f).toInt().coerceAtLeast(1)
        }
        ImageRequest.Builder(ctx)
            .data(page.file)
            .size(w, h)
            .precision(Precision.INEXACT)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

@Composable
private fun EndOfEpisode(next: Episode?, dark: Boolean, onNext: () -> Unit, onBack: () -> Unit) {
    val fg = if (dark) Color(0xFFDDDDDD) else Color(0xFF333333)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 64.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("— 이번 화 끝 —", color = fg.copy(alpha = 0.7f))
        if (next != null) {
            Button(onClick = onNext) {
                Text("다음 화 · ${next.displayTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("마지막 회차입니다", color = fg)
        }
        OutlinedButton(onClick = onBack) { Text("목록으로") }
    }
}

@Composable
private fun TopControls(vm: AppViewModel, st: ReaderState, content: ReaderContent, prefs: ViewerPrefs) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xE6151515))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.back() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                st.episode?.displayTitle.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                st.series?.name.orEmpty(),
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (content is ReaderContent.Images) {
            if (st.mode == ReaderMode.PAGE) {
                IconButton(onClick = { vm.settings.updateViewer { it.copy(pageRtl = !it.pageRtl) } }) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = if (prefs.pageRtl) "오른쪽→왼쪽" else "왼쪽→오른쪽",
                        tint = if (prefs.pageRtl) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { vm.setMode(ReaderMode.SCROLL) }) {
                    Icon(Icons.Filled.ViewDay, contentDescription = "세로 스크롤로 보기", tint = Color.White)
                }
            } else {
                IconButton(onClick = { vm.setMode(ReaderMode.PAGE) }) {
                    Icon(Icons.Filled.MenuBook, contentDescription = "페이지 넘김으로 보기", tint = Color.White)
                }
            }
        } else {
            TextButton(onClick = {
                vm.settings.updateViewer { it.copy(novelFontSp = (it.novelFontSp - 1).coerceAtLeast(12)) }
            }) { Text("가-", color = Color.White, fontSize = 13.sp) }
            TextButton(onClick = {
                vm.settings.updateViewer { it.copy(novelFontSp = (it.novelFontSp + 1).coerceAtMost(32)) }
            }) { Text("가+", color = Color.White, fontSize = 17.sp) }
            IconButton(onClick = {
                vm.settings.updateViewer {
                    val all = NovelTheme.entries
                    it.copy(novelTheme = all[(all.indexOf(it.novelTheme) + 1) % all.size])
                }
            }) { Icon(Icons.Filled.Palette, contentDescription = "테마 (${prefs.novelTheme.label})", tint = Color.White) }
            IconButton(onClick = {
                vm.settings.updateViewer {
                    val next = ((it.novelLineHeight * 10).roundToInt() + 2).let { v -> if (v > 26) 12 else v } / 10f
                    it.copy(novelLineHeight = next)
                }
            }) { Icon(Icons.Filled.FormatSize, contentDescription = "줄 간격", tint = Color.White) }
        }
    }
}

@Composable
private fun BottomControls(
    current: Int,
    total: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xE6151515))
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = hasPrev) {
            Icon(
                Icons.Filled.SkipPrevious, contentDescription = "이전 화",
                tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        if (total > 1) {
            Slider(
                value = drag ?: current.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { onSeek(it.roundToInt()) }
                    drag = null
                },
                valueRange = 0f..(total - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            "${((drag?.roundToInt() ?: current) + 1)}/$total",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(
                Icons.Filled.SkipNext, contentDescription = "다음 화",
                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

// ------------------------------------------------------------------ system bars

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SystemBars(hidden: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(hidden, activity) {
        val window = activity?.window
        if (window != null) {
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) ctrl.hide(WindowInsetsCompat.Type.systemBars())
            else ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(activity) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
