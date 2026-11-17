package com.tokisync.viewer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 열람 기록 (웹 뷰어의 Dexie readHistory + Drive read_history.json 동기화 포팅)
 * - 로컬: files/read_history.json
 * - 원격: GAS view_history_get / view_history_save
 * - 병합: episodeId 기준, lastReadAt 이 최신인 항목 채택 (웹과 동일)
 */
class HistoryStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "read_history.json")
    private val syncMutex = Mutex()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, HistoryEntry>> = _entries.asStateFlow()

    private var saveJob: Job? = null

    fun get(episodeId: String): HistoryEntry? = _entries.value[episodeId]

    fun latestForSeries(seriesId: String): HistoryEntry? =
        _entries.value.values.filter { it.seriesId == seriesId }.maxByOrNull { it.lastReadAt }

    fun lastReadBySeries(): Map<String, String> {
        val map = HashMap<String, String>()
        for (e in _entries.value.values) {
            val cur = map[e.seriesId]
            if (cur == null || e.lastReadAt > cur) map[e.seriesId] = e.lastReadAt
        }
        return map
    }

    /** 회차를 열었을 때 (기존 마커는 유지하고 시각만 갱신) */
    fun markOpened(episodeId: String, seriesId: String) {
        val existing = _entries.value[episodeId]
        val json = if (existing != null) {
            JSONObject(existing.json.toString()).put("lastReadAt", nowIso())
        } else {
            JSONObject()
                .put("episodeId", episodeId)
                .put("seriesId", seriesId)
                .put("lastReadAt", nowIso())
                .put("progress", 0)
        }
        put(json)
    }

    /** 읽는 위치 저장 (웹 뷰어의 markerIndex 와 같은 의미: 이미지/문단 인덱스) */
    fun updateMarker(episodeId: String, seriesId: String, index: Int, total: Int) {
        val existing = _entries.value[episodeId]
        if (existing != null && existing.markerIndex == index && existing.totalCount == total) return
        val json = (existing?.json?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("episodeId", episodeId)
            .put("seriesId", seriesId)
            .put("progress", 0))
            .put("markerIndex", index)
            .put("appTotal", total)
            .put("lastReadAt", nowIso())
        put(json)
    }

    fun remove(episodeIds: Collection<String>) {
        if (episodeIds.isEmpty()) return
        _entries.value = _entries.value - episodeIds.toSet()
        scheduleSave()
    }

    private fun put(json: JSONObject) {
        val entry = HistoryEntry(json)
        _entries.value = _entries.value + (entry.episodeId to entry)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(800)
            writeLocal(_entries.value)
        }
    }

    suspend fun flush() {
        saveJob?.cancel()
        withContext(Dispatchers.IO) { writeLocal(_entries.value) }
    }

    /** Drive 이력 pull → merge → 로컬 저장 → Drive push (웹 뷰어 syncHistoryFromDrive 와 동일 흐름) */
    suspend fun syncWithDrive(gas: GasClient): Boolean = syncMutex.withLock {
        try {
            val remote = gas.getReadHistory() ?: JSONArray()
            val merged = HashMap<String, HistoryEntry>()
            for (o in remote.objects()) {
                val e = HistoryEntry(o)
                if (e.episodeId.isNotBlank()) merged[e.episodeId] = e
            }
            for (local in _entries.value.values) {
                val r = merged[local.episodeId]
                if (r == null || local.lastReadAt > r.lastReadAt) merged[local.episodeId] = local
            }
            _entries.value = merged
            flush()
            gas.saveReadHistory(JSONArray(merged.values.map { it.json }))
            true
        } catch (e: Exception) {
            Log.w(TAG, "History sync failed", e)
            false
        }
    }

    private fun load(): Map<String, HistoryEntry> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val arr = parseJsonArray(file.readText()) ?: return emptyMap()
            arr.objects().map { HistoryEntry(it) }.filter { it.episodeId.isNotBlank() }
                .associateBy { it.episodeId }
        }.getOrElse { emptyMap() }
    }

    @Synchronized
    private fun writeLocal(map: Map<String, HistoryEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(map.values.map { it.json }).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "HistoryStore"
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        /** JS Date.toISOString() 과 동일한 형식 (문자열 비교로 최신 판정하므로 형식이 중요) */
        fun nowIso(): String = ZonedDateTime.now(ZoneOffset.UTC).format(ISO)
    }
}
