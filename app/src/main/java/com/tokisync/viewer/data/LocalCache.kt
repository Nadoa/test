package com.tokisync.viewer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 오프라인 시작을 위한 라이브러리/회차 목록 JSON 캐시 */
class LocalCache(context: Context) {
    private val root = File(context.filesDir, "lists").apply { mkdirs() }

    suspend fun loadLibrary(): List<JSONObject> = read(File(root, "library.json"))
    suspend fun saveLibrary(list: List<JSONObject>) = write(File(root, "library.json"), list)

    suspend fun loadEpisodes(seriesId: String): List<JSONObject> = read(File(root, "ep_${safe(seriesId)}.json"))
    suspend fun saveEpisodes(seriesId: String, list: List<JSONObject>) =
        write(File(root, "ep_${safe(seriesId)}.json"), list)

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private suspend fun read(f: File): List<JSONObject> = withContext(Dispatchers.IO) {
        if (!f.exists()) return@withContext emptyList()
        runCatching { parseJsonArray(f.readText())?.objects().orEmpty() }.getOrElse { emptyList() }
    }

    private suspend fun write(f: File, list: List<JSONObject>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONArray(list).toString())
            if (!tmp.renameTo(f)) {
                f.delete(); tmp.renameTo(f)
            }
        }
        Unit
    }
}
