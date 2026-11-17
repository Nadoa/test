package com.tokisync.viewer.ui

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tokisync.viewer.TokiApp
import com.tokisync.viewer.data.Episode
import com.tokisync.viewer.data.ReaderContent
import com.tokisync.viewer.data.ReaderMode
import com.tokisync.viewer.data.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface Screen {
    data object Settings : Screen
    data object Library : Screen
    data class Episodes(val series: Series) : Screen
    data class Reader(val series: Series, val episode: Episode) : Screen
}

data class LibraryState(
    val items: List<Series> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class EpisodesState(
    val seriesId: String = "",
    val items: List<Episode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReaderState(
    val series: Series? = null,
    val episode: Episode? = null,
    val mode: ReaderMode = ReaderMode.SCROLL,
    val loading: Boolean = false,
    val progress: Float = -1f,
    val status: String = "",
    val content: ReaderContent? = null,
    val error: String? = null,
    val startIndex: Int = 0,
    val session: Long = 0L,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as TokiApp).container
    val settings = c.settings
    val history = c.history
    val store = c.episodes
    val thumbs = c.thumbs

    private val _backStack = MutableStateFlow<List<Screen>>(
        listOf(if (settings.isConfigured) Screen.Library else Screen.Settings)
    )
    val backStack: StateFlow<List<Screen>> = _backStack.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _episodes = MutableStateFlow(EpisodesState())
    val episodes: StateFlow<EpisodesState> = _episodes.asStateFlow()

    private val _reader = MutableStateFlow(ReaderState())
    val reader: StateFlow<ReaderState> = _reader.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 볼륨키: +1 다음, -1 이전 */
    private val _pageKeys = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val pageKeys: SharedFlow<Int> = _pageKeys.asSharedFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var libraryJob: Job? = null
    private var episodesJob: Job? = null
    private var readerJob: Job? = null
    private var preloadJob: Job? = null
    private var downloadJob: Job? = null
    private var readerSession = 0L

    init {
        if (settings.isConfigured) loadLibrary(force = false)
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    // ------------------------------------------------------------ navigation

    fun navigate(screen: Screen) {
        _backStack.update { it + screen }
    }

    fun resetTo(screen: Screen) {
        _backStack.value = listOf(screen)
    }

    /** @return 처리했으면 true (스택이 하나뿐이면 false → 앱 종료) */
    fun back(): Boolean {
        val stack = _backStack.value
        if (stack.size <= 1) return false
        if (stack.last() is Screen.Reader) leaveReader()
        _backStack.value = stack.dropLast(1)
        return true
    }

    // ------------------------------------------------------------ settings

    fun saveConnection(gasUrlOrId: String, folderId: String, apiKey: String) {
        settings.saveConnection(gasUrlOrId, folderId, apiKey)
        c.gas.invalidateToken()
        if (settings.isConfigured) {
            _library.value = LibraryState()
            resetTo(Screen.Library)
            loadLibrary(force = false)
        }
    }

    suspend fun testConnection(gasUrlOrId: String, folderId: String, apiKey: String): String {
        val prevGas = settings.gasId.value
        val prevFolder = settings.folderId.value
        val prevKey = settings.apiKey.value
        settings.saveConnection(gasUrlOrId, folderId, apiKey)
        return try {
            val info = c.gas.serverInfo()
            "✅ 연결 성공 · ${info?.optString("version").orEmpty()} ${info?.optString("user").orEmpty()}".trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "❌ ${e.message}"
        } finally {
            settings.saveConnection(prevGas, prevFolder, prevKey)
        }
    }

    // ------------------------------------------------------------ library

    fun loadLibrary(force: Boolean) {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            if (_library.value.items.isEmpty()) {
                val cached = c.cache.loadLibrary().map(Series::fromJson)
                if (cached.isNotEmpty()) _library.update { it.copy(items = cached) }
            }
            _library.update { it.copy(loading = true, error = null) }
            try {
                val raw = c.gas.getLibrary(bypassCache = force)
                c.cache.saveLibrary(raw)
                _library.update {
                    it.copy(items = raw.map(Series::fromJson).filter { s -> s.id.isNotBlank() }, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _library.update { it.copy(error = e.message ?: "라이브러리를 불러오지 못했습니다") }
            } finally {
                _library.update { it.copy(loading = false) }
            }
            syncHistory()
        }
    }

    fun syncHistory(showResult: Boolean = false) {
        viewModelScope.launch {
            _syncing.value = true
            val ok = history.syncWithDrive(c.gas)
            _syncing.value = false
            if (showResult) toast(if (ok) "✅ 읽기 기록 동기화 완료" else "❌ 읽기 기록 동기화 실패 (로컬 기록 사용)")
        }
    }

    // ------------------------------------------------------------ episodes

    fun openSeries(series: Series) {
        navigate(Screen.Episodes(series))
        loadEpisodes(series, force = false)
    }

    fun loadEpisodes(series: Series, force: Boolean) {
        episodesJob?.cancel()
        if (_episodes.value.seriesId != series.id) {
            _episodes.value = EpisodesState(seriesId = series.id)
        }
        episodesJob = viewModelScope.launch {
            if (_episodes.value.items.isEmpty()) {
                val cached = c.cache.loadEpisodes(series.id).map { Episode.fromJson(it, series.id) }
                if (cached.isNotEmpty()) _episodes.update { it.copy(items = cached) }
            }
            _episodes.update { it.copy(loading = true, error = null) }
            try {
                val raw: List<JSONObject> = if (!force && series.cacheFileId.isNotBlank()) {
                    runCatching { c.gas.getBooksByCache(series.cacheFileId) }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: c.gas.getBooks(series.id, bypassCache = false)
                } else {
                    c.gas.getBooks(series.id, bypassCache = force)
                }
                c.cache.saveEpisodes(series.id, raw)
                _episodes.update {
                    it.copy(items = raw.map { o -> Episode.fromJson(o, series.id) }.filter { e -> e.id.isNotBlank() })
                }
                if (force) history.syncWithDrive(c.gas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _episodes.update { it.copy(error = e.message ?: "회차 목록을 불러오지 못했습니다") }
            } finally {
                _episodes.update { it.copy(loading = false) }
            }
        }
    }

    fun downloadEpisodes(list: List<Episode>) {
        val targets = list.filter { !it.isFolder && !store.isDownloaded(it.id) }
        if (targets.isEmpty()) {
            toast("받을 회차가 없습니다")
            return
        }
        val previous = downloadJob
        downloadJob = viewModelScope.launch {
            previous?.join()
            var ok = 0
            for (ep in targets) {
                try {
                    store.ensureDownloaded(ep)
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("❌ ${ep.displayTitle}: ${e.message}")
                }
            }
            if (targets.size > 1) toast("📥 ${ok}/${targets.size}개 다운로드 완료")
        }
    }

    fun cancelDownloads() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun deleteDownloads(list: List<Episode>) {
        store.delete(list.map { it.id })
    }

    // ------------------------------------------------------------ reader

    private fun modeFor(series: Series): ReaderMode {
        settings.seriesMode(series.id)?.let { return it }
        val cat = series.categoryKey
        return when {
            "webtoon" in cat || "웹툰" in cat -> ReaderMode.SCROLL
            "manga" in cat || "만화" in cat || "comic" in cat -> ReaderMode.PAGE
            "novel" in cat || "소설" in cat -> ReaderMode.NOVEL
            else -> settings.viewer.value.defaultMode
        }
    }

    fun openEpisode(series: Series, episode: Episode, replace: Boolean = false) {
        if (episode.isFolder) {
            toast("폴더형 회차는 지원하지 않습니다 (CBZ/ZIP/EPUB만 지원)")
            return
        }
        if (replace && _backStack.value.lastOrNull() is Screen.Reader) {
            _backStack.update { it.dropLast(1) + Screen.Reader(series, episode) }
        } else {
            navigate(Screen.Reader(series, episode))
        }
        history.markOpened(episode.id, series.id)
        loadReader(series, episode)
    }

    fun retryReader() {
        val st = _reader.value
        val s = st.series ?: return
        val e = st.episode ?: return
        loadReader(s, e)
    }

    private fun loadReader(series: Series, episode: Episode) {
        readerJob?.cancel()
        val session = ++readerSession
        _reader.value = ReaderState(
            series = series, episode = episode, mode = modeFor(series),
            loading = true, status = "준비 중...", session = session,
        )
        readerJob = viewModelScope.launch {
            try {
                val file = store.ensureDownloaded(episode) { p ->
                    _reader.update {
                        if (it.session != session) it
                        else it.copy(progress = p, status = if (p < 0) "다운로드 중..." else "다운로드 중... ${(p * 100).toInt()}%")
                    }
                }
                _reader.update { it.copy(status = "압축 해제 중...", progress = -1f) }
                val content = store.openContent(episode.id, file)
                val start = (history.get(episode.id)?.markerIndex ?: 0).coerceIn(0, (content.size - 1).coerceAtLeast(0))
                val mode = when {
                    content is ReaderContent.Text -> ReaderMode.NOVEL
                    _reader.value.mode == ReaderMode.NOVEL -> settings.viewer.value.defaultMode
                        .takeIf { it != ReaderMode.NOVEL } ?: ReaderMode.SCROLL
                    else -> _reader.value.mode
                }
                _reader.update {
                    it.copy(loading = false, content = content, startIndex = start, mode = mode, status = "")
                }
                if (settings.viewer.value.preloadNext) preloadNext(episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reader.update { it.copy(loading = false, error = e.message ?: "불러오기 실패") }
            }
        }
    }

    private fun preloadNext(current: Episode) {
        val next = neighbor(current, +1) ?: return
        if (store.isDownloaded(next.id)) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            runCatching { store.ensureDownloaded(next) }
        }
    }

    fun neighbor(current: Episode, delta: Int): Episode? {
        val list = _episodes.value.items.takeIf { _episodes.value.seriesId == current.seriesId }
            ?: return null
        val idx = list.indexOfFirst { it.id == current.id }
        if (idx < 0) return null
        return list.getOrNull(idx + delta)?.takeIf { !it.isFolder }
    }

    fun goNeighbor(delta: Int) {
        val st = _reader.value
        val series = st.series ?: return
        val ep = st.episode ?: return
        val target = neighbor(ep, delta)
        if (target == null) {
            toast(if (delta > 0) "마지막 회차입니다" else "첫 회차입니다")
            return
        }
        pushHistoryLater()
        openEpisode(series, target, replace = true)
        toast(if (delta > 0) "⏩ ${target.displayTitle}" else "⏪ ${target.displayTitle}")
    }

    fun setMode(mode: ReaderMode) {
        val st = _reader.value
        val series = st.series ?: return
        if (st.content is ReaderContent.Text) return
        settings.setSeriesMode(series.id, mode)
        val start = st.episode?.let { history.get(it.id)?.markerIndex } ?: 0
        _reader.update { it.copy(mode = mode, startIndex = start) }
    }

    fun onReaderPosition(index: Int, total: Int) {
        val st = _reader.value
        val ep = st.episode ?: return
        if (st.content == null) return
        history.updateMarker(ep.id, ep.seriesId, index, total)
    }

    private fun leaveReader() {
        readerJob?.cancel()
        preloadJob?.cancel()
        _reader.value = ReaderState()
        pushHistoryLater()
    }

    private fun pushHistoryLater() {
        viewModelScope.launch {
            history.flush()
            history.syncWithDrive(c.gas)
        }
    }

    /** MainActivity 에서 호출. 리더 화면이고 설정이 켜져 있으면 키를 소비 */
    fun onVolumeKey(keyCode: Int): Boolean {
        if (_backStack.value.lastOrNull() !is Screen.Reader) return false
        if (!settings.viewer.value.volumeKeys) return false
        if (_reader.value.content == null) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> _pageKeys.tryEmit(+1).let { true }
            KeyEvent.KEYCODE_VOLUME_UP -> _pageKeys.tryEmit(-1).let { true }
            else -> false
        }
    }

    // ------------------------------------------------------------ storage

    fun cacheUsageBytes(): Long = store.usageBytes()

    fun clearDownloads() {
        store.clearAll()
        toast("🗑 저장된 회차를 모두 삭제했습니다")
    }
}
