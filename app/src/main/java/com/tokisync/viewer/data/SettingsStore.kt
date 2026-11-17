package com.tokisync.viewer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NovelTheme(val label: String) { LIGHT("라이트"), SEPIA("세피아"), DARK("다크") }

data class ViewerPrefs(
    val defaultMode: ReaderMode = ReaderMode.SCROLL,
    val pageRtl: Boolean = false,
    val volumeKeys: Boolean = true,
    val preloadNext: Boolean = true,
    val directDrive: Boolean = true,
    val downloadThreads: Int = 3,
    val novelTheme: NovelTheme = NovelTheme.DARK,
    val novelFontSp: Int = 18,
    val novelLineHeight: Float = 1.8f,
    val cacheLimitMb: Int = 3072,
)

/** GAS 연결 정보 + 뷰어 설정 (SharedPreferences) */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("toki_settings", Context.MODE_PRIVATE)

    private val _gasId = MutableStateFlow(prefs.getString(KEY_GAS_ID, "").orEmpty())
    private val _folderId = MutableStateFlow(prefs.getString(KEY_FOLDER_ID, "").orEmpty())
    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())
    private val _viewer = MutableStateFlow(loadViewer())

    val gasId: StateFlow<String> = _gasId.asStateFlow()
    val folderId: StateFlow<String> = _folderId.asStateFlow()
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()
    val viewer: StateFlow<ViewerPrefs> = _viewer.asStateFlow()

    val isConfigured: Boolean
        get() = _gasId.value.isNotBlank() && _folderId.value.isNotBlank()

    fun saveConnection(gasUrlOrId: String, folderId: String, apiKey: String) {
        val id = extractGasId(gasUrlOrId)
        prefs.edit()
            .putString(KEY_GAS_ID, id)
            .putString(KEY_FOLDER_ID, folderId.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
        _gasId.value = id
        _folderId.value = folderId.trim()
        _apiKey.value = apiKey.trim()
    }

    fun updateViewer(transform: (ViewerPrefs) -> ViewerPrefs) {
        val next = transform(_viewer.value)
        _viewer.value = next
        prefs.edit()
            .putString("v_mode", next.defaultMode.name)
            .putBoolean("v_rtl", next.pageRtl)
            .putBoolean("v_volume", next.volumeKeys)
            .putBoolean("v_preload", next.preloadNext)
            .putBoolean("v_direct", next.directDrive)
            .putInt("v_threads", next.downloadThreads)
            .putString("v_ntheme", next.novelTheme.name)
            .putInt("v_nfont", next.novelFontSp)
            .putFloat("v_nline", next.novelLineHeight)
            .putInt("v_cache", next.cacheLimitMb)
            .apply()
    }

    /** 작품별로 사용자가 고른 보기 모드 */
    fun seriesMode(seriesId: String): ReaderMode? =
        prefs.getString("mode_$seriesId", null)?.let { runCatching { ReaderMode.valueOf(it) }.getOrNull() }

    fun setSeriesMode(seriesId: String, mode: ReaderMode) {
        prefs.edit().putString("mode_$seriesId", mode.name).apply()
    }

    private fun loadViewer(): ViewerPrefs {
        val d = ViewerPrefs()
        return ViewerPrefs(
            defaultMode = enumOr(prefs.getString("v_mode", null), d.defaultMode),
            pageRtl = prefs.getBoolean("v_rtl", d.pageRtl),
            volumeKeys = prefs.getBoolean("v_volume", d.volumeKeys),
            preloadNext = prefs.getBoolean("v_preload", d.preloadNext),
            directDrive = prefs.getBoolean("v_direct", d.directDrive),
            downloadThreads = prefs.getInt("v_threads", d.downloadThreads),
            novelTheme = enumOr(prefs.getString("v_ntheme", null), d.novelTheme),
            novelFontSp = prefs.getInt("v_nfont", d.novelFontSp),
            novelLineHeight = prefs.getFloat("v_nline", d.novelLineHeight),
            cacheLimitMb = prefs.getInt("v_cache", d.cacheLimitMb),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: fallback

    companion object {
        private const val KEY_GAS_ID = "gas_id"
        private const val KEY_FOLDER_ID = "folder_id"
        private const val KEY_API_KEY = "api_key"

        private val GAS_URL_REGEX = Regex("/s/([^/]+)/(exec|dev)")

        /** 전체 배포 URL을 넣어도 배포 ID만 추출 (웹 뷰어와 동일 규칙) */
        fun extractGasId(input: String): String {
            val trimmed = input.trim()
            return GAS_URL_REGEX.find(trimmed)?.groupValues?.get(1) ?: trimmed
        }
    }
}
